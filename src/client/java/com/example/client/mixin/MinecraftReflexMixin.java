package com.example.client.mixin;

import com.example.client.FrameBudget;
import com.example.vulkan.NativeVulkan;
import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;


@Mixin(Minecraft.class)
public abstract class MinecraftReflexMixin {

    /**
     * 2026-10-22（D2）：左键上一帧是否按下，用于取"按下沿"。
     * 官方 Reflex 要求 eTriggerFlash 在**每次左键按下**时发一次，不是每帧发 ——
     * 每帧发会让驱动侧的 RFI 指示器一直亮，反而失去"看输入延迟"的意义。
     */
    @Unique
    private boolean reflex$leftButtonWasDown;

    /**
     * GLFW 访问器。用反射而不是直接 import：本模组的客户端编译类路径里没有
     * {@code org.lwjgl.glfw}（运行时才有），直接引用编译不过。三个符号只在
     * 类初始化时解析一次，之后每帧只是两次 Method.invoke。
     */
    @Unique
    private static Method reflex$glfwGetCurrentContext;

    @Unique
    private static Method reflex$glfwGetMouseButton;

    @Unique
    private static int reflex$glfwMouseButtonLeft = -1;

    @Unique
    private static int reflex$glfwPress = -1;

    @Unique
    private static boolean reflex$glfwResolved;

    /**
     * 延迟解析 GLFW 符号。**不能写成 static 初始化块**：Mixin 不允许 mixin 类
     * 带 {@code <clinit>}（应用期会报错）。所以放在这里、由每帧调用点进入
     * 一次后就短路。
     */
    @Unique
    private static void reflex$resolveGlfw() {
        if(reflex$glfwResolved) {
            return;
        }

        reflex$glfwResolved = true;

        try {
            final Class<?> glfw =
                    Class.forName("org.lwjgl.glfw.GLFW");

            reflex$glfwGetCurrentContext =
                    glfw.getMethod("glfwGetCurrentContext");

            reflex$glfwGetMouseButton =
                    glfw.getMethod(
                            "glfwGetMouseButton",
                            long.class,
                            int.class
                    );

            reflex$glfwMouseButtonLeft =
                    glfw.getField("GLFW_MOUSE_BUTTON_LEFT")
                            .getInt(null);

            reflex$glfwPress =
                    glfw.getField("GLFW_PRESS")
                            .getInt(null);
        } catch(Throwable ignored) {
            // 拿不到就整条关掉（RFI 只是诊断功能，不影响渲染）。
            reflex$glfwGetCurrentContext = null;
            reflex$glfwGetMouseButton = null;
        }
    }

    @Inject(
            method = "runTick",
            at = @At("HEAD")
    )
    private void reflex$beginStreamlineFrame(
            boolean advanceGameTime,
            CallbackInfo ci
    ) {
        // 2026-10-14 帧预算第二段：runTick 入口（纯读时钟；见 FrameBudget 的类注释图）。
        FrameBudget.tickHead();

        // 2026-10-15 帧预算第六段：tickHead 里这三个 JNI 单独计时（纯读时钟）。
        // 任务泵已排除 ⇒ simMs − vanillaTickMs − taskMs 的剩余嫌疑就是它们；
        // 头号嫌疑是 beginStreamlineFrame（可能含帧围栏/GPU 等待）。
        long jniHeadNanos = System.nanoTime();
        NativeVulkan.beginStreamlineFrame();
        FrameBudget.jniTickHead(0, System.nanoTime() - jniHeadNanos);

        jniHeadNanos = System.nanoTime();
        NativeVulkan.markSimulationStart();
        FrameBudget.jniTickHead(1, System.nanoTime() - jniHeadNanos);

        // =====================================================
        // 2026-10-03 界面状态改从**真正持有界面的地方**取。
        //
        // 旧实现把这一条挂在 Minecraft.setScreenAndShow 上。26.2 把界面管理搬进了
        // net.minecraft.client.gui.Gui：绝大多数界面切换（包括关闭界面 → null）
        // 走 Gui.setScreen，而 Minecraft.setScreenAndShow 只剩 disconnect /
        // clearClientLevel 这两条"离开 / 清空世界"的路径会调用，**且都传非 null**
        // （见 Minecraft.java:2225 / 2255 / 2265）。
        //
        // 后果是致命的：这个标志只会被置 true、永远不会回到 false ⇒ fgDesiredOn
        // 恒假 ⇒ 后端 FG-4.4 把帧生成 eOff 之后**整局都不会再打开**，表现就是
        // "多帧生成不生效"。实机日志可证：一次会话里 [FG MENU] open=1 出现 4 次、
        // open=0 一次都没有。
        //
        // 改成每帧从 Minecraft.gui（public final）与 Gui.screen()（public）读真值。
        // 代价是一次 JNI 调用；C++ 侧 setFullscreenMenuOpen 在值未变时直接返回，
        // 只有真的变化才重推 Reflex 上限。
        //
        // 注意 overlay 不算：Gui.overlay() 是聊天/弹窗一类的小遮罩，不是全屏菜单。
        // =====================================================
        final Minecraft self =
                (Minecraft)(Object)this;

        jniHeadNanos = System.nanoTime();
        NativeVulkan.setFullscreenMenuOpen(
                self.gui != null && self.gui.screen() != null
        );
        FrameBudget.jniTickHead(2, System.nanoTime() - jniHeadNanos);

        reflex$pollFlashTrigger();
    }

