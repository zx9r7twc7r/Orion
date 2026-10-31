package com.example.client.compat.dh;

import com.example.vulkan.NativeVulkan;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;

import java.util.Arrays;
import java.util.Locale;

/**
 * DH stage B（远场几何 / 重打光）的 Java 侧管道：每帧把 DH 的<b>逆 MVP</b>推进 C++。
 *
 * <h2>为什么需要它</h2>
 * <p>stage-1 只是把 DH 的 color 整片贴在远场上——远场的几何与光照全是 DH 的，
 * 我们的太阳/天空/阴影完全不参与。要让远场"是 RT 的"（规划 §7.3 的
 * <b>图像定色 + 几何定光</b>），必须先把 DH 的 depth 变回世界几何：
 * {@code world = dhInverseMvmProjectionMatrix × vec4(ndc, depth, 1)}。
 * 这个矩阵由 {@link DhApiRenderParam} 直接给出，不需要自己拼 near/far/投影。</p>
 *
 * <h2>规格</h2>
 * <p>载荷是固定 24 个 float（布局见 C++ {@code updateDhFarField} 的注释）：
 * 16 = 矩阵，17..21 = mode/strength/gain/variant/near/far。每帧一次 JNI 调用，
 * 只做内存拷贝，<b>零 Vulkan 调用</b>。</p>
 *
 * <h2>为什么把模式做成 JVM 属性</h2>
 * <p>本轮四个模式（误差图 / 法线 / 重打光 / 重打光+大气）共用同一份 spv 与 DLL，
 * 换模式只需要改运行配置里的一行 {@code -D}，不必重编任何东西——这是"一次编译拿多个
 * 答案"的刻意设计，别把模式编译进常量。</p>
 *
 * <h2>矩阵的用途在 09-23 第二次定案后收窄成了"一个标量距离"</h2>
 * <p>rgen 那边把远场位置写成 {@code 相机 + 主射线 × 距离}，距离优先取逆 MVP 给的、
 * 取不到就退到 z 镜像、再退到解析 reverse-Z。所以这里不需要假设矩阵的行/列序，
 * 也不需要补偿任何东西：{@link DhApiMat4f#putValuesInArray(float[])} 原样推给 C++，
 * 由 rgen 的变体自检现场确定读法（{@link DhFarFieldSolver} 在 Java 侧独立复核一次，
 * 结论只进日志）。</p>
 *
 * <p>推送侧唯一必须做对的事是<b>挡住非有限值</b>：DH 每帧首个回调给的矩阵实测是
 * NaN，而 NaN 会让 rgen 的比较全部为假、白扔掉最精确的那一级距离。挡法是丢帧
 * （保留上一帧的好矩阵），不是推零。</p>
 */
final class DhFarFieldGeometry {
    private static final String TAG = "[DH-MAT]";

    

    private static final int PAYLOAD_LENGTH = 40;

    private static final float[] PAYLOAD = new float[PAYLOAD_LENGTH];

    

    private static final float[] MATRIX_SCRATCH = new float[16];

    

    

    static final int MODE = (int) number("orion.dh.farLight.mode", -1.0f);

    

    static final float STRENGTH = number("orion.dh.farLight.strength", 1.0f);

    static final float GAIN = number("orion.dh.farLight.gain", 1.0f);

    

    static final float VARIANT = number("orion.dh.farLight.variant", 0.0f);

    private static int pushes;
    private static int failures;
    private static int rejected;
    private static boolean configLogged;
    private static float lastLoggedNear = Float.NaN;
    private static int nearLogCount;
    private static boolean mvpLogged;

    

    private static final float NEAR_OVERRIDE = number("orion.dh.farLight.nearOverride", 0.0f);

    private static volatile boolean renderUtilResolved;
    private static volatile java.lang.reflect.Method nearClipMethod;
    private static volatile java.lang.reflect.Method farClipMethod;

