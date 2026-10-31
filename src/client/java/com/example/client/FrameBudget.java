package com.example.client;

/**
 * 2026-10-13：帧预算二分（纯 Java、只读时钟，不调用任何原生接口、不改渲染行为）。
 *
 * <p><b>为什么需要它。</b>至今每一局都出现同一个盲区：`[PERF L10 CPU]` 的帧间隔是
 * 24~93ms，而同一窗口里 GPU 只有 8~15ms、我们自己的 C++（`recordFrameCommand`，
 * 即 `[PERF L10 CPUPHASE]` 的口径）只有 1~12ms、我们计过时的文字分项只有 2~2.5ms。
 * 也就是说**几十毫秒既不在 GPU、也不在任何我们计过时的东西里**，而日志里没有任何
 * 数字能指出它落在哪。这个类就补这一个洞，把帧间隔切成两段：</p>
 *
 * <pre>
 *   avgIntervalMs = 相邻两次 GameRenderer.render() 的间隔 = 整帧周期（与
 *                   [PERF L10 CPU] 的 avgMs 同口径，可互相校验）
 *   avgRenderMs   = GameRenderer.render() 自身的墙钟耗时（世界 + 手 + GUI +
 *                   我们注入在里面的 RT 录制与 Java 钩子）
 *   avgOutsideMs  = avgIntervalMs - avgRenderMs
 *                 = **不在 render 里**的部分：客户端 tick、GC、呈现/帧生成排队、
 *                   以及任何等待
 * </pre>
 *
 * <p>读法：</p>
 * <ul>
 *   <li>{@code avgOutsideMs} 大 ⇒ 卡的是 tick/GC/呈现，不是渲染路径；</li>
 *   <li>{@code avgRenderMs} 几乎等于 {@code avgIntervalMs} ⇒ 卡在渲染路径里。
 *       此时再拿 {@code [PERF L10 CPUPHASE]} 的 total 相减：
 *       差额 = 原版的渲染成本（城市图里怀疑就是招牌文字那条 Text 路径，
 *       代码注释记过原版 profiler 报 Text 占整帧 85.6%）。</li>
 * </ul>
 *
 * <p>挂在 {@code GameRendererMixin} 的 render() HEAD/RETURN 上，60 帧一行。</p>
 *
 * <p><b>2026-10-14 第二段：把 avgOutsideMs 再切成三份。</b>实测 {@code avgOutsideMs}
 * 高达 22.4ms（占整帧 92%），而 {@code render()} 自身只有 2ms。这一段落在
 * {@code Minecraft.runTick()} 里、{@code render()} 之外，但原仪表分不出是"模拟/tick"
 * 还是"呈现/交接"还是原版渲染。于是沿 runTick 再插两个时钟（{@code MinecraftReflexMixin}
 * 本来就占着 runTick 的 HEAD 与"renderFrame 之前"，这里补 RETURN）：</p>
 *
 * <pre>
 *   ┌ runTick ──────────────────────────────────────────────┐
 *   │ tickHead → beforeRenderFrame    = avgSimMs   （模拟/tick、区块提交、光线上传等） │
 *   │ beforeRenderFrame → renderHead                                       │
 *   │ renderHead → renderTail         = avgRenderMs（GameRenderer.render 自身）      │
 *   │ renderTail → tickTail           = avgFrameTailMs（renderFrame 的其余：呈现/acquire…）│
 *   └───────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>读法：三段之和应与 {@code avgIntervalMs} 同口径（差值 = 帧与帧之间的空档）。
 * 哪一段最大，优化就打那里 —— 这一条是为"整体优化"定靶用的，不改任何行为。</p>
 */
public final class FrameBudget {

    private static final int WINDOW = 60;

    private static long lastHeadNanos;
    private static double lastRenderMs;

    private static long tickHeadNanos;
    private static long beforeRenderFrameNanos;
    private static double lastSimMs;
    private static double lastFrameTailMs;

    private static int frames;
    private static double sumIntervalMs;
    private static double sumRenderMs;
    private static double sumOutsideMs;
    private static double sumSimMs;
    private static double sumFrameTailMs;
    private static double sumAcquireMs;
    private static double sumPresentMs;

