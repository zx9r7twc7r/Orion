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

    

    @Unique
    private boolean reflex$leftButtonWasDown;

    

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
        FrameBudget.tickHead();

        long jniHeadNanos = System.nanoTime();
        NativeVulkan.beginStreamlineFrame();
        FrameBudget.jniTickHead(0, System.nanoTime() - jniHeadNanos);

        jniHeadNanos = System.nanoTime();
        NativeVulkan.markSimulationStart();
        FrameBudget.jniTickHead(1, System.nanoTime() - jniHeadNanos);

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
        FrameBudget.beforeRenderFrame();

        NativeVulkan.markSimulationEnd();
    }

    

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
