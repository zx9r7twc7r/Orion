package com.example.client.bench;

import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * 2026-10-24：基准会话（把每一帧的耗时落成可复算的文件，并自己判"这次运行能不能信"）。
 *
 * <p><b>为什么需要它。</b>本工程的性能数字一直是"看日志里最后几行"：`[PERF L10 CPU]`
 * 只有窗口平均、`[FRAME BUDGET]` 只有 60 帧一行，而且没有任何东西记录"这次跑得干不干净"。
 * 参考实现（NVIDIA rtx-remix-minecraft 的 {@code BenchmarkController} + {@code tools/benchmark}）
 * 把这件事拆成三件必须分开的东西，缺一件结论就不可比：</p>
 *
 * <ol>
 *   <li><b>逐帧原始数据</b>（{@code frames.jsonl}）：每帧一行的间隔与 {@code render()}
 *       耗时，事后可以重算任何分位数，不需要信任任何"当时算好的平均值"；</li>
 *   <li><b>心跳状态</b>（{@code benchmark/status.json}）：外部监视器只看它是否前进，
 *       就能区分"还在跑"与"卡死"（进程活着但没帧）。冻结判据刻意不含序号自增之外的
 *       任何东西 —— 见 {@code tools/orion_bench/orion_bench.py}；</li>
 *   <li><b>可信性判定</b>（{@code summary.json} 的 {@code valid}/{@code reasons}）：
 *       退化帧（几乎空帧）比例、超长帧、预热帧数。这一层是参考实现里最容易被忽略、
 *       也最容易让人得出错误结论的一条：一次"平均 3 ms"的运行，可能 40% 是空帧。</li>
 * </ol>
 *
 * <p><b>开销。</b>未开启时每帧只有一次 {@code static final boolean} 判空。开启后每帧
 * 两次 {@code System.nanoTime()}、每帧一行 JSON（缓冲，60 帧 flush 一次）、每 30 帧
 * 一次 status.json 原子替换。所有 IO 失败都会自己关掉会话并打一行日志，绝不影响渲染。</p>
 *
 * <p><b>开关。</b>{@code -Dorion.bench=1} 开启；{@code -Dorion.bench.frames=N} 跑满 N
 * 帧后自动收尾；{@code -Dorion.bench.warmup=N}（默认 120）帧不计入统计；
 * {@code -Dorion.bench.dir=<相对 gameDir 的子目录>} 换产物目录；
 * {@code -Dorion.bench.tag=<标签>} 写进元数据，便于 {@code compare} 分组。</p>
 */
public final class BenchmarkSession {