    private static long renderTailNanos;
    private static long afterRenderFrameNanos;
    private static double lastRenderFrameInnerMs;
    private static double lastPostRenderFrameMs;
    private static double sumRenderFrameInnerMs;
    private static double sumPostRenderFrameMs;

    private static long submitStartNanos;
    private static double frameSubmitMs;
    private static int frameSubmitCount;
    private static double lastSubmitMs;
    private static int lastSubmitCount;
    private static double sumSubmitMs;
    private static int sumSubmitCount;

    private static double sumVanillaTickMs;
    private static double maxRenderMs;
    private static double maxSimMs;

    private static long acquireStartNanos;
    private static long presentStartNanos;
    private static double frameAcquireMs;
    private static double framePresentMs;
    private static double lastAcquireMs;
    private static double lastPresentMs;

    private static long vanillaTickStartNanos;
    private static double frameVanillaTickMs;
    private static double lastVanillaTickMs;

    private static volatile Thread mainThread;
    private static long taskPumpStartNanos;
    private static long taskRunStartNanos;
    private static double frameTaskPumpMs;
    private static double frameTaskRunMs;
    private static int frameTaskCount;
    private static double frameTaskMaxMs;
    private static double lastTaskPumpMs;
    private static double lastTaskRunMs;
    private static int lastTaskCount;
    private static double lastTaskMaxMs;
    private static double sumTaskPumpMs;
    private static double sumTaskRunMs;
    private static int sumTaskCount;
    private static double maxTaskMaxMs;

    private static final int JNI_HEAD_SLOTS = 3;
    private static final double[] frameJniHeadMs = new double[JNI_HEAD_SLOTS];
    private static final double[] lastJniHeadMs = new double[JNI_HEAD_SLOTS];
    private static final double[] sumJniHeadMs = new double[JNI_HEAD_SLOTS];
    private static final double[] maxJniHeadMs = new double[JNI_HEAD_SLOTS];

    private static long lastTickTailNanos;
    private static double lastGapMs;
    private static double sumGapMs;

    private static long prevGcTimeMs;
    private static long prevGcCount;

    private static final int SIM_SUB_SLOTS = 4;
    private static final double[] frameSimSubMs = new double[SIM_SUB_SLOTS];
    private static final double[] lastSimSubMs = new double[SIM_SUB_SLOTS];
    private static final double[] sumSimSubMs = new double[SIM_SUB_SLOTS];

    /** tickHead 内单个 JNI 调用的耗时采样（slot 见上方说明）。 */
    public static void jniTickHead(int slot, long nanos) {
        if (slot < 0 || slot >= JNI_HEAD_SLOTS) {
            return;
        }
        frameJniHeadMs[slot] += nanos / 1_000_000.0;
    }

    private static final long[] simSubStartNanos = new long[SIM_SUB_SLOTS];

    /** 是否处于"主线程 + 本帧 runTick 段内"——四路子项只在这个窗口里累计。 */
    public static boolean isSimSubActive() {
        return tickHeadNanos != 0L && onMainThread();
    }

    public static void markSimSubStart(int slot) {
        if (slot < 0 || slot >= SIM_SUB_SLOTS) {
            return;
        }
        simSubStartNanos[slot] = System.nanoTime();
    }

    public static void endSimSub(int slot) {
        if (slot < 0 || slot >= SIM_SUB_SLOTS) {
            return;
        }
        if (simSubStartNanos[slot] != 0L) {
            frameSimSubMs[slot] +=
                    (System.nanoTime() - simSubStartNanos[slot]) / 1_000_000.0;
            simSubStartNanos[slot] = 0L;
        }
    }

    private FrameBudget() {}

    private static boolean onMainThread() {
        return mainThread != null && Thread.currentThread() == mainThread;
    }

