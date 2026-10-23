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

    /**
     * 与 C++ {@code Java_com_example_vulkan_NativeVulkan_updateDhFarField} 约定的载荷长度。
     *
     * <p>布局：{@code [0..15]} DH 逆 MVP、{@code [16..19]} mode/strength/gain/variant、
     * {@code [20..21]} near/far（格）、{@code [22..23]} 保留、{@code [24..39]} DH 的
     * <b>正向</b> MVM（方向采样用，mode 13）。</p>
     *
     * <p>C++ 侧对 {@code [24..39]} 是<b>可选</b>的：只收 24 个 float 的旧宿主会让
     * 正向矩阵保持全零，rgen 靠 {@code m00 == 0} 自证无效并退回屏幕直通 —— 两侧
     * 版本不匹配时是降级，不是崩。</p>
     */
    private static final int PAYLOAD_LENGTH = 40;

    private static final float[] PAYLOAD = new float[PAYLOAD_LENGTH];

    /**
     * {@link DhApiMat4f#putValuesInArray(float[])} 要求长度严格 16，所以先落到这个
     * 中间数组，检查完整性之后再拷进载荷。
     */
    private static final float[] MATRIX_SCRATCH = new float[16];

    /**
     * 远场阶段模式，来自 {@code -Dorion.dh.farLight.mode}：
     * <ul>
     *   <li><b>-1 —— 总开关关闭</b>：整个合成块一次都不执行，远场像素保持我们自己的
     *       颜色。回答"画面里某个现象是不是 DH 合成引入的"的<b>唯一干净判据</b>：
     *       关掉它现象消失 = 我们涂的；还在 = DH 自己画的或与 DH 无关</li>
     *   <li>0 —— 回到 stage-1 的 DH 直通（不做任何重打光）</li>
     *   <li>1 —— 误差图（红=矩阵重建落不上主射线，绿=落上了）：矩阵可信度的定量读数</li>
     *   <li>2 —— 显示重建出来的法线（N*0.5+0.5）：看一眼就知道法线是不是连贯的坡面</li>
     *   <li>3 —— 用我们的太阳/天空重打光</li>
     *   <li>4 —— 重打光之后再叠我们的晴空大气透视（<b>默认</b>，理由见下）</li>
     *   <li>5 —— 距离来源图：蓝=解析 reverse-Z（<b>首选</b>）、红=逆 MVP、
     *       绿=z 取镜像、黄=长度兜底</li>
     *   <li>6 —— 距离灰度（distance/far）：深度语义搞反的话地形会压成一片</li>
     *   <li>7 —— <b>四联诊断</b>：屏幕按 x 四等分，从左到右依次是 1 / 2 / 5 / 6
     *       ⇒ 一局就能同时读出"矩阵可信度 / 法线是否连贯 / 距离来自哪一级 / 深度语义"</li>
     *   <li>9 —— <b>左右分屏对照</b>：左半 = 0（DH 原图直通，我们一点不碰）、
     *       右半 = 4（默认档）。用来一次回答"某个现象是我们引入的，还是 DH 自己的"</li>
     *   <li>10 —— <b>覆盖掩码</b>：白 = 我们用 DH 内容接管；<b>品红 = 接管了但重建点
     *       在云层高度（&gt;480 格）</b> ⇒ 我们在往天空/云里涂；<b>红 = DH 在这个像素
     *       没有几何</b>（即 DH 挖的那个洞本身，含 overdrawPrevention 近裁剪与
     *       "高度覆盖"圆洞）。其余像素压暗 25% 作参照底</li>
     *   <li>11 —— <b>光照诊断</b>：R = NdotL（平地不该明显超过太阳高度角）、
     *       G = 光照因子（应与近场同量级）、B = 云透过率</li>
     *   <li>12 —— <b>DH 原图直通 + 我们的晴空大气透视（当前默认）</b>。
     *       不重打光：远场拿不到 GI（它不在 TLAS 里），硬重打光会在黄昏把远场
     *       压成极暗绿（实测 (11,13,8)），就是用户报的"一大坨黑影"。详见 rgen
     *       里 mode 12 分支的注释。</li>
     *   <li>13 —— <b>方向采样</b>（09-24 新增，治"远场整体缩小 / 与几何重叠错位"）。
     *       与 12 唯一的差别是"DH 图怎么采样"：12 按<b>屏幕 UV 1:1 直通</b>（隐含
     *       假设"DH 用的就是我们这台相机"），13 把本像素的<b>主射线方向</b>穿 DH
     *       自己的投影矩阵（{@code dhMvmProjectionMatrix}，w=0）再取纹素。
     *       实测两相机夹角 55~77° ⇒ 直通必然错尺度，13 对该差异免疫。
     *       矩阵缺失时自动退回直通（rgen 靠 {@code m00 == 0} 自证）。
     *       ⚠ 若 13 下远场大面积缺失，说明 DH 的视锥比我们的窄很多（那些方向在
     *       DH 图外）—— 那是"DH 只渲染了它自己那圈"的真实边界，不是 bug。</li>
     * </ul>
     *
     * <p><b>默认值的变迁（09-23 第五次定案）</b>：4 → 12。4 档（重打光 + 大气）
     * 建立在"远场能和近场用同一条光照模型"这个前提上，但实测证明**这个前提不成立**：
     * 近场 litColor 里最大的项之一是 {@code indirectLight * 1.8 * rtaoFactor}（GI），
     * 而远场不在 TLAS 里 ⇒ 拿不到 GI，只剩直射近似 + 环境底。黄昏太阳低、直射弱时
     * 这个缺口被放大到肉眼级别。要让远场和近场真正同源，必须走 C 阶段（远场代理几何
     * 入 TLAS），在那之前把默认退回"直通"更安全。</p>
     *
     * <p>⭐⭐ <b>默认值的变迁（09-24 第六次定案）：12 → -1。</b>
     * 上面那句"必须走 C 阶段"已经落地：{@link DhFlags#LOD_MESH} 于同日转正为默认开，
     * 远场几何现在真的在 TLAS 里、真的参与 GI / 阴影 / 反射。于是本开关（以及它背后
     * rgen 里约 4300 行的远场特例：反投影、near/far 解析式、三步回退、镜像点、邻居
     * 法线重建）已经<b>没有存在必要</b> —— 留着只会让两套远场互相叠加、各自半对。</p>
     *
     * <p>本档现在只剩一个职责：<b>回退开关</b>。要退回"采样 DH 成品图"的旧形态，
     * 传 {@code -Dorion.dh.farLight.mode=12}（或 13）即可，<b>不用重编 DLL 与 spv</b>。
     * 代码路径全部保留、只是不再默认走它；等实机确认统一路径无误之后再删。</p>
     * <p>（以上是 09-24 的历史；09-25 的方案 c 又把它接回来了，见下面这段。）</p>
     */
    /**
     * 远场合成模式。<b>当前默认 -1（关）</b>：09-25 首次打开方案 c（mode 13 + 发布纹理 +
     * 矩阵）时<b>进世界崩溃</b> —— {@code rtx_backend.dll} 抛出未捕获 C++ 异常
     * （{@code recordFrameCommand} 处冒出），同一时刻 DH 的纹理还是"回落图集"
     * （真句柄没到）⇒ 描述符/采样在句柄未就绪时就跑了。修好发布时序之前不要默认开。</p>
     *
     * <ul>
     *   <li>{@code -1}（默认）＝合成块不执行，远场保持我们自己的颜色（我们的几何照旧可见）；</li>
     *   <li>{@code 13} ＝ <b>方向采样</b>：主射线方向穿 DH 投影取 UV（对 FOV/朝向免疫）；</li>
     *   <li>{@code 12} ＝ 屏幕 UV 直通（要求两台相机角尺度一致，历史用法）。</li>
     * </ul>
     *
     * <p>⚠ 打开它必须同时满足：DH 在渲染（{@code disableRendering=false}）、
     * {@code publishTextures=true} 且 {@code [DH TEX]} 显示真句柄（不是"回落图集"）、
     * {@code matrices=true}。C++ 侧还会据此把 DH 远场实例的 mask 改成 0xfe（相机不可见），
     * 所以 mode=-1 时几何照旧可见、绝不会出现"远场空白"。</p>
     */
    static final int MODE = (int) number("orion.dh.farLight.mode", -1.0f);

    /**
     * 重打光强度。0 = DH 原图直通（stage-1 那套），1 = 完全用我们的光点灯。
     *
     * <p><b>默认 1.0（09-23 第三次定案）</b>。旧默认 0.65 是为了"别把 DH 烘焙进去的
     * 明暗二次计一次"——那个顾虑属于**旧的相对调制**实现（把我们的光当成乘在 DH 已受光
     * 颜色上的比值）。现在 rgen 把 DH 的图当 <b>albedo</b>、再用与近场**同一条**光照模型
     * 重新点灯（直射用 {@code getTimeDrivenSkyLightRadianceScale()} 的绝对尺度 + 环境底
     * 用近场同一个函数），所以：
     * <ul>
     *   <li>不存在二次计光：DH 的烘焙光照被**替换**掉，而不是叠上去；</li>
     *   <li>0.65 反而有害：它会把 DH 那张"日间亮度"的烘焙色混回 35%，夜里比我们的
     *       近场亮出一大截，交接处（我们数据的边缘）就会出现那条明显圆弧。</li>
     * </ul>
     * 只在"想刻意保留 DH 原图观感"时才往下调。</p>
     */
    static final float STRENGTH = number("orion.dh.farLight.strength", 1.0f);

    /** 重打光后整体亮度增益。两侧都偏暗/偏亮时先动它，而不是动 strength。 */
    static final float GAIN = number("orion.dh.farLight.gain", 1.0f);

    /**
     * 矩阵变体：0 = 自动（默认），1..4 = 强制第 N 个变体。
     * 只有自动选择明显失效时才需要动它。
     */
    static final float VARIANT = number("orion.dh.farLight.variant", 0.0f);

    private static int pushes;
    private static int failures;
    private static int rejected;
    private static boolean configLogged;
    private static float lastLoggedNear = Float.NaN;
    private static int nearLogCount;
    /** 正向 MVM（mode 13 用）的下发结果是否已经报告过（只报一次，避免刷屏）。 */
    private static boolean mvpLogged;

    /**
     * 免重编微调旋钮：{@code -Dorion.dh.farLight.nearOverride=方块值}。
     * &gt;0 时强制用它当 near lane（诊断"DH 真实投影 near 与 RenderUtil 不一致"用）。
     */
    private static final float NEAR_OVERRIDE = number("orion.dh.farLight.nearOverride", 0.0f);

    //=====================================================================
    // DH RenderUtil 的反射调用（core 包不在编译类路径上，只能运行时取）
    //=====================================================================
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

        // far = m23 / m22 ；near = m22 * far / (1 + m22)
        float far = m23 / m22;
        float near = m22 * far / (1.0f + m22);

        // 合理性护栏：near/far 必须有限且为正、far 明显大于 near。
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
        // ⭐ 反解出的 near 是 DH 渲染深度图的真实值，可能 < 1（实测 0.891 格）。
        // 不能再套 `>= 1.0` 护栏 —— 那个护栏的本意是挡"占比 0..1 被误当方块"，
        // 但反解值直接来自投影矩阵 m22/m23，就是真实 near/far 编码，无歧义。
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
                // 反射失败退到占比换算，不影响推送。
            }
        }
        // 占比 × 视距格数（getNearClipPlaneDistanceInBlocks 的主体就是这个乘积，
        // 差一个 FOV/aspect 修正因子，量级仍正确）。
        try {
            int renderDistance = net.minecraft.client.Minecraft.getInstance()
                    .options.renderDistance().get();
            float blocks = param.nearClipPlane * (renderDistance * 16.0f);
            if (Float.isFinite(blocks) && blocks >= 1.0f) {
                return blocks;
            }
        } catch (Throwable ignored) {
            // 选项不可读时放弃。
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
                // 反射失败退到 param 原值。
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

    /**
     * 合法矩阵元素的量级上限。
     *
     * <p>实测正常帧 {@code v0≈-33.5 v5≈17.3 v15≈0.002}（这台相机下 DH 的逆 MVP 明显是
     * 相对相机的、平移为 0），上限取 1e4 留了三个数量级余量。</p>
     */
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
            // =====================================================
            // 0) 先做在线验证（只读 + 打日志，不改矩阵）
            // =====================================================
            // 验证明两件事：16 个 float 的填充顺序（用 dhModelViewMatrix 是刚体变换
            // 这一结构事实判定）、以及 DH 与 MC 的视锥是否同源。结论在
            // run/logs/latest.log 的 [DH-MAT] 段；画面侧另有 rgen 的独立自检。
            DhFarFieldSolver.inspect(param);

            // =====================================================
            // 1) 取逆矩阵。**不再经过任何改写**（09-23 第二次定案）
            // =====================================================
            // 上一版把解算器"补偿平移"后的矩阵推下去；那条路已废：rgen 现在把位置
            // 锚定在自己的主射线上，矩阵只负责给一个标量距离，所以矩阵需要的只有
            // "别是垃圾"。改写反而会让"矩阵到底对不对"更难判读。
            DhApiMat4f inverse = param.dhInverseMvmProjectionMatrix;

            if (inverse == null) {
                failures++;
                return;
            }

            inverse.putValuesInArray(MATRIX_SCRATCH);

            // =====================================================
            // 2) 非有限值直接丢帧（不推零，也不置无效）
            // =====================================================
            // DH 每帧首个回调给的矩阵实测是 NaN（rt_debug.log `v0=-nan(ind)`）。推送
            // 侧必须挡住它：NaN 会让 rgen 的距离三级回落的比较全部为假（NaN 比较恒假），
            // 只剩解析兜底可用 —— 那虽然不会出垃圾，但白白丢掉了最精确的那一级。
            //
            // ⚠ 丢帧而不是推零：推零会被 C++ 判成"换世界/卸载"⇒ 远场退回直通，
            //    比"用上一帧的矩阵"更糟（矩阵一帧之内几乎不变）。
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
            // =====================================================
            // near/far lane —— ⭐⭐ 09-23 晚定案："微缩模型"的根因
            // =====================================================
            // param.nearClipPlane 实测是【视距占比】（0..1，今晚 0.891323；跨会话
            // 同值复现 ⇒ 是配置占比，不是方块）。旧版直接下发，rgen 的解析式
            // z = n·f/(n + d·(f−n)) 用它当方块 ⇒ 真实 200~3000 格的地形全部被压到
            // 相机周围 6~107 格 = 用户报的"每个地图都有一个微缩模型、永远无法到达"。
            //
            // 正确值 = DH 自己对同一张深度缓冲线性化时用的那两个函数（javap 已核：
            // BlazeDhSsaoRenderer 把 RenderUtil.getNearClipPlaneInBlocks() /
            // getFarClipPlaneDistanceInBlocks() 原样作为 SSAO 的 uniform），
            // 这里用同样的函数 ⇒ 约定逐字一致。
            final float nearBlocks = resolveNearClipBlocks(param);
            final float farBlocks = resolveFarClipBlocks(param);
            PAYLOAD[20] = nearBlocks;
            PAYLOAD[21] = farBlocks;
            PAYLOAD[22] = 0.0f;
            PAYLOAD[23] = 0.0f;

            // =====================================================
            // [24..39] 正向 MVM —— mode 13（方向采样）用
            // =====================================================
            // 与上面那个逆矩阵不同，这里要的是**正向**（world → DH clip）。
            // rgen 用它变换主射线的**方向**（w = 0.0），于是平移列天然被消掉 ⇒
            // 不需要 DH 的相机位置 —— 而那个位置根本拿不到（DH 把相机放在原点
            // 渲染，dhModelViewMatrix 的平移实测为 0）。
            //
            // ⭐ 这是"远场整体缩小 / 与几何重叠错位"的正解 —— 屏幕
            // UV 直通隐含假设"DH 用的就是我们这台相机"，实测两相机夹角 55~77°
            // ⇒ 直通必然错尺度。方向采样换用 DH 自己的投影，对该差异免疫。
            //
            // 非有限值照挡：NaN 会让 clip.xy / clip.w 变 NaN、texelFetch 的坐标
            // 未定义。挡法同样是"置零"而不是丢帧 —— 全零让 rgen 的 `m00 == 0`
            // 判无效并退回直通（距离路径仍在，不受影响）。
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
            // 换算失败（near 无效）时把 far 置 0 ⇒ rgen 的 lanesUsable 判假 ⇒
            // 退回矩阵路径，绝不能让垃圾值溜进解析式。
            // ⭐ near 反解值可 < 1（DH 实测 0.891 格），只要求 > 0 且 far 显著更大。
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
            // native 没加载也无需处理：那时本来就不会有矩阵到达 C++。
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