    /**
     * ⭐⭐ near/far lane 的第一来源：从 {@code dhProjectionMatrix} 的 m22/m23 反解。
     *
     * <p><b>为什么这是根治"微缩模型"的正解（09-23 深夜定案）</b>：</p>
     * <p>DH 的深度图是用它自己的投影矩阵 {@code dhProjectionMatrix} 渲染的，而该矩阵
     * 由 {@code RenderUtil.setDhProjectionMatrix} 从 MC 投影矩阵复制后，只改
     * {@code m22}/{@code m23} 两个元素（{@code setClipPlanes} 只 {@code putfield m22/m23}）。
     * 这两个元素直接编码了渲染深度图那一刻的 near/far：</p>
     * <pre>
     *   REVERSE_Z 分支（depthDirection=REVERSE_Z 实测）：
     *     m22 = near / (far - near)
     *     m23 = near * far / (far - near)
     *   ⇒ far  = m23 / m22
     *   ⇒ near = m22 * far / (1 + m22)
     * </pre>
     *
     * <p>用它有两个不可替代的好处：</p>
     * <ol>
     *   <li><b>与深度编码严格一致</b>：解析式 {@code z = n·f/(n + d·(f−n))} 是 DH
     *       深度的精确逆变换，反解出的距离 1:1 正确，不存在"near lane 猜错"导致的
     *       整体缩放（= 微缩模型）。</li>
     *   <li><b>无波动</b>：旧路径反射 {@code RenderUtil.getNearClipPlaneInBlocks()}
     *       的返回值被 overdraw 占比 / 相机速度（{@code reduceOverdrawWithFastMovement}）/
     *       高度覆盖三条动态路径叠加，实测在同一会话内 28.5 ↔ 4.9 格来回跳，解析式
     *       反解出的距离随之整体缩放数倍——这正是"远场地形缩成微缩模型 + 随相机
     *       x/y/z 大幅漂移"的直接根因。矩阵元素是 DH 每帧算好的单一真值，不波动。</li>
     * </ol>
     *
     * <p>返回 {@code null} 表示矩阵元素不可用（非有限 / 反解出非法值），调用方回落到
     * 反射路径。</p>
     */
    private static float[] resolveClipFromProjection(DhApiRenderParam param) {
        if (param == null || param.dhProjectionMatrix == null) {
            return null;
        }
        float m22 = param.dhProjectionMatrix.m22;
        float m23 = param.dhProjectionMatrix.m23;

        if (!Float.isFinite(m22) || !Float.isFinite(m23) || m22 == 0.0f) {
            return null;
        }

        float far = m23 / m22;
        float near = m22 * far / (1.0f + m22);

        if (!Float.isFinite(near) || !Float.isFinite(far)
                || near <= 0.0f || far <= 0.0f
                || far <= near) {
            return null;
        }

        return new float[]{near, far};
    }