    /** Minecraft.runTick() 入口（MinecraftReflexMixin @At HEAD）。 */
    public static void tickHead() {
        mainThread = Thread.currentThread();
        tickHeadNanos = System.nanoTime();
        if (lastTickTailNanos != 0L) {
            lastGapMs = (tickHeadNanos - lastTickTailNanos) / 1_000_000.0;
        } else {
            lastGapMs = 0.0;
        }
        beforeRenderFrameNanos = 0L;
        afterRenderFrameNanos = 0L;
        renderTailNanos = 0L;
        frameAcquireMs = 0.0;
        framePresentMs = 0.0;
        frameSubmitMs = 0.0;
        frameSubmitCount = 0;
        frameVanillaTickMs = 0.0;
        frameTaskPumpMs = 0.0;
        frameTaskRunMs = 0.0;
        frameTaskCount = 0;
        frameTaskMaxMs = 0.0;
        java.util.Arrays.fill(frameJniHeadMs, 0.0);
        java.util.Arrays.fill(frameSimSubMs, 0.0);
    }

    /** 原版 Minecraft.tick() 开始（MinecraftReflexMixin @At HEAD）。 */
    public static void vanillaTickHead() {
        vanillaTickStartNanos = System.nanoTime();
    }

    /** 原版 Minecraft.tick() 结束（@At RETURN）。 */
    public static void vanillaTickTail() {
        if (vanillaTickStartNanos != 0L) {
            frameVanillaTickMs +=
                    (System.nanoTime() - vanillaTickStartNanos) / 1_000_000.0;
            vanillaTickStartNanos = 0L;
        }
    }

    /** 交换链获取开始（VulkanGpuSurface.acquireNextTexture @At HEAD）。 */
    public static void acquireHead() {
        acquireStartNanos = System.nanoTime();
    }

    /** 交换链获取结束（@At RETURN）。 */
    public static void acquireTail() {
        if (acquireStartNanos != 0L) {
            frameAcquireMs += (System.nanoTime() - acquireStartNanos) / 1_000_000.0;
            acquireStartNanos = 0L;
        }
    }

    /** 呈现开始（VulkanGpuSurface.present @At HEAD）。 */
    public static void presentHead() {
        presentStartNanos = System.nanoTime();
    }

    /** 呈现结束（@At RETURN）。 */
    public static void presentTail() {
        if (presentStartNanos != 0L) {
            framePresentMs += (System.nanoTime() - presentStartNanos) / 1_000_000.0;
            presentStartNanos = 0L;
        }
    }

    /**
     * 主线程任务泵整段开始（BlockableEventLoop.runAllTasks @At HEAD）。
     * 只统计主线程那次：worker 循环的同名方法被主线程门卫挡掉。
     */
    public static void taskPumpHead() {
        if (onMainThread()) {
            taskPumpStartNanos = System.nanoTime();
        }
    }

    /** 任务泵整段结束（runAllTasks @At RETURN）。 */
    public static void taskPumpTail() {
        if (onMainThread() && taskPumpStartNanos != 0L) {
            frameTaskPumpMs +=
                    (System.nanoTime() - taskPumpStartNanos) / 1_000_000.0;
            taskPumpStartNanos = 0L;
        }
    }

    /** 单个任务开始（BlockableEventLoop.doRunTask @At HEAD）。 */
    public static void taskRunHead() {
        if (onMainThread()) {
            taskRunStartNanos = System.nanoTime();
        }
    }

    /** 单个任务结束（doRunTask @At RETURN）：次数/合计/单任务最大值。 */
    public static void taskRunTail() {
        if (onMainThread() && taskRunStartNanos != 0L) {
            final double ms =
                    (System.nanoTime() - taskRunStartNanos) / 1_000_000.0;
            frameTaskRunMs += ms;
            ++frameTaskCount;
            if (ms > frameTaskMaxMs) {
                frameTaskMaxMs = ms;
            }
            taskRunStartNanos = 0L;
        }
    }

    /**
     * runTick() 内、调用 renderFrame() 之前那一刻（MinecraftReflexMixin 的 INVOKE 点）。
     * 模拟段到这里结束。
     */
    public static void beforeRenderFrame() {
        if (tickHeadNanos == 0L) {
            return;
        }

        beforeRenderFrameNanos = System.nanoTime();
        lastSimMs = (beforeRenderFrameNanos - tickHeadNanos) / 1_000_000.0;
    }