    /**
     * 2026-10-22（D2）：左键按下沿 → Reflex Flash 指示器（RFI）marker。
     *
     * <p>为什么用 {@code glfwGetCurrentContext()} 而不是 Minecraft 的 Window：
     * 这里只需要"当前上下文那个窗口"，GLFW 自己就有，不必依赖任何 Minecraft
     * 映射名（换版本不会因为映射改名而编译不过）。它返回 0 表示当前线程没有
     * 当前上下文，此时直接跳过。
     *
     * <p>原生侧还会再校验一次 {@code ReflexState::flashIndicatorDriverControlled}
     * ——驱动没接管 RFI 时发这个 marker 没有意义（官方要求只在为真时发）。
     */
    @Unique
    private void reflex$pollFlashTrigger() {
        reflex$resolveGlfw();

        if(reflex$glfwGetCurrentContext == null ||
                reflex$glfwGetMouseButton == null) {
            return;
        }

        try {
            final long windowHandle =
                    ((Number) reflex$glfwGetCurrentContext.invoke(null))
                            .longValue();

            if(windowHandle == 0L) {
                return;
            }

            final boolean leftDown =
                    ((Number) reflex$glfwGetMouseButton.invoke(
                            null,
                            windowHandle,
                            reflex$glfwMouseButtonLeft
                    )).intValue() == reflex$glfwPress;

            if(leftDown && !this.reflex$leftButtonWasDown) {
                NativeVulkan.pclTriggerFlash();
            }

            this.reflex$leftButtonWasDown = leftDown;
        } catch(Throwable ignored) {
            // 单帧失败不改变状态：下次再试。
        }
    }

    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void reflex$endSimulation(
            boolean advanceGameTime,
            CallbackInfo ci
    ) {
        // 2026-10-14 帧预算第二段：模拟段到此结束（这一段 = tick/区块提交/资源上传等）。
        FrameBudget.beforeRenderFrame();

        NativeVulkan.markSimulationEnd();
    }

    /**
     * 2026-10-22 A1（帧预算第九段）：{@code renderFrame(boolean)} 返回的那一刻。
     *
     * <p>为什么量它：实测 {@code renderFrameTailMs} 中位数 9.22ms，而其中的
     * acquire+present 只有 0.21ms —— 剩下约 9ms 在日志里没有任何归属。这一刀把
     * frameTail 劈成"renderFrame 内（render() 之后）"与"renderFrame 之后到
     * runTick 返回"两段，配合 {@code submitMs} 就能判断这 9ms 是队列等待
     * 还是原版 Java 自己的活。纯读时钟，不改任何渲染行为。</p>
     */
    @Inject(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V",
                    shift = At.Shift.AFTER
            )
    )
    private void reflex$frameBudgetAfterRenderFrame(
            boolean advanceGameTime,
            CallbackInfo ci
    ) {
        FrameBudget.afterRenderFrame();
    }

    /**
     * 2026-10-14 帧预算第二段：runTick 出口。用来把 renderFrame() 里**不属于
     * GameRenderer.render()** 的那部分（呈现/交换链 acquire/HUD 等）单独量出来。
     * 纯读时钟，不改任何行为。
     */
    @Inject(
            method = "runTick",
            at = @At("RETURN")
    )
    private void reflex$frameBudgetTickTail(
            boolean advanceGameTime,
            CallbackInfo ci
    ) {
        FrameBudget.tickTail();
    }

    /**
     * 2026-10-14 帧预算第四段：原版客户端 tick 自身。纯读时钟。
     * 为什么量它：交换链已排除（acquire+present ≈ 0.26ms）⇒ simMs 的 7.6ms 只可能落在
     * "原版 tick / 区块结果消费 / 我们的上传与 JNI"三者之一。这一对把"原版 tick"摘出来。
     * 注意：追帧时一帧可能 tick 多次 ⇒ FrameBudget 按帧累加。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void reflex$frameBudgetVanillaTickHead(CallbackInfo ci) {
        FrameBudget.vanillaTickHead();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void reflex$frameBudgetVanillaTickTail(CallbackInfo ci) {
        FrameBudget.vanillaTickTail();
    }
}