    /**
     * near lane（方块）。优先级：
     * <ol>
     *   <li>{@code -Dorion.dh.farLight.nearOverride}（&gt;0 强制）；</li>
     *   <li><b>从 {@code dhProjectionMatrix.m22/m23} 反解</b>（与深度编码严格一致、无波动，
     *       根治微缩模型，见 {@link #resolveClipFromProjection}）；</li>
     *   <li>反射 {@code RenderUtil.getNearClipPlaneInBlocks()}（兜底）；</li>
     *   <li>退回 {@code nearClipPlane × 视距格数}（占比换算）；</li>
     *   <li>仍无效返回 0，调用方会把 far lane 一并置 0（rgen 判 lane 无效，
     *       走矩阵路径 = 09-23 早晨的行为）。</li>
     * </ol>
     */
    private static float resolveNearClipBlocks(DhApiRenderParam param) {
        if (NEAR_OVERRIDE > 0.0f) {
            return NEAR_OVERRIDE;
        }
        float[] clip = resolveClipFromProjection(param);
        if (clip != null && clip[0] > 0.0f) {
            return clip[0];
        }
        resolveRenderUtilOnce();
        java.lang.reflect.Method method = nearClipMethod;
        if (method != null) {
            try {
                float value = (Float) method.invoke(null);
                if (Float.isFinite(value) && value >= 1.0f) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            int renderDistance = net.minecraft.client.Minecraft.getInstance()
                    .options.renderDistance().get();
            float blocks = param.nearClipPlane * (renderDistance * 16.0f);
            if (Float.isFinite(blocks) && blocks >= 1.0f) {
                return blocks;
            }
        } catch (Throwable ignored) {
        }
        return 0.0f;
    }

    /** far lane（方块）。优先从投影矩阵反解，其次 DH 自己的 {@code getFarClipPlaneDistanceInBlocks()}。 */
    private static float resolveFarClipBlocks(DhApiRenderParam param) {
        float[] clip = resolveClipFromProjection(param);
        if (clip != null && clip[1] > 1.0f) {
            return clip[1];
        }
        resolveRenderUtilOnce();
        java.lang.reflect.Method method = farClipMethod;
        if (method != null) {
            try {
                float value = (Float) method.invoke(null);
                if (Float.isFinite(value) && value > 1.0f) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return param.farClipPlane;
    }

    private static void resolveRenderUtilOnce() {
        if (renderUtilResolved) return;
        synchronized (DhFarFieldGeometry.class) {
            if (renderUtilResolved) return;
            try {
                Class<?> util = Class.forName("com.seibel.distanthorizons.core.util.RenderUtil");
                nearClipMethod = util.getMethod("getNearClipPlaneInBlocks");
                farClipMethod = util.getMethod("getFarClipPlaneDistanceInBlocks");
            } catch (Throwable failure) {
                nearClipMethod = null;
                farClipMethod = null;
                log("RenderUtil 反射不可用，near/far lane 退回占比换算：" + failure);
            }
            renderUtilResolved = true;
        }
    }

    private DhFarFieldGeometry() {
    }

    static String stats() {
        return "矩阵推送=" + pushes + " 失败=" + failures + " 丢弃非有限=" + rejected;
    }

    /** 16 个 float 全部有限才算有限。NaN/Inf 一律丢帧，见 {@link #push}。 */
    private static boolean allFinite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    

    private static final float MAX_MATRIX_MAGNITUDE = 1.0e4f;

    /**
     * 元素量级是否"像矩阵"—— 挡"有限但荒谬"的未初始化值。
     *
     * <p>2026-09-23 15:17 那一局实测：DH 每帧<b>第一次</b>回调给的矩阵不再是 NaN，而是
     * {@code v0=305163 v5=161536 v15=383.963}。{@code isFinite} 放行它 ⇒ 它被送进 UBO、
     * 被 rgen 当距离用（这次大概率因落在合法区间外而退到解析兜底，但也可能恰好落进去
     * ⇒ 远场几何闪一下）。这类值说明 DH 的矩阵还没算好，用上一帧的才是对的。</p>
     */
    private static boolean plausibleMagnitude(float[] values) {
        for (float value : values) {
            if (!(Math.abs(value) <= MAX_MATRIX_MAGNITUDE)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 渲染线程调用（DH 的 {@code DhApiBeforeRenderEvent} 里）。
     *
     * <p>失败只计数不抛：DH 的事件回调里抛异常会污染 DH 自己的渲染循环，
     * 而这里的失败（矩阵字段拿不到、native 未加载）都不该影响 DH 正常出图。</p>
     */
    static void push(DhApiRenderParam param) {
        if (!DhFlags.MATRICES || param == null) return;

        if (!configLogged) {
            configLogged = true;
            log("远场几何推送已启用：mode=" + MODE + " strength=" + STRENGTH
                    + " gain=" + GAIN + " variant=" + VARIANT
                    + " 接管判据=主射线 miss（我们命中就是我们的）"
                    + " 光照=与近场同源（直射绝对尺度 + 同一个环境底函数）"
                    + " 法线=倾角压制 天空护栏=480格"
                    + "（-1=总开关关闭 0=直通 1=误差图 2=法线 3=重打光 4=重打光+大气(默认)"
                    + " 5=距离来源 6=距离灰度 7=四联诊断 9=左右分屏对照"
                    + " 10=覆盖掩码 11=光照诊断 12=直通+大气(默认) 13=方向采样"
                    + "（13 用于治远场整体缩小/重叠错位；需 DLL 与 spv 同步更新））");
        }

        try {
            DhFarFieldSolver.inspect(param);

            DhApiMat4f inverse = param.dhInverseMvmProjectionMatrix;

            if (inverse == null) {
                failures++;
                return;
            }

            inverse.putValuesInArray(MATRIX_SCRATCH);

            boolean finite = allFinite(MATRIX_SCRATCH);
            boolean plausible = plausibleMagnitude(MATRIX_SCRATCH);

            if (!finite
                    || !plausible
                    || !Float.isFinite(param.nearClipPlane)
                    || !Float.isFinite(param.farClipPlane)) {
                rejected++;

                if (rejected <= 3) {
                    log("本帧矩阵不可用（" + (finite ? "数值荒谬" : "非有限")
                            + "），跳过推送（保留上一帧的好矩阵）：v0="
                            + MATRIX_SCRATCH[0] + " v5=" + MATRIX_SCRATCH[5]
                            + " v15=" + MATRIX_SCRATCH[15]
                            + " near=" + param.nearClipPlane
                            + " far=" + param.farClipPlane);
                }

                return;
            }

            System.arraycopy(MATRIX_SCRATCH, 0, PAYLOAD, 0, 16);
            PAYLOAD[16] = MODE;
            PAYLOAD[17] = STRENGTH;
            PAYLOAD[18] = GAIN;
            PAYLOAD[19] = VARIANT;
            final float nearBlocks = resolveNearClipBlocks(param);
            final float farBlocks = resolveFarClipBlocks(param);
            PAYLOAD[20] = nearBlocks;
            PAYLOAD[21] = farBlocks;
            PAYLOAD[22] = 0.0f;
            PAYLOAD[23] = 0.0f;

            boolean mvpUsable = false;
            if (param.dhMvmProjMatrix != null) {
                param.dhMvmProjMatrix.putValuesInArray(MATRIX_SCRATCH);
                mvpUsable = allFinite(MATRIX_SCRATCH) && plausibleMagnitude(MATRIX_SCRATCH);
                if (mvpUsable) {
                    System.arraycopy(MATRIX_SCRATCH, 0, PAYLOAD, 24, 16);
                }
            }
            if (!mvpUsable) {
                java.util.Arrays.fill(PAYLOAD, 24, PAYLOAD_LENGTH, 0.0f);
            }
            if (!mvpLogged) {
                mvpLogged = true;
                log(mvpUsable
                        ? "正向 MVM 已下发（mode 13 方向采样可用）：m00=" + PAYLOAD[24]
                        + " m11=" + PAYLOAD[29]
                        : "⚠ 正向 MVM 不可用（非有限 / 量级荒谬）⇒ mode 13 会退回屏幕直通");
            }
            if (!(nearBlocks > 0.0f) || !(nearBlocks < farBlocks * 0.25f)) {
                PAYLOAD[21] = 0.0f;
            }
            if (nearBlocks != lastLoggedNear || pushes % 1200L == 0L) {
                if (nearLogCount < 3 || pushes % 1200L == 0L) {
                    lastLoggedNear = nearBlocks;
                    nearLogCount++;
                    float m22 = (param.dhProjectionMatrix != null)
                            ? param.dhProjectionMatrix.m22 : Float.NaN;
                    float m23 = (param.dhProjectionMatrix != null)
                            ? param.dhProjectionMatrix.m23 : Float.NaN;
                    log("near/far lane（方块）：" + nearBlocks + " / " + farBlocks
                            + "（投影矩阵反解 m22=" + m22 + " m23=" + m23
                            + " ⇒ near=m22*far/(1+m22) far=m23/m22"
                            + "；DH 原始 nearClipPlane=" + param.nearClipPlane
                            + "；-Dorion.dh.farLight.nearOverride=方块值 可强制）");
                }
            }

            NativeVulkan.updateDhFarField(PAYLOAD);
            pushes++;
        } catch (Throwable failure) {
            failures++;
            if (failures <= 3) {
                log("推送失败（第 " + failures + " 次）：" + failure);
            }
        }
    }

    /**
     * 换世界 / 卸载时把矩阵标成无效（全零载荷）。
     *
     * <p>不这么做的话，新世界里 DH 还没渲染，C++ 会拿着上一个世界的矩阵去反投影，
     * 那比关掉更难查。</p>
     */
    static void invalidate() {
        DhFarFieldSolver.reset();
        Arrays.fill(PAYLOAD, 0.0f);
        try {
            NativeVulkan.updateDhFarField(PAYLOAD);
        } catch (Throwable ignored) {
        }
        pushes = 0;
        rejected = 0;
        configLogged = false;
    }

    private static float number(String propertyName, float defaultValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null) {
            raw = System.getenv(propertyName.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        if (raw == null) return defaultValue;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException failure) {
            log("属性 " + propertyName + " 的值 \"" + raw + "\" 不是数字，用默认 " + defaultValue);
            return defaultValue;
        }
    }

    private static void log(String message) {
        System.err.println(TAG + " " + message);
    }
}