    public static final double DEGENERATE_FRAME_FLOOR_MS = 3.5;
    public static final double VERY_LONG_FRAME_MS = 5000.0;
    public static final double DEGENERATE_RATIO_LIMIT = 0.01;

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("orion.bench", "false"))
            || "1".equals(System.getProperty("orion.bench"));

    private static final int WARMUP_FRAMES = readInt("orion.bench.warmup", 120);
    private static final int MAX_FRAMES = readInt("orion.bench.frames", 0);
    private static final String TAG = System.getProperty("orion.bench.tag", "");

    private static final DateTimeFormatter RUN_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private static boolean initialized;
    private static boolean disabled;
    private static Path runDirectory;
    private static Path statusFile;
    private static BufferedWriter framesWriter;

    private static long frameStartNanos;
    private static long previousStartNanos;
    private static long sequence;
    private static int frameIndex;
    private static int measuredFrames;

    private static double[] intervalsMs = new double[4096];
    private static double[] renderMs = new double[4096];
    private static int degenerateFrames;
    private static int veryLongFrames;
    private static double lastFrameMs;
    private static double lastRenderMs;
    private static boolean finalized;

    private BenchmarkSession() {
    }

    private static int readInt(final String key, final int fallback) {
        final String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (final NumberFormatException failure) {
            System.out.println("[ORION BENCH] " + key + "=\"" + raw
                    + "\" is not an integer; using " + fallback);
            return fallback;
        }
    }

    private static void initialize() {
        initialized = true;
        try {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gameDirectory == null) {
                disabled = true;
                return;
            }
            final String relative = System.getProperty("orion.bench.dir", "benchmark");
            final Path base = minecraft.gameDirectory.toPath().resolve(relative);
            runDirectory = base.resolve("runs")
                    .resolve(ZonedDateTime.now().format(RUN_STAMP));
            Files.createDirectories(runDirectory);
            statusFile = base.resolve("status.json");
            framesWriter = Files.newBufferedWriter(runDirectory.resolve("frames.jsonl"),
                    StandardCharsets.UTF_8);
            Files.writeString(runDirectory.resolve("session.txt"),
                    "orion benchmark session\n"
                            + "pid=" + ProcessHandle.current().pid() + "\n"
                            + "tag=" + TAG + "\n"
                            + "warmupFrames=" + WARMUP_FRAMES + "\n"
                            + "maxFrames=" + (MAX_FRAMES == 0 ? "unbounded" : MAX_FRAMES) + "\n"
                            + "gameDir=" + minecraft.gameDirectory + "\n",
                    StandardCharsets.UTF_8);
            writeStatus("warmup");
            System.out.println("[ORION BENCH] session started dir="
                    + runDirectory.toAbsolutePath()
                    + " warmup=" + WARMUP_FRAMES
                    + " frames=" + (MAX_FRAMES == 0 ? "unbounded" : MAX_FRAMES));
            Runtime.getRuntime().addShutdownHook(new Thread(
                    BenchmarkSession::finish, "orion-bench-finalize"));
        } catch (final IOException | RuntimeException failure) {
            disabled = true;
            System.out.println("[ORION BENCH] disabled after init failure: " + failure);
        }
    }

    /** 每帧入口（{@code GameRenderer.render} HEAD）。 */
    public static void frameStart() {
        if (!ENABLED || disabled) {
            return;
        }
        if (!initialized) {
            initialize();
        }
        if (disabled) {
            return;
        }
        frameStartNanos = System.nanoTime();
        if (previousStartNanos != 0L) {
            lastFrameMs = (frameStartNanos - previousStartNanos) / 1.0e6;
        }
        previousStartNanos = frameStartNanos;
    }

    /** 每帧出口（{@code GameRenderer.render} RETURN）。 */
    public static void frameEnd() {
        if (!ENABLED || disabled || frameStartNanos == 0L) {
            return;
        }
        lastRenderMs = (System.nanoTime() - frameStartNanos) / 1.0e6;
        frameIndex++;
        sequence++;
        if (frameIndex > WARMUP_FRAMES && lastFrameMs > 0.0) {
            record(lastFrameMs, lastRenderMs);
        }
        if ((frameIndex % 30) == 0) {
            writeStatus(frameIndex > WARMUP_FRAMES ? "measuring" : "warmup");
        }
        if (MAX_FRAMES > 0 && frameIndex >= WARMUP_FRAMES + MAX_FRAMES) {
            finish();
        }
    }

    private static void record(final double interval, final double render) {
        if (measuredFrames >= intervalsMs.length) {
            int grown = intervalsMs.length * 2;
            while (measuredFrames >= grown) {
                grown *= 2;
            }
            intervalsMs = Arrays.copyOf(intervalsMs, grown);
            renderMs = Arrays.copyOf(renderMs, grown);
        }
        intervalsMs[measuredFrames] = interval;
        renderMs[measuredFrames] = render;
        measuredFrames++;
        if (interval < DEGENERATE_FRAME_FLOOR_MS) {
            degenerateFrames++;
        }
        if (interval > VERY_LONG_FRAME_MS) {
            veryLongFrames++;
        }
        if (framesWriter != null) {
            try {
                framesWriter.write(String.format(Locale.ROOT,
                        "{\"i\":%d,\"frameMs\":%.3f,\"renderMs\":%.3f}%n",
                        frameIndex, interval, render));
                if ((measuredFrames % 60) == 0) {
                    framesWriter.flush();
                }
            } catch (final IOException failure) {
                disabled = true;
                System.out.println("[ORION BENCH] frames.jsonl write failed: " + failure);
            }
        }
    }

    private static void writeStatus(final String phase) {
        if (statusFile == null) {
            return;
        }
        final String json = String.format(Locale.ROOT,
                "{\n"
                        + "  \"pid\": %d,\n"
                        + "  \"seq\": %d,\n"
                        + "  \"frame\": %d,\n"
                        + "  \"phase\": \"%s\",\n"
                        + "  \"measuredFrames\": %d,\n"
                        + "  \"lastFrameMs\": %.3f,\n"
                        + "  \"lastRenderMs\": %.3f,\n"
                        + "  \"degenerateFrames\": %d,\n"
                        + "  \"runDir\": \"%s\",\n"
                        + "  \"camPath\": \"%s\",\n"
                        + "  \"camMode\": \"%s\",\n"
                        + "  \"camWaypoints\": %d,\n"
                        + "  \"camFrame\": %d\n"
                        + "}\n",
                ProcessHandle.current().pid(),
                sequence,
                frameIndex,
                phase,
                measuredFrames,
                lastFrameMs,
                lastRenderMs,
                degenerateFrames,
                runDirectory == null ? "" : escape(runDirectory.toString()),
                escape(CameraPathDriver.activeName() == null ? "" : CameraPathDriver.activeName()),
                CameraPathDriver.modeName(),
                CameraPathDriver.waypointCount(),
                CameraPathDriver.playbackFrame());
        try {
            final Path temp = statusFile.resolveSibling("status.json.tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, statusFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException failure) {
        }
    }

    /** 收尾：写 summary.json（幂等）。 */
    public static synchronized void finish() {
        if (finalized || !ENABLED) {
            return;
        }
        finalized = true;
        try {
            if (framesWriter != null) {
                framesWriter.flush();
                framesWriter.close();
            }
            if (runDirectory == null) {
                return;
            }
            final String summary = buildSummary();
            Files.writeString(runDirectory.resolve("summary.json"), summary,
                    StandardCharsets.UTF_8);
            writeStatus("done");
            System.out.println("[ORION BENCH] summary written: "
                    + runDirectory.resolve("summary.json"));
            System.out.print(shortReport());
        } catch (final IOException | RuntimeException failure) {
            System.out.println("[ORION BENCH] finalize failed: " + failure);
        }
    }

    private static String shortReport() {
        final double[] sorted = sortedCopy(measuredFrames);
        if (sorted.length == 0) {
            return "[ORION BENCH] no measured frames\n";
        }
        return String.format(Locale.ROOT,
                "[ORION BENCH] frames=%d p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms%n",
                sorted.length, percentile(sorted, 50), percentile(sorted, 95),
                percentile(sorted, 99), sorted[sorted.length - 1]);
    }

    private static String buildSummary() {
        final double[] sorted = sortedCopy(measuredFrames);
        final boolean empty = sorted.length == 0;
        final double degenerateRatio = empty ? 1.0
                : degenerateFrames / (double) measuredFrames;
        final StringBuilder reasons = new StringBuilder();
        if (empty) {
            appendReason(reasons, "no measured frames (warmup longer than the run?)");
        }
        if (degenerateRatio > DEGENERATE_RATIO_LIMIT) {
            appendReason(reasons, String.format(Locale.ROOT,
                    "degenerate frames %.1f%% > %.1f%% -- frames below %.1fms mean "
                            + "the renderer was idle; numbers are not comparable",
                    degenerateRatio * 100.0, DEGENERATE_RATIO_LIMIT * 100.0,
                    DEGENERATE_FRAME_FLOOR_MS));
        }
        if (veryLongFrames > 0) {
            appendReason(reasons, String.format(Locale.ROOT,
                    "%d frame(s) over %.0fms -- one stall can dominate the mean; "
                            + "inspect frames.jsonl instead of the average",
                    veryLongFrames, VERY_LONG_FRAME_MS));
        }
        final boolean valid = empty || (degenerateRatio <= DEGENERATE_RATIO_LIMIT);

        return String.format(Locale.ROOT,
                "{\n"
                        + "  \"schema\": 1,\n"
                        + "  \"tag\": \"%s\",\n"
                        + "  \"framesMeasured\": %d,\n"
                        + "  \"framesWarmup\": %d,\n"
                        + "  \"valid\": %s,\n"
                        + "  \"reasons\": [%s],\n"
                        + "  \"frameMs\": { \"p50\": %.3f, \"p90\": %.3f, \"p95\": %.3f, "
                        + "\"p99\": %.3f, \"p999\": %.3f, \"min\": %.3f, \"max\": %.3f, "
                        + "\"mean\": %.3f },\n"
                        + "  \"renderMs\": { \"p50\": %.3f, \"p95\": %.3f, \"p99\": %.3f, "
                        + "\"mean\": %.3f },\n"
                        + "  \"degenerate\": { \"floorMs\": %.1f, \"count\": %d, "
                        + "\"ratio\": %.4f },\n"
                        + "  \"veryLongFrames\": %d,\n"
                        + "  \"cameraPath\": { \"name\": \"%s\", \"mode\": \"%s\", "
                        + "\"waypoints\": %d },\n"
                        + "  \"env\": { \"javaVersion\": \"%s\", \"os\": \"%s\", "
                        + "\"arch\": \"%s\", \"gpuLabel\": \"%s\" }\n"
                        + "}\n",
                escape(TAG),
                measuredFrames, WARMUP_FRAMES,
                valid ? "true" : "false",
                reasons,
                empty ? 0.0 : percentile(sorted, 50),
                empty ? 0.0 : percentile(sorted, 90),
                empty ? 0.0 : percentile(sorted, 95),
                empty ? 0.0 : percentile(sorted, 99),
                empty ? 0.0 : percentile(sorted, 99.9),
                empty ? 0.0 : sorted[0],
                empty ? 0.0 : sorted[sorted.length - 1],
                empty ? 0.0 : mean(sorted),
                empty ? 0.0 : percentile(sortedCopyOf(renderMs, measuredFrames), 50),
                empty ? 0.0 : percentile(sortedCopyOf(renderMs, measuredFrames), 95),
                empty ? 0.0 : percentile(sortedCopyOf(renderMs, measuredFrames), 99),
                empty ? 0.0 : mean(renderMs, measuredFrames),
                DEGENERATE_FRAME_FLOOR_MS, degenerateFrames, degenerateRatio,
                veryLongFrames,
                escape(CameraPathDriver.activeName() == null ? "" : CameraPathDriver.activeName()),
                CameraPathDriver.modeName(),
                CameraPathDriver.waypointCount(),
                escape(System.getProperty("java.version", "")),
                escape(System.getProperty("os.name", "") + " " + System.getProperty("os.version", "")),
                escape(System.getProperty("os.arch", "")),
                escape(System.getenv().getOrDefault("ORION_BENCH_GPU_LABEL", "")));
    }

    private static void appendReason(final StringBuilder builder, final String reason) {
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append('"').append(escape(reason)).append('"');
    }

    private static double[] sortedCopy(final int count) {
        return sortedCopyOf(intervalsMs, count);
    }

    private static double[] sortedCopyOf(final double[] source, final int count) {
        final double[] copy = Arrays.copyOf(source, count);
        Arrays.sort(copy);
        return copy;
    }

    /** 线性插值分位数（与 numpy 默认口径一致，便于外部脚本复算）。 */
    public static double percentile(final double[] sorted, final double percent) {
        if (sorted.length == 0) {
            return 0.0;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        final double rank = (percent / 100.0) * (sorted.length - 1);
        final int low = (int) Math.floor(rank);
        final int high = Math.min(sorted.length - 1, low + 1);
        final double weight = rank - low;
        return sorted[low] * (1.0 - weight) + sorted[high] * weight;
    }

    private static double mean(final double[] values) {
        return mean(values, values.length);
    }

    private static double mean(final double[] values, final int count) {
        if (count == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += values[i];
        }
        return sum / count;
    }

    private static String escape(final String raw) {
        return raw.replace("\\", "/").replace("\"", "'");
    }
}