    /**
     * Minecraft.runTick() 出口。本帧的 renderFrame 剩余（呈现/acquire/HUD 等）
     * = runTick 总时长 − 模拟段 − render() 自身。
     */
    public static void tickTail() {
        if (tickHeadNanos == 0L || beforeRenderFrameNanos == 0L) {
            tickHeadNanos = 0L;
            return;
        }

        final long tickTailNanos = System.nanoTime();
        final double tickMs = (tickTailNanos - tickHeadNanos) / 1_000_000.0;
        lastFrameTailMs = tickMs - lastSimMs - lastRenderMs;

        if (afterRenderFrameNanos != 0L) {
            lastPostRenderFrameMs = (tickTailNanos - afterRenderFrameNanos) / 1_000_000.0;
        } else {
            lastPostRenderFrameMs = 0.0;
            lastRenderFrameInnerMs = 0.0;
        }

        lastAcquireMs = frameAcquireMs;
        lastPresentMs = framePresentMs;
        lastSubmitMs = frameSubmitMs;
        lastSubmitCount = frameSubmitCount;
        lastVanillaTickMs = frameVanillaTickMs;
        lastTaskPumpMs = frameTaskPumpMs;
        lastTaskRunMs = frameTaskRunMs;
        lastTaskCount = frameTaskCount;
        lastTaskMaxMs = frameTaskMaxMs;
        System.arraycopy(frameJniHeadMs, 0, lastJniHeadMs, 0, JNI_HEAD_SLOTS);
        System.arraycopy(frameSimSubMs, 0, lastSimSubMs, 0, SIM_SUB_SLOTS);

        lastTickTailNanos = tickTailNanos;

        tickHeadNanos = 0L;
        beforeRenderFrameNanos = 0L;
    }

    /** GameRenderer.render() 入口：收尾上一帧，开这一帧。 */
    public static void head() {
        final long now = System.nanoTime();

        if (lastHeadNanos != 0L) {
            final double intervalMs = (now - lastHeadNanos) / 1_000_000.0;

            sumIntervalMs += intervalMs;
            sumRenderMs += lastRenderMs;
            sumOutsideMs += intervalMs - lastRenderMs;

            sumSimMs += lastSimMs;
            sumFrameTailMs += lastFrameTailMs;
            sumGapMs += lastGapMs;
            sumAcquireMs += lastAcquireMs;
            sumPresentMs += lastPresentMs;
            sumRenderFrameInnerMs += lastRenderFrameInnerMs;
            sumPostRenderFrameMs += lastPostRenderFrameMs;
            sumSubmitMs += lastSubmitMs;
            sumSubmitCount += lastSubmitCount;
            sumVanillaTickMs += lastVanillaTickMs;
            sumTaskPumpMs += lastTaskPumpMs;
            sumTaskRunMs += lastTaskRunMs;
            sumTaskCount += lastTaskCount;
            if (lastTaskMaxMs > maxTaskMaxMs) {
                maxTaskMaxMs = lastTaskMaxMs;
            }
            for (int slot = 0; slot < JNI_HEAD_SLOTS; ++slot) {
                sumJniHeadMs[slot] += lastJniHeadMs[slot];
                if (lastJniHeadMs[slot] > maxJniHeadMs[slot]) {
                    maxJniHeadMs[slot] = lastJniHeadMs[slot];
                }
            }
            for (int slot = 0; slot < SIM_SUB_SLOTS; ++slot) {
                sumSimSubMs[slot] += lastSimSubMs[slot];
            }

            if (lastRenderMs > maxRenderMs) {
                maxRenderMs = lastRenderMs;
            }

            if (lastSimMs > maxSimMs) {
                maxSimMs = lastSimMs;
            }

            if (++frames >= WINDOW) {
                report();
            }
        }

        lastHeadNanos = now;
    }

    /** GameRenderer.render() 出口：记下本帧 render() 自身的耗时。 */
    public static void tail() {
        if (lastHeadNanos == 0L) {
            return;
        }

        final long now = System.nanoTime();
        lastRenderMs = (now - lastHeadNanos) / 1_000_000.0;
        renderTailNanos = now;
    }

