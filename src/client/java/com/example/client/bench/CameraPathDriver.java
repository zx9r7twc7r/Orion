package com.example.client.bench;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-24：相机路径的录制与回放（每帧一次调用，不碰任何渲染状态）。
 *
 * <p><b>接缝。</b>唯一权威的相机入口是 {@code VulkanCameraMixin} 在
 * {@code GameRenderer.renderLevel} HEAD 里取到的 {@code cameraRenderState.pos} 与
 * {@code cameraState.viewRotationMatrix}，紧接着就把它们写进 native（{@code updateCamera}）。
 * 本类就在这两步之间被调用：</p>
 *
 * <ul>
 *   <li><b>录制</b>：把本帧 native 实际会收到的位姿抄一份进航点表（同一坐标系、同一时刻，
 *       因此录下来的路径与当时画面严格对应）；</li>
 *   <li><b>回放</b>：返回一个替换位姿，{@code VulkanCameraMixin} 用它替换 {@code pos} 与
 *       {@code viewRotationMatrix}，于是 native 看到的相机完全由路径决定，而**玩家本体、
 *       物理、区块加载都不受影响**（回放不做碰撞也不挪玩家，这一点与"录制时走过的路"
 *       在旁观者视角下是一一对应的）。</li>
 * </ul>
 *
 * <p><b>确定性。</b>回放不用墙钟，用"渲染帧计数 × 固定步长"（默认取路径文件里的
 * {@code fixedStepHz}，通常 60）：同一台机器上跑两次，第 N 帧的相机位姿逐位相同。
 * 这正是参考实现（rtx-remix-minecraft 的 {@code FixedStepClock}）的做法：墙钟会让
 * 一次掉帧把整段路径推后，截图与帧号就对不上了。</p>
 *
 * <p><b>开关（Java 系统属性，不是 {@code ORION_*} 环境变量）。</b>
 * 见 {@code docs/benchmarking.md}：{@code orion.campath.record}、{@code orion.campath.play}、
 * {@code orion.campath.loop}、{@code orion.campath.record.frames}、
 * {@code orion.campath.fixedStepHz}、{@code orion.campath.world}。</p>
 */
public final class CameraPathDriver {

    /** 回放位姿。角度单位是度，与 {@code Entity#getYRot()} 一致。 */
    public record Pose(double x, double y, double z, float yaw, float pitch) {

        /**
         * 由位姿构造视图旋转矩阵。
         *
         * <p>必须与 {@code net.minecraft.client.Camera} 的约定逐字一致：
         * {@code rotation.rotationYXZ((float) Math.PI - yRot * DEG2RAD, -xRot * DEG2RAD, 0)}。
         * 少一个 {@code PI} 会让回放画面朝向反 180°，符号写反则是俯仰翻转 —— 两者都
         * 不会崩溃，只会让基准截图"看起来对但永远对不上"，所以这里只抄这一个公式。</p>
         */
        public Matrix4f viewRotationMatrix() {
            final float toRadians = (float) (Math.PI / 180.0);
            return new Matrix4f().rotationYXZ(
                    (float) Math.PI - this.yaw * toRadians,
                    -this.pitch * toRadians,
                    0.0f);
        }
    }

    private enum Mode {
        OFF,
        RECORD,
        PLAY
    }

    private static final boolean DIAGNOSTIC =
            Boolean.getBoolean("orion.campath.diag");

    private static Mode mode = Mode.OFF;
    private static boolean installed;
    private static String requestedName;
    private static String activeName;

    private static String worldKey;
    private static Path file;
    private static CameraPath path;

    // 录制状态
    private static final List<CameraPath.Waypoint> recorded = new ArrayList<>();
    private static long lastRecordNanos;
    private static double recordedSeconds;
    private static int recordLimitFrames;
    private static boolean recordSaved;

    // 回放状态
    private static boolean loop;
    private static int fixedStepHz = 60;
    private static long playFrame;
    private static int startDelayFrames;
    private static boolean playFinished;
    private static int logCountdown;

    private CameraPathDriver() {
    }