    /**
     * 2026-10-22 A1：{@code Minecraft.renderFrame(boolean)} 返回的那一刻
     * （MinecraftReflexMixin 在 renderFrame 调用点 INVOKE/Shift.AFTER 注入）。
     *
     * <p>与 {@link #tail()} 的采样点差分 = {@code renderFrameInnerMs}：renderFrame 里、
     * {@code GameRenderer.render()} 之后的剩余（present/acquire 已单独量过，只占 0.21ms）。
     * 纯读时钟，不改任何行为。</p>
     */
    public static void afterRenderFrame() {
        afterRenderFrameNanos = System.nanoTime();
        if (renderTailNanos != 0L) {
            lastRenderFrameInnerMs =
                    (afterRenderFrameNanos - renderTailNanos) / 1_000_000.0;
        } else {
            lastRenderFrameInnerMs = 0.0;
        }
    }

    /**
     * 2026-10-22 A1：{@code VulkanCommandEncoder.submit} 开始。
     * 这一段是 {@code vkQueueSubmit} 与其伴随的 fence/信号量操作 ——
     * 队列满或帧生成排队时它会阻塞，是 9ms frameTail 的头号嫌疑。
     */
    public static void submitHead() {
        submitStartNanos = System.nanoTime();
    }

    /** 2026-10-22 A1：{@code submit} 结束（一帧可能多次 ⇒ 按帧累加）。 */
    public static void submitTail() {
        if (submitStartNanos == 0L) {
            return;
        }
        frameSubmitMs += (System.nanoTime() - submitStartNanos) / 1_000_000.0;
        ++frameSubmitCount;
        submitStartNanos = 0L;
    }

    private static void report() {
        final double inv = frames <= 0 ? 1.0 : (double) frames;
        final double avgInterval = sumIntervalMs / inv;
        final double avgRender = sumRenderMs / inv;
        final double avgOutside = sumOutsideMs / inv;
        final double avgSim = sumSimMs / inv;
        final double avgFrameTail = sumFrameTailMs / inv;

        System.out.println(
                "[FRAME BUDGET]"
                        + " frames=" + frames
                        + " avgIntervalMs=" + round(avgInterval)
                        + " avgRenderMs=" + round(avgRender)
                        + " avgOutsideMs=" + round(avgOutside)
                        + " maxRenderMs=" + round(maxRenderMs)
                        + " renderPct=" + round(avgInterval <= 0.0
                                ? 0.0
                                : 100.0 * avgRender / avgInterval)
                        + "  判读：Outside 大 ⇒ tick/GC/呈现；Render≈Interval ⇒ 在渲染路径里"
                        + System.lineSeparator()
                        + "                [FRAME SPLIT] simMs=" + round(avgSim)
                        + " renderFrameTailMs=" + round(avgFrameTail)
                        + " gapMs=" + round(sumGapMs / inv)
                        + " maxSimMs=" + round(maxSimMs)
                        + "  (= runTick 拆三段 + 帧间空档；"
                        + "四者相加应与 avgIntervalMs 同口径，差值 = runTick 内未钩的段)");
        System.out.println(
                "                [FRAME SURFACE] acquireMs=" + round(sumAcquireMs / inv)
                        + " presentMs=" + round(sumPresentMs / inv)
                        + "  (= frameTail 里交换链的占比；"
                        + "acquire 阻塞 ⇒ 队列满/FG 排队；present 阻塞 ⇒ 驱动/vsync)");
        System.out.println(
                "                [FRAME TAIL SPLIT] renderFrameInnerMs="
                        + round(sumRenderFrameInnerMs / inv)
                        + " postRenderFrameMs=" + round(sumPostRenderFrameMs / inv)
                        + " submitMs=" + round(sumSubmitMs / inv)
                        + " submitCount=" + sumSubmitCount
                        + "  (= frameTail 拆两段 + 我们自己的 submit 墙钟；"
                        + "submit≈frameTail ⇒ 队列等待/帧节奏（减 GPU pass 才有 fps）；"
                        + "两段都小 ⇒ 卡在原版 Java 侧)");
        System.out.println(
                "                [FRAME SIM] vanillaTickMs=" + round(sumVanillaTickMs / inv)
                        + "  (= simMs 里的原版 tick；"
                        + "simMs − vanillaTickMs = 区块结果消费 + 我们的上传/JNI)");
        System.out.println(
                "                [FRAME SIM SPLIT] packetsMs=" + round(sumSimSubMs[0] / inv)
                        + " extractMs=" + round(sumSimSubMs[1] / inv)
                        + " soundMs=" + round(sumSimSubMs[2] / inv)
                        + " mouseMs=" + round(sumSimSubMs[3] / inv)
                        + "  (= sim 段四路：主线程网络包 / 26.3 区块提取 / 声音 / 鼠标；"
                        + "sim − vanillaTick − task − JNI三槽 − 本行四项 的残差 ⇒ "
                        + "advanceGameTime/textureTick/gizmos 等小额项)");
        System.out.println(
                "                [FRAME TASKS] runAllTasksMs=" + round(sumTaskPumpMs / inv)
                        + " taskMs=" + round(sumTaskRunMs / inv)
                        + " taskCount=" + sumTaskCount
                        + " maxTaskMs=" + round(maxTaskMaxMs)
                        + "  (= simMs 里的主线程任务泵：区块编译完成后的消费/上传任务；"
                        + "taskMs 与 vanillaTickMs 可能重叠——tick 内也会 pump)");
        System.out.println(
                "                [FRAME JNI HEAD] beginStreamlineMs="
                        + round(sumJniHeadMs[0] / inv)
                        + " maxBeginStreamlineMs=" + round(maxJniHeadMs[0])
                        + " markSimMs=" + round(sumJniHeadMs[1] / inv)
                        + " menuMs=" + round(sumJniHeadMs[2] / inv)
                        + "  (= simMs 里我们自己的三个 JNI；"
                        + "beginStreamline 大 ⇒ 帧围栏/GPU 等待藏在 sim 段)");
        long gcTimeMs = 0L;
        long gcCount = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory
                        .getGarbageCollectorMXBeans()) {
            final long t = bean.getCollectionTime();
            final long c = bean.getCollectionCount();
            if (t >= 0L) {
                gcTimeMs += t;
            }
            if (c >= 0L) {
                gcCount += c;
            }
        }
        final long gcMsWindow = gcTimeMs - prevGcTimeMs;
        final long gcCountWindow = gcCount - prevGcCount;
        prevGcTimeMs = gcTimeMs;
        prevGcCount = gcCount;
        System.out.println(
                "                [FRAME GAP/GC] gcMs=" + gcMsWindow
                        + " gcCount=" + gcCountWindow
                        + "  (= 本窗口 GC 墙钟耗时/次数的 MXBean 差分；"
                        + "gapMs 见 FRAME SPLIT 行，interval−sim−render−frameTail−gap "
                        + "的残差 ⇒ runTick 内未钩的段)");

        sumVanillaTickMs = 0.0;
        sumTaskPumpMs = 0.0;
        sumTaskRunMs = 0.0;
        sumTaskCount = 0;
        maxTaskMaxMs = 0.0;
        java.util.Arrays.fill(sumJniHeadMs, 0.0);
        java.util.Arrays.fill(maxJniHeadMs, 0.0);
        java.util.Arrays.fill(sumSimSubMs, 0.0);

        frames = 0;
        sumIntervalMs = 0.0;
        sumRenderMs = 0.0;
        sumOutsideMs = 0.0;
        sumSimMs = 0.0;
        sumFrameTailMs = 0.0;
        sumGapMs = 0.0;
        sumAcquireMs = 0.0;
        sumPresentMs = 0.0;
        sumRenderFrameInnerMs = 0.0;
        sumPostRenderFrameMs = 0.0;
        sumSubmitMs = 0.0;
        sumSubmitCount = 0;
        maxRenderMs = 0.0;
        maxSimMs = 0.0;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