    /**
     * 客户端初始化时调用一次：读开关、决定本次会话是录制还是回放。
     * 与世界的绑定延后到第一帧（那时才知道维度与世界名）。
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        final String play = System.getProperty("orion.campath.play");
        final String record = System.getProperty("orion.campath.record");
        if (play != null && !play.isBlank() && record != null && !record.isBlank()) {
            System.out.println("[ORION CAMPATH] both orion.campath.play and "
                    + "orion.campath.record are set; refusing to run (record wins? no)."
                    + " pick one.");
            return;
        }
        if (record != null && !record.isBlank()) {
            mode = Mode.RECORD;
            requestedName = CameraPathStore.sanitize(record);
            recordLimitFrames = readInt("orion.campath.record.frames", 0);
            System.out.println("[ORION CAMPATH] record armed name=" + requestedName
                    + " limitFrames=" + (recordLimitFrames == 0 ? "unbounded" : recordLimitFrames));
            Runtime.getRuntime().addShutdownHook(new Thread(
                    CameraPathDriver::stopRecording, "orion-campath-save"));
            return;
        }
        if (play != null && !play.isBlank()) {
            mode = Mode.PLAY;
            requestedName = CameraPathStore.sanitize(play);
            loop = Boolean.parseBoolean(System.getProperty("orion.campath.loop", "false"));
            fixedStepHz = readInt("orion.campath.fixedStepHz", 0);
            startDelayFrames = readInt("orion.campath.startDelayFrames", 0);
            System.out.println("[ORION CAMPATH] playback armed name=" + requestedName
                    + " loop=" + loop
                    + " fixedStepHz=" + (fixedStepHz == 0 ? "from-file" : fixedStepHz)
                    + " startDelayFrames=" + startDelayFrames);
        }
    }

    private static int readInt(final String key, final int fallback) {
        final String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (final NumberFormatException failure) {
            System.out.println("[ORION CAMPATH] " + key + "=\"" + raw
                    + "\" is not an integer; using " + fallback);
            return fallback;
        }
    }

    /**
     * 每帧调用（{@code GameRenderer.renderLevel} HEAD，相机已经算好、native 还没收到）。
     *
     * @return 回放位姿；{@code null} 表示本帧用真实相机。
     */
    public static synchronized Pose frame(final Vec3 cameraPos, final float cameraXRot,
                                          final float cameraYRot) {
        if (mode == Mode.OFF) {
            return null;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        if (file == null) {
            bind(minecraft);
            if (file == null) {
                return null;
            }
        }
        return mode == Mode.RECORD
                ? recordFrame(cameraPos, cameraXRot, cameraYRot)
                : playOneFrame();
    }

    /** 第一帧进世界时确定本局的路径文件（维度 + 世界名）。 */
    private static void bind(final Minecraft minecraft) {
        worldKey = CameraPathStore.worldKey(minecraft);
        file = CameraPathStore.file(minecraft, worldKey, requestedName);
        if (file == null) {
            return;
        }
        final String where = CameraPathStore.describe(minecraft, file);
        if (mode == Mode.RECORD) {
            CameraPathStore.ensureReadme(minecraft, worldKey);
            System.out.println("[ORION CAMPATH] recording to " + where);
            lastRecordNanos = System.nanoTime();
            return;
        }
        try {
            path = CameraPathStore.load(file);
            if (fixedStepHz <= 0) {
                fixedStepHz = Math.max(1, path.fixedStepHz());
            }
            System.out.println("[ORION CAMPATH] loaded " + where
                    + " waypoints=" + path.size()
                    + " duration=" + String.format(java.util.Locale.ROOT, "%.3f", path.duration())
                    + "s step=" + fixedStepHz + "Hz"
                    + (loop ? " loop=on" : ""));
        } catch (final IOException | RuntimeException failure) {
            System.out.println("[ORION CAMPATH] failed to load " + where + ": "
                    + failure.getMessage() + " -- playback disabled");
            mode = Mode.OFF;
            file = null;
        }
    }

    private static Pose recordFrame(final Vec3 cameraPos, final float cameraXRot,
                                    final float cameraYRot) {
        final long now = System.nanoTime();
        double deltaSeconds = (now - lastRecordNanos) / 1.0e9;
        lastRecordNanos = now;
        if (deltaSeconds < CameraPath.MIN_STEP_SECONDS) {
            return null;
        }
        if (deltaSeconds > CameraPath.MAX_STEP_SECONDS) {
            deltaSeconds = CameraPath.MAX_STEP_SECONDS;
        }
        recordedSeconds += deltaSeconds;
        // 存原始角度；CameraPath.of() 统一展开，避免这里和读取端两套逻辑。
        recorded.add(new CameraPath.Waypoint(recordedSeconds,
                new CameraPath.Pose(cameraPos.x, cameraPos.y, cameraPos.z,
                        cameraYRot, cameraXRot)));
        if (recordLimitFrames > 0 && recorded.size() >= recordLimitFrames) {
            stopRecording();
        }
        return null;
    }

    private static Pose playOneFrame() {
        if (path == null || playFinished) {
            return null;
        }
        if (playFrame < startDelayFrames) {
            playFrame++;
            return null;
        }
        final double seconds = (playFrame - startDelayFrames) / (double) fixedStepHz;
        playFrame++;
        if (seconds >= path.duration()) {
            if (loop) {
                // 回到起点：帧号重置，保持"第 N 帧"与位姿的固定关系。
                playFrame = startDelayFrames;
                if (DIAGNOSTIC) {
                    System.out.println("[ORION CAMPATH] loop restart");
                }
                final CameraPath.Pose restart = path.sample(0.0);
                return new Pose(restart.x(), restart.y(), restart.z(),
                        restart.yaw(), restart.pitch());
            }
            if (!playFinished) {
                playFinished = true;
                final String where = CameraPathStore.describe(
                        Minecraft.getInstance(), file);
                System.out.println("[ORION CAMPATH] playback finished: " + where
                        + " frames=" + (playFrame - startDelayFrames));
                // 结束后继续用真实相机（不回弹），这样最后一帧的画面不会被破坏。
            }
            return null;
        }
        if (DIAGNOSTIC && --logCountdown <= 0) {
            logCountdown = 60;
            final CameraPath.Pose pose = path.sample(seconds);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[ORION CAMPATH] frame=%d t=%.3f pos=(%.3f,%.3f,%.3f) yaw=%.2f pitch=%.2f",
                    playFrame, seconds, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch()));
        }
        final CameraPath.Pose sampled = path.sample(seconds);
        return new Pose(sampled.x(), sampled.y(), sampled.z(),
                sampled.yaw(), sampled.pitch());
    }

    /** 停止录制并落盘（幂等；关机钩子与帧数上限都会走到这里）。 */
    public static synchronized void stopRecording() {
        if (mode != Mode.RECORD || recordSaved) {
            return;
        }
        recordSaved = true;
        if (recorded.isEmpty() || file == null) {
            System.out.println("[ORION CAMPATH] record stopped with no waypoints; nothing written");
            return;
        }
        try {
            final CameraPath built = CameraPath.of(requestedName, 60,
                    System.currentTimeMillis() / 1000L, recorded);
            CameraPathStore.save(file, built);
            activeName = requestedName;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[ORION CAMPATH] saved %s waypoints=%d duration=%.3fs",
                    CameraPathStore.describe(Minecraft.getInstance(), file),
                    built.size(), built.duration()));
        } catch (final IOException | RuntimeException failure) {
            System.out.println("[ORION CAMPATH] save failed: " + failure);
        }
    }

    public static synchronized boolean isActive() {
        return mode != Mode.OFF;
    }

    /** 状态名（{@code OFF}/{@code RECORD}/{@code PLAY}），给日志与状态文件用。 */
    public static synchronized String modeName() {
        return mode.name();
    }

    /** 已录/已载入的航点数，给基准状态文件用。 */
    public static synchronized int waypointCount() {
        return mode == Mode.RECORD ? recorded.size() : (path == null ? 0 : path.size());
    }

    /** 当前回放帧号（未回放时为 0）。 */
    public static synchronized long playbackFrame() {
        return playFrame;
    }

    static synchronized String activeName() {
        return activeName != null ? activeName : requestedName;
    }
}
