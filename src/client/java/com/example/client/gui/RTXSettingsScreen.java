package com.example.client.gui;

import com.example.vulkan.NativeVulkan;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import com.example.client.config.RTXSettingsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.DoubleConsumer;

public final class RTXSettingsScreen extends OptionsSubScreen {


    private static final Component TITLE =
            Component.literal("RTX 设置");
    private int pendingReflexMode;

    private int pendingRRMode;

    private int pendingGiBounces;
    private int pendingAoNearSamples;
    private int pendingVolumetricRestir;
    private int pendingAoMidSamples;
    private int pendingSunShadowSamples;
    private int pendingLocalLightRisCandidates;
    private int pendingLocalLightAreaSamples;
    private int pendingWaterReflectionInterval;
    private int pendingGlossyFullDistance;
    private int pendingDynamicRtasInterval;
    private int pendingMaxRtParticles;
    // 2026-10-06 每像素采样数（spp）：离散档 0.5 / 1 / 2 / 3 / 4。
    private float pendingRtSpp;

    private float pendingNoDirectLightAmbient;

    private float pendingPOMDepth;
    // 2026-10-18 视差高度场采样：false = 平滑；true = 锐利。
    private boolean pendingPOMSharp;

    // 2026-10-25 两个性能档（会话内生效）：
    //   pendingGiNearRate      0 全速 / 1 半速 / 2 四分一
    //   pendingGlossyRoughLod  true = 光泽按粗糙度降速
    private int pendingGiNearRate;
    private boolean pendingGlossyRoughLod;

    // 2026-10-22 UE 风格体积云八项（索引与 NativeVulkan.setUeCloudParam 一致）
    private final float[] pendingUeCloud =
            RTXSettingsConfig.getUeCloudAll();

    private final String[] UE_CLOUD_LABELS = {
            "多散射 octaves", "  每阶贡献", "  每阶遮蔽", "散射亮度补偿",
            "地面反照贡献", "云环境光遮蔽", "逐采样大气透射", "天气图频率倍率"
    };
    private final float[] UE_CLOUD_MIN = {1.0f, 0.0f, 0.05f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    private final float[] UE_CLOUD_MAX = {3.0f, 1.0f, 1.00f, 3.0f, 1.0f, 4.0f, 2.0f, 4.0f};
    private final float[] UE_CLOUD_STEP = {1.0f, 0.05f, 0.05f, 0.05f,
            0.05f, 0.10f, 0.05f, 0.10f};

    // 八项共用一份滑条实现 —— 展开写是 8 × 60 行匿名类，没必要。
    private AbstractSliderButton makeUeCloudSlider(
            int index,
            String label,
            float min,
            float max,
            float step
    ) {
        return new AbstractSliderButton(
                0,
                0,
                310,
                20,
                Component.empty(),
                (this.pendingUeCloud[index] - min) / (max - min)
        ) {

            {
                this.updateMessage();
            }


            private float snapped() {
                final float raw =
                        min + (float) this.value * (max - min);
                final float snapped =
                        Math.round(raw / step) * step;
                return Math.max(min, Math.min(max, snapped));
            }


            @Override
            protected void updateMessage() {
                this.setMessage(
                        Component.literal(
                                String.format(
                                        java.util.Locale.ROOT,
                                        "%s: %.2f",
                                        label,
                                        this.snapped()
                                )
                        )
                );
            }


            @Override
            protected void applyValue() {
                final float value =
                        this.snapped();

                this.value =
                        (value - min) / (max - min);

                RTXSettingsScreen.this.pendingUeCloud[index] =
                        value;

                this.updateMessage();
            }
        };
    }
    // 2026-10-19 树叶次表面散射：false = 太阳背光（旧档）；true = 真次表面散射。
    private boolean pendingTrueSubsurfaceScattering;
    private float pendingLocalLightMaxDistance;
    private float pendingLocalLightIntensity;
    /** 2026-10-07 动态光源（移动光源）强度；0 = 关。 */
    private float pendingDynamicLightIntensity;
    private float pendingSunDiskLuminance;
    private float pendingSunHaloStrength;
    private float pendingSunAngularDiameterDegrees;
    private float pendingStarIntensity;
    // 2026-10-08 末地银河旋钮（CameraUBO.galaxyParams，原生侧应用）。
    private float pendingGalaxyScale;
    private float pendingGalaxyTiltDegrees;
    private float pendingGalaxyYawDegrees;
    private float pendingGalaxyMix;
    private float pendingSunTintStrength;
    // 2026-10-10 极光亮度倍率（CameraUBO.auroraParams.y）。生物群系决定出不出，
    // 这里是叠加亮度：0 = 关，1 = 现网观感。
    private float pendingAuroraBrightness;
    // 2026-10-10 极光流动速度倍率（CameraUBO.auroraParams.z）。
    private float pendingAuroraFlowSpeed;
    private boolean pendingPhysicalSkyEnabled;
    private float pendingScatterIntensity;
    private float pendingOzoneStrength;
    private float pendingPowderStrength;
    private float pendingCloudSunForwardScattering;
    // 批次1（2026-10-02）：火烧云总闸 + 夕阳云体红化（独立标量设置）。
    private float pendingCloudSunReddening;
    // 批次2-T2b：云顶随云量生长。
    private float pendingCloudTopStretch;
    // 批次4：云形抗重复域扭曲。
    private float pendingCloudDetileStrength;
    private float pendingCloudType;
    private float pendingCloudErosionScale;
    private float pendingCloudWeatherScale;
    private float pendingCloudSilver;
    private float pendingCloudViewDistance;

    private boolean pendingCloudEnabled;
    private float pendingCloudBottom;
    private float pendingCloudThickness;
    private float pendingCloudCoverage;
    private float pendingCloudDensity;
    private float pendingCloudDetailStrength;
    private float pendingCloudWindSpeed;
    private float pendingCloudEvolution;
    private int pendingCloudQuality;
    private float pendingCloudSize;
    private float pendingCloudDistribution;
    private float pendingCloudBaseFeather;
    // 2026-10-16：「云顶亮度 / 云底亮度」两个手动倍率已移除 —— 云顶/底明暗改由着色器里的
    // upwardAO 遮蔽与 (1-height) 权重物理决定，不再暴露手动旋钮。
    private float pendingCloudSunlightStrength;
    // 2026-10-02 清理：高层平面云的 20 个 pending 字段已随整层退役一并移除。
    // phi_fwd 多重散射漫射场 + MS 残量尾巴（2026-10-02）。4 个分量与
    // RTXSettingsConfig.CloudMsSettings 一一对应（由 apply 时组装）。
    private float msPhiIntensity;
    private float msPhiDepthPow;
    private float msPhiDepthBias;
    private float msOctaveStrength;
    // 批次2：步进旋钮（cloudMarchParams）。
    private float msAdaptiveLightRange;
    // 2026-10-02：远景云衰减距离（cloudMarchParams.z，原保留位；0 = 关闭）。
    private float msFarFadeDistance;

    // 参考重构（2026-10-02）phi_fwd 四件套（PAGE_CLOUD 的"补光*"四行）。
    private float phiBoundaryConfidence;
    private float phiMsBuildScale;
    private float phiCompress;
    private float phiConeRatio;

    // 参考重构（2026-10-02）受光逻辑三个尺度量（PAGE_CLOUD 的"受光*"三行）。
    private float lightSourceOdScale;
    private float lightSourceCurvePow;
    private float lightAoUpwardScale;
    // 2026-10-02：夕阳环境染色（cloudLightParams.w，原保留位）。
    private float lightSunsetAmbientTint;

    // 参考重构（2026-10-02）云底软化（形状，密度场共享）+ 物理月光项。
    private float shapeBottomSmoothHeight;
    private float shapeBottomSmoothPow;
    private float shapeTwoTierThreshold;
    private float moonStrength;
    private float moonPhase;

    // 第四阶段（2026-10-02）细节/形状旋钮（密度场共享）。
    private float shapeDetailScale;
    private float shapeDetailContrast;
    private float shapeProfileCurve;

    // 2026-10-02 参考重构 #2~#7（PAGE_CLOUD，五组新行）。
    //   amb* = #2 环境光接天空（天顶/地面倍率、朝阳侧暖环境、天光色相跟随）
    //   ms2* = #6 散射染色 + MS 级联三参数
    //   l2*  = #5 光路红化 + #7 粉末口径
    //   profile* = #3 剖面云型（密度场共享）
    //   wispy*   = #4 两族侵蚀（密度场共享）
    private float ambTopMul;
    private float ambBottomMul;
    private float ambSunSide;
    private float ambHueFollow;
    // 2026-10-14 日落天光修正（B 方案）：天光冷化 + 天光能量衰减。
    private float skyHueCool;
    private float skyHueEnergy;
    // 2026-10-17：② 方向性天光 + 顶底性质统一（同属 cloudSkyHueParams 的 .z/.w）。
    private float skyDirStrength;
    private float ambientUnify;
    // 2026-10-15 云地面反射与视觉回退项（cloudPhysicsParams）。
    private float cloudGroundAlbedo;
    private float cloudGroundBounce;
    private float cloudSunlightResidual;
    private float cloudPhaseFloor;
    private float ms2ScatterTint;
    private float ms2Octaves;
    private float ms2Attenuation;
    private float ms2Eccentricity;
    private float l2SunPathReddening;
    private float l2AtmosphereExtinction;
    private float l2PowderMode;
    private float profileType;
    private float profileTypeSpread;
    private float wispyStrength;
    private float wispyEdgeWidth;
    private float wispyReach;
    private float wispyTopHeight;
 // 2026-10-10（#3 天光自遮挡 / #4 时域速度抑制 /
    // #5 晨雾 / #6 彩虹 + 闪电）。全部出厂 0 = 关。
    private float cloudSkyVisibilityStrength;
    private float cloudSkyVisibilitySteps;
    private float cloudTemporalDampStrength;
    // 2026-10-16 云底遮蔽。
    private float cloudBottomOcclusion;
    private float fogExtrasDynamicLightStrength;
    private float fogExtrasGroundAnchorStrength;
    private float fogExtrasSkyAmbientStrength;
    private float fogExtrasTemporalJitterStrength;
    // 2026-10-10 借鉴（#1 云后空气透视 / #2 地面遮挡衰减）。
    // 全部出厂 0 = 关。
    private float cloudAerialStrength;
    private float groundAerialStrength;
    private float groundAerialOcclusion;
    private boolean msTwoTierStep;
    private boolean msMeanReproject;
    // 批次3：水体观感四件套（waterParams）。
    private float wpCrestBacklit;
    private float wpCausticDispersion;
    private float wpCausticDepthDiffusion;
    private boolean wpPhysicalPhase;

    // 2026-10-02 水面波面参考重构（HPWater）：波面法线侧三个（waterWaveParams）+
    // 波动方程侧三个（水模拟 push constant；障碍物反射已按需求移除）。
    private float waterWaveCalibration;
    private float waterWaveStrength;
    private float waterWaveBlend;
    // 2026-10-20 反射焦散强度：走 waterWaveParams 的**原保留 lane**（w3），不占新
    // UBO 尾块 ⇒ 其它消费者不必重编。与折射的「焦散亮度」是两个独立旋钮。
    private float waterReflectCausticStrength;
    private float waterSimSpongeWidth;
    private float waterSimGaussianSource;
    private float waterSimDampingScale;
    private float waterSimPropagationSpeed;
    // 2026-10-03 薄层 SSS 四件套（参考 HPWater BSDF）：非线性光程 / 能量补偿 / 相位 g / 总强度。
    private float waterSssNonlinear;
    private float waterSssBoost;
    private float waterSssPhaseG;
    private float waterSssStrength;
    // 2026-10-02 波形本体四件套（波长 / 风向 / 陡度 / 扭曲）。
    private float waterWaveLengthScale;
    private float waterWaveDirection;
    private float waterWaveCrestSharpness;
    private float waterWaveWarpStrength;
    // 2026-10-15 ROUTE C 波面后端（复用退役的 waterWaveShapeParams 尾块）。
    // 2026-10-20：域扭曲 / 波长带宽 两项已随 GUI 入口删除（ROUTE C 屏蔽后无编译端
    // 读它们，见 RTXSettingsConfig.WaterWaveAflSettings），wire 上仍占两个 lane。
    private float waterWaveAflScale;    // 最长波长 λmax（格）
    private float waterWaveAflLayers;   // 波数 count（波列根数）
    // 2026-10-03 FFT 频谱海面：开关 + 三个旋钮（2026-10-17 清理：删浪速/海浪风向/涌浪强度）。
    private boolean waterFftEnabled;
    private float waterFftAmplitude;
    private float waterFftChoppiness;
    private float waterFftTilePeriod;
    // 2026-10-19 波面细分：细节层倍率（0 = 关，1 = 出厂）。
    private float waterFftDetail;
    // 2026-10-03 后处理（新「后处理」设置页）。
    private int postTonemapMode;
    private float postExposureStrength;
    private float postContrast;
    private float postSaturation;
    private float postGamma;
    private int postMirrorBounceCount;
    private int postTlasCullDistance;
    private int postTlasFrustumCullMargin;
    private int postRrFarCloudDistance;
    /** 2026-10-07 泛光强度：0 = 关。 */
    private float postBloomStrength;
    /** 2026-10-23 调色调：线性光 R/G/B 增益（1 = 恒等）。
     *  必须显式初始化成 1：滑条最小值为 0.25，字段若还是 Java 默认 0，
     *  滑条构造时归一化值会被钳到 0（= 0.25），一次「应用」就把 0.25 落盘。 */
    private float postGainR = 1.0f;
    private float postGainG = 1.0f;
    private float postGainB = 1.0f;

    private boolean pendingFogEnabled;
    // 2026-10-19 B 方案体积雾（见 cpp/shaders/volumetric_fog.glsl）。旧的噪声尺度 /
    // 漂移速度 / 细节层数 / 破晓增强 / 贴地薄雾四件套 / Rayleigh 四项已随旧实现删除。
    private float pendingFogDensity;
    private float pendingFogHeight;
    private float pendingFogScaleHeight;
    private float pendingFogAnisotropy;
    private float pendingFogSunStrength;
    private float pendingFogAmbientStrength;
    private float pendingFogMaxDistance;
    private float pendingFogStartDistance;
    private int pendingFogSunSteps;
    private float pendingFogRainBoost;
    // 2026-10-23 雨天地面雾浪（只在雨天生效，见 cpp/shaders/volumetric_fog.glsl）。
    private boolean pendingFogWispEnabled;
    private float pendingFogWispStrength;
    private float pendingFogWispWavelength;
    private float pendingFogWispSpeed;
    private boolean pendingGodRayEnabled;
    private int pendingGodRaySteps;
    private int pendingGodRayOcclusionSamples;
    // 2026-10-03 体积光强度倍率（乘在昼夜 strength 上；1.0 = 着色器标定档）。
    private float pendingGodRayIntensity;
    // 2026-10-04（P1）体积光时间重投影的历史权重（0 = 关，0.85 = 出厂）。
    private float pendingGodRayReprojection;
    private float pendingRainOpacity;
    private float pendingRainStreakLength;
    private float pendingRainBrightness;
    private float pendingRainWidth;
    // 2026-10-23 雨丝卡片数量倍率（1.0 = 出厂；0.25 = 只有 1/4 卡片）。
    private float pendingRainDensityScale;
    // 2026-10-07 雪片设置。与雨的那组同一条 Java-side 管线：无 native 往返，
    // 应用的下一轮 20 Hz 天气重建即生效。
    private float pendingSnowSize;
    private float pendingSnowDensity;
    private float pendingSnowOpacity;
    private float pendingSnowBrightness;
    private float pendingSnowFallSpeed;
    private float pendingSnowSway;
    private boolean pendingRainWallEnabled;
    private float pendingRainWallWetness;
    private float pendingRainWallDripDensity;
    private float pendingRainWallDripSpeed;
    private float pendingRainWallDripSize;
    private float pendingRainWallDripBump;
    private float pendingRainWallDistance;

    private float pendingWaterStyleWaveScale;
    private float pendingWaterStyleWaveSpeed;
    private float pendingWaterStyleSwellScale;
    private float pendingWaterStyleMidScale;
    private float pendingWaterStyleFineScale;
    private float pendingWaterStyleTintMix;
    private float pendingWaterStyleDensityScale;
    private float pendingWaterStyleCausticStrength;

    private boolean pendingHDR;

    private boolean pendingAutoExposureEnabled;
    private float pendingAutoExposureCompensation;

    private boolean pendingFGFrameSync;

    // 2026-10-22（B2）：按刷新率钳制帧生成倍率。
    private boolean pendingFGRefreshCap;

    // 2026-10-22（D3）：下次启动是否用 development 构建（sl.imgui）。
    private boolean pendingSLDevPlugins;

    private boolean pendingSerEnabled;

    // 2026-10-22（MV 总开关）：0 = 关（rgen 每像素写 0，SL 用 depth 自己补相机
    // 运动），1 = 开。2/3 是排查用的 MV 口径档位，界面只暴露开/关。
    private int pendingMotionVectors;

    private int pendingFGMultiplier;

    private int pendingFGBaseFrameLimit;

    private boolean pendingArmorMetal;

    // 2026-10-03 远淡出跟随 RR 档位（体积云页；见 RTXSettingsConfig 的注释）。
    private boolean pendingCloudFadeFollowsRrMode;

    // 2026-10-06 远云剥离 RR（体积云页）：云在输出分辨率渲染、合成搬到 RR 之后，
    // 远云清晰度不再被 RR 档位的上采样限死。见 RTXSettingsConfig 的注释。
    private boolean pendingCloudPostRrComposite;

    // 2026-10-04 S2/S3（体积云页）：远云的"分辨率下限"与"远段步长加密"。
    // 两者都是"不改 = 旧行为"，一起加正好凑一对 ⇒ 不影响本页两列配对的奇偶。
    private int pendingCloudResFloorPercent;
    private float pendingCloudFarStepDensity;

 // 2026-10-09 平面云。9 项 = 1 开关 + 8 滑块，
    // 是奇数 ⇒ 组末用 flushOptionRow() 让落单那项占满一整行，不影响后面的组。
    private boolean pendingPlaneCloudEnabled;
    private float pendingPlaneCloudHeightOffset;
    private float pendingPlaneCloudScale;
    private float pendingPlaneCloudSpeed;
    private float pendingPlaneCloudDensity;
    private float pendingPlaneCloudCoverage;
    private float pendingPlaneCloudOctaves;
    private float pendingPlaneCloudFadeDistance;
    private float pendingPlaneCloudBrightness;

    // 2026-10-07 Perf-L10 实测后的两项 RT 优化（放在性能页）。
    // 都是"数值型"（0/1、距离格数），与本页既有滑条风格一致。
    private int pendingGiRoulette;
    private int pendingSunMarchFarDistance;

    // 2026-10-09 运动矢量调试视图（0 = 关 / 1 = 幅值热图 / 2 = 有向色）。
    // 它是**诊断工具**而不是收益项，但和上面两项同一性质（数值型、写 UBO 保留 lane、
    // 切换下一帧生效），所以放在性能页同一排，不单独开一个页面。
    private int pendingMotionDebugView;

    // 2026-10-26 两项新增（写法与上面同：写 CameraUBO.sparseParams 保留 lane，
    // 下一帧生效，不重建任何资源）：
    //   · 直接光采样：0 = 传统 RIS（每像素 16 提案 + 单根可见性），1 = RTXDI 式
    //     时域重用（历史 reservoir 合并 + 复用上帧选中的光源），省 RIS 提案的
    //     求值与阴影步，代价是移动光源/接收面下多一帧滞后。
    //   · 稀疏渲染采样率%：0 = 关（全像素都算 RT）；N = 每帧按白噪声只让约 N%
    //     的像素跑光追，其余像素复用历史/降噪。深度、MV、引导图始终密写。
    private int pendingDirectLightMode;
    private int pendingIndirectMode;
    private int pendingSparseRatePercent;

    // 2026-10-22：运动矢量总开关已从界面移除 —— 引擎内部固定为
    // "只给 ENTITY 层发位移段 + 像素→UV 换算"（见 native 的 mvLayerMask）。
    // 保留原字段会被误以为还能控制，所以整块删掉。

    // Two widgets are buffered here and flushed as one half-width row, so the
    // options render as a two-column grid instead of one 310px column.
    private final List<AbstractWidget> optionRow =
            new ArrayList<>();

    // =====================================================
    // Page routing
    //
    // This used to be one flat OptionsList of ~94 widgets with no headings at
    // all.  It is now a hub plus one page per category: on the hub addOptions()
    // only emits the category buttons, and on a sub-page each group tags itself
    // with `section` before it emits anything, so addOptionRow() drops every
    // widget that does not belong to the current page.
    //
    // The filter sits in addOptionRow() rather than around each group because
    // the groups are not contiguous -- the upscaler block, for example, is
    // interrupted by the SER and armour toggles, which live on other pages.
    // Tagging a section is one line per group and keeps the widget code exactly
    // where it always was.
    //
    // Note what this does NOT do: the pending* fields stay on the screen and
    // are never rebuilt per page, so moving between pages keeps the whole
    // draft.  确认 on the hub is still the only thing that writes it out.
    // =====================================================
    private static final int PAGE_HUB = -1;
    private static final int PAGE_UPSCALER = 0;
    private static final int PAGE_PERF = 1;
    private static final int PAGE_SKY = 2;
    private static final int PAGE_CLOUD = 3;
    private static final int PAGE_FOG = 4;
    private static final int PAGE_RAIN = 5;
    private static final int PAGE_WATER = 6;
    private static final int PAGE_MATERIAL = 7;
    private static final int PAGE_POST = 8;
    // 2026-10-26 **色彩分级**子页（用户要求：色彩映射仍留在后处理页可切换，
    // 另给一个入口点进去，用详细参数逐色调地调）。入口在 PAGE_POST 末尾。
    private static final int PAGE_COLORGRADE = 9;

    private int page = PAGE_HUB;

    // Which page the group currently being emitted belongs to.  Only meaningful
    // while addOptions() runs.
    private int section = PAGE_HUB;


    public RTXSettingsScreen(
            Screen lastScreen,
            Options options
    ) {
        super(
                lastScreen,
                options,
                TITLE
        );


        this.pendingReflexMode =
                RTXSettingsConfig.getReflexMode();

        this.pendingRRMode =
                RTXSettingsConfig.getRRMode();

        this.pendingGiBounces =
                RTXSettingsConfig.getPerformanceSettings().giBounces();
        RTXSettingsConfig.PerformanceSettings performance =
                RTXSettingsConfig.getPerformanceSettings();
        this.pendingAoNearSamples = performance.aoNearSamples();
        this.pendingVolumetricRestir = performance.volumetricRestir();
        this.pendingAoMidSamples = performance.aoMidSamples();
        this.pendingSunShadowSamples = performance.sunShadowSamples();
        this.pendingLocalLightRisCandidates = performance.localLightRisCandidates();
        this.pendingLocalLightAreaSamples = performance.localLightAreaSamples();
        this.pendingWaterReflectionInterval = performance.waterReflectionInterval();
        this.pendingGlossyFullDistance = performance.glossyFullDistance();
        this.pendingDynamicRtasInterval = performance.dynamicRtasInterval();
        this.pendingMaxRtParticles = performance.maxRtParticles();
        this.pendingRtSpp = performance.rtSpp();

        this.pendingNoDirectLightAmbient =
                RTXSettingsConfig.getNoDirectLightAmbient();

        this.pendingPOMDepth =
                RTXSettingsConfig.getPOMDepth();

        this.pendingPOMSharp =
                RTXSettingsConfig.isPOMSharp();

        // 2026-10-25 两个性能档：初值从原生读回（原生侧已有默认值 2 / true）。
        this.pendingGiNearRate =
                NativeVulkan.getGiNearRate();

        this.pendingGlossyRoughLod =
                NativeVulkan.getGlossyRoughLod();

        this.pendingTrueSubsurfaceScattering =
                RTXSettingsConfig.isTrueSubsurfaceScattering();

        this.pendingLocalLightMaxDistance =
                RTXSettingsConfig.getLocalLightMaxDistance();

        this.pendingLocalLightIntensity =
                RTXSettingsConfig.getLocalLightIntensity();

        this.pendingDynamicLightIntensity =
                RTXSettingsConfig.getDynamicLightIntensity();

        RTXSettingsConfig.SunVisualSettings sunVisuals =
                RTXSettingsConfig.getSunVisualSettings();
        this.pendingSunDiskLuminance = sunVisuals.diskLuminance();
        this.pendingSunHaloStrength = sunVisuals.haloStrength();
        this.pendingSunAngularDiameterDegrees = sunVisuals.angularDiameterDegrees();

        RTXSettingsConfig.AtmosphereSettings atmosphere =
                RTXSettingsConfig.getAtmosphereSettings();
        this.pendingStarIntensity = atmosphere.starIntensity();
        this.pendingSunTintStrength = atmosphere.sunTintStrength();
        this.pendingPhysicalSkyEnabled = atmosphere.physicalSkyEnabled();
        this.pendingScatterIntensity = atmosphere.scatterIntensity();
        this.pendingOzoneStrength = atmosphere.ozoneStrength();
        this.pendingPowderStrength =
                RTXSettingsConfig.getPowderStrength();
        this.pendingAuroraBrightness =
                RTXSettingsConfig.getAuroraBrightness();
        this.pendingAuroraFlowSpeed =
                RTXSettingsConfig.getAuroraFlowSpeed();
        this.pendingCloudSunForwardScattering =
                RTXSettingsConfig.getCloudSunForwardScattering();
        this.pendingCloudSunReddening =
                RTXSettingsConfig.getCloudSunReddening();
        this.pendingCloudTopStretch =
                RTXSettingsConfig.getCloudTopStretch();
        this.pendingCloudDetileStrength =
                RTXSettingsConfig.getCloudDetileStrength();
        this.pendingCloudType = RTXSettingsConfig.getCloudType();
        this.pendingCloudErosionScale = RTXSettingsConfig.getCloudErosionScale();
        this.pendingCloudWeatherScale = RTXSettingsConfig.getCloudWeatherScale();
        this.pendingCloudSilver = RTXSettingsConfig.getCloudSilver();
        this.pendingCloudViewDistance = RTXSettingsConfig.getCloudViewDistance();
        this.loadCloudMarchSettings(RTXSettingsConfig.getCloudMarchSettings());
        this.loadWaterParamsSettings(RTXSettingsConfig.getWaterParamsSettings());
        this.loadWaterWaveSettings(RTXSettingsConfig.getWaterWaveSettings());
        this.loadWaterSimWaveSettings(RTXSettingsConfig.getWaterSimWaveSettings());
        this.loadWaterWaveShapeSettings(RTXSettingsConfig.getWaterWaveShapeSettings());
        this.loadWaterWaveAflSettings(RTXSettingsConfig.getWaterWaveAflSettings());
        this.loadWaterSssSettings(RTXSettingsConfig.getWaterSssSettings());
        this.loadWaterFftSettings(RTXSettingsConfig.getWaterFftSettings());
        this.loadPostProcessingSettings(RTXSettingsConfig.getPostProcessingSettings());

        RTXSettingsConfig.CloudSettings clouds =
                RTXSettingsConfig.getCloudSettings();
        this.loadCloudSettings(clouds);
        this.loadCloudMsSettings(RTXSettingsConfig.getCloudMsSettings());
        this.loadCloudPhiSettings(RTXSettingsConfig.getCloudPhiSettings());
        this.loadCloudLightSettings(RTXSettingsConfig.getCloudLightSettings());
        this.loadCloudShapeSettings(RTXSettingsConfig.getCloudShapeSettings());
        this.loadCloudMoonSettings(RTXSettingsConfig.getCloudMoonSettings());
        this.loadCloudShape2Settings(RTXSettingsConfig.getCloudShape2Settings());
        this.loadCloudAmbientSettings(RTXSettingsConfig.getCloudAmbientSettings());
        this.loadCloudSkyHueSettings(RTXSettingsConfig.getCloudSkyHueSettings());
        this.loadCloudPhysicsSettings(RTXSettingsConfig.getCloudPhysicsSettings());
        this.loadCloudMs2Settings(RTXSettingsConfig.getCloudMs2Settings());
        this.loadCloudLight2Settings(RTXSettingsConfig.getCloudLight2Settings());
        this.loadCloudProfileSettings(RTXSettingsConfig.getCloudProfileSettings());
        this.loadCloudDetailSettings(RTXSettingsConfig.getCloudDetailSettings());
        this.loadCloudTemporalSettings(RTXSettingsConfig.getCloudTemporalSettings());

 // 2026-10-10（#5 晨雾 / #6 彩虹 + 闪电）。
        this.loadFogExtrasSettings(RTXSettingsConfig.getFogExtrasSettings());

        RTXSettingsConfig.FogWispSettings fogWisp = RTXSettingsConfig.getFogWispSettings();
        this.pendingFogWispEnabled = fogWisp.enabled();
        this.pendingFogWispStrength = fogWisp.strength();
        this.pendingFogWispWavelength = fogWisp.wavelength();
        this.pendingFogWispSpeed = fogWisp.speed();

        // 2026-10-10 借鉴（#1 云后空气透视 / #2 地面遮挡衰减）。
        this.loadCloudAerialSettings(RTXSettingsConfig.getCloudAerialSettings());

        RTXSettingsConfig.VolumetricFogSettings fog =
                RTXSettingsConfig.getVolumetricFogSettings();
        this.pendingFogEnabled = fog.enabled();
        this.pendingFogDensity = fog.density();
        this.pendingFogHeight = fog.height();
        this.pendingFogScaleHeight = fog.scaleHeight();
        this.pendingFogAnisotropy = fog.anisotropy();
        this.pendingFogSunStrength = fog.sunStrength();
        this.pendingFogAmbientStrength = fog.ambientStrength();
        this.pendingFogMaxDistance = fog.maxDistance();
        this.pendingFogStartDistance = fog.startDistance();
        this.pendingFogSunSteps = fog.sunSteps();
        this.pendingFogRainBoost = fog.rainBoost();

        RTXSettingsConfig.VolumetricLightSettings godRays =
                RTXSettingsConfig.getVolumetricLightSettings();
        this.pendingGodRayEnabled = godRays.enabled();
        this.pendingGodRaySteps = godRays.steps();
        this.pendingGodRayOcclusionSamples = godRays.occlusionSamples();
        this.pendingGodRayIntensity = godRays.intensity();
        this.pendingGodRayReprojection = godRays.reprojection();

        RTXSettingsConfig.RainSettings rainLook =
                RTXSettingsConfig.getRainSettings();
        this.pendingRainOpacity = rainLook.opacity();
        this.pendingRainStreakLength = rainLook.streakLength();
        this.pendingRainBrightness = rainLook.brightness();
        this.pendingRainWidth = rainLook.width();
        this.pendingRainDensityScale = rainLook.densityScale();

        RTXSettingsConfig.SnowSettings snowLook =
                RTXSettingsConfig.getSnowSettings();
        this.pendingSnowSize = snowLook.sizeScale();
        this.pendingSnowDensity = snowLook.densityScale();
        this.pendingSnowOpacity = snowLook.opacity();
        this.pendingSnowBrightness = snowLook.brightness();
        this.pendingSnowFallSpeed = snowLook.fallSpeedScale();
        this.pendingSnowSway = snowLook.swayBlocks();

        RTXSettingsConfig.RainWallSettings rainWall =
                RTXSettingsConfig.getRainWallSettings();
        this.pendingRainWallEnabled = rainWall.enabled();
        this.pendingRainWallWetness = rainWall.wetness();
        this.pendingRainWallDripDensity = rainWall.dripDensity();
        this.pendingRainWallDripSpeed = rainWall.dripSpeed();
        this.pendingRainWallDripSize = rainWall.dripSize();
        this.pendingRainWallDripBump = rainWall.dripBump();
        this.pendingRainWallDistance = rainWall.distance();

        RTXSettingsConfig.GalaxySettings galaxy =
                RTXSettingsConfig.getGalaxySettings();
        this.pendingGalaxyScale = galaxy.scale();
        this.pendingGalaxyTiltDegrees = galaxy.tiltDegrees();
        this.pendingGalaxyYawDegrees = galaxy.yawDegrees();
        this.pendingGalaxyMix = galaxy.mix();

        RTXSettingsConfig.WaterStyleSettings waterStyle =
                RTXSettingsConfig.getWaterStyleSettings();
        this.pendingWaterStyleWaveScale = waterStyle.waveScale();
        this.pendingWaterStyleWaveSpeed = waterStyle.waveSpeed();
        this.pendingWaterStyleSwellScale = waterStyle.swellScale();
        this.pendingWaterStyleMidScale = waterStyle.midScale();
        this.pendingWaterStyleFineScale = waterStyle.fineScale();
        this.pendingWaterStyleTintMix = waterStyle.tintMix();
        this.pendingWaterStyleDensityScale = waterStyle.densityScale();
        this.pendingWaterStyleCausticStrength = waterStyle.causticStrength();

        this.pendingHDR =
                RTXSettingsConfig.isHDREnabled();

        this.pendingAutoExposureEnabled =
                RTXSettingsConfig.isAutoExposureEnabled();

        this.pendingAutoExposureCompensation =
                RTXSettingsConfig.getAutoExposureCompensation();

        this.pendingFGFrameSync =
                RTXSettingsConfig.isFGFrameSyncEnabled();

        this.pendingFGRefreshCap =
                RTXSettingsConfig.isFGRefreshCapEnabled();

        this.pendingSLDevPlugins =
                RTXSettingsConfig.isSLDevPluginsEnabled();

        this.pendingSerEnabled =
                RTXSettingsConfig.isSerEnabled();

        this.pendingMotionVectors =
                RTXSettingsConfig.getMotionVectorsEnabled();

        this.pendingFGMultiplier =
                RTXSettingsConfig.getFGMultiplier();

        this.pendingFGBaseFrameLimit =
                RTXSettingsConfig.getFGBaseFrameLimit();

        this.pendingArmorMetal =
                RTXSettingsConfig.isEntityArmorMetalEnabled();

        this.pendingCloudFadeFollowsRrMode =
                RTXSettingsConfig.isCloudFadeFollowsRrMode();
        this.pendingCloudPostRrComposite =
                RTXSettingsConfig.isCloudPostRrComposite();
        RTXSettingsConfig.PlaneCloudSettings planeCloud =
                RTXSettingsConfig.getPlaneCloudSettings();
        this.pendingPlaneCloudEnabled = planeCloud.enabled();
        this.pendingPlaneCloudHeightOffset = planeCloud.heightOffset();
        this.pendingPlaneCloudScale = planeCloud.scale();
        this.pendingPlaneCloudSpeed = planeCloud.speed();
        this.pendingPlaneCloudDensity = planeCloud.density();
        this.pendingPlaneCloudCoverage = planeCloud.coverage();
        this.pendingPlaneCloudOctaves = planeCloud.octaves();
        this.pendingPlaneCloudFadeDistance = planeCloud.fadeDistance();
        this.pendingPlaneCloudBrightness = planeCloud.brightness();
        this.pendingCloudResFloorPercent =
                RTXSettingsConfig.getCloudResFloorPercent();
        this.pendingCloudFarStepDensity =
                RTXSettingsConfig.getCloudFarStepDensity();
        this.pendingGiRoulette =
                RTXSettingsConfig.getGiRoulette();
        this.pendingSunMarchFarDistance =
                RTXSettingsConfig.getSunMarchFarDistance();
        this.pendingMotionDebugView =
                RTXSettingsConfig.getMotionDebugView();
        this.pendingDirectLightMode =
                RTXSettingsConfig.getDirectLightMode();
        this.pendingIndirectMode =
                RTXSettingsConfig.getIndirectMode();
        this.pendingSparseRatePercent =
                RTXSettingsConfig.getSparseRatePercent();
    }


    @Override
    protected void addOptions() {

        this.optionRow.clear();

        if(this.page == PAGE_HUB) {
            this.section = PAGE_HUB;
            this.addHubButtons();
            return;
        }

        this.section = PAGE_UPSCALER;

        final boolean reflexAvailable =
                NativeVulkan.isLoaded()
                        && NativeVulkan.isReflexAvailable();


        final int currentMode =
                this.pendingReflexMode;


        CycleButton<Integer> reflexButton =
                CycleButton.builder(
                                mode -> {
                                    if(!reflexAvailable) {
                                        return Component.literal(
                                                "不可用"
                                        );
                                    }


                                    return switch(mode) {
                                        case 0 ->
                                                Component.literal(
                                                        "Off"
                                                );

                                        case 1 ->
                                                Component.literal(
                                                        "On"
                                                );

                                        case 2 ->
                                                Component.literal(
                                                        "On + Boost"
                                                );

                                        default ->
                                                Component.literal(
                                                        "On"
                                                );
                                    };
                                },
                                currentMode
                        )
                        .withValues(
                                0,
                                1,
                                2
                        )
                        .create(
                                Component.literal(
                                        "Reflex 低延迟"
                                ),
                                (button, mode) -> {

                                    if(!reflexAvailable) {
                                        return;
                                    }


                                    this.pendingReflexMode =
                                            mode;
                                }
                        );


        reflexButton.active =
                reflexAvailable;


        this.addOptionRow(
                reflexButton
        );

        CycleButton<Boolean> fgFrameSyncButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开" : "关"
                                ),
                                this.pendingFGFrameSync
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("FG 帧同步"),
                                (button, enabled) ->
                                        this.pendingFGFrameSync = enabled
                        );

        this.addOptionRow(
                fgFrameSyncButton
        );

        // What the driver currently allows. It reads 2x until frame generation
        // has reported its state once (needs a world), which is the honest
        // answer for a machine without the multi-frame unlock anyway.
        final int fgMultiplierMax =
                NativeVulkan.isLoaded()
                        ? NativeVulkan.getFGMultiplierMax()
                        : 2;

        // 2026-10-11：档位里加入"关"（0）。此前最低只能选到 2x，帧生成无法真正关掉，
        // 而 2x 仍然每渲染帧生成一帧、多一次 present —— 需要对照"每生成帧/每次
        // present 的开销"（例如排查内存增长）时必须能关。语义见 RTXSettingsConfig
        // 的 FG_MULTIPLIER_OFF：原生侧走既有的 DLSSGMode::eOff 通路，即时生效。
        CycleButton<Integer> fgMultiplierButton =
                CycleButton.builder(
                                multiplier -> Component.literal(
                                        multiplier == RTXSettingsConfig.FG_MULTIPLIER_OFF
                                                ? "关"
                                                : multiplier > fgMultiplierMax
                                                        ? multiplier + "x（上限 "
                                                                + fgMultiplierMax + "x）"
                                                        : multiplier + "x"
                                ),
                                this.pendingFGMultiplier
                        )
                        .withValues(RTXSettingsConfig.FG_MULTIPLIER_OFF, 2, 3, 4, 6)
                        .create(
                                Component.literal("帧生成倍增值"),
                                (button, multiplier) ->
                                        this.pendingFGMultiplier = multiplier
                        );

        this.addOptionRow(
                fgMultiplierButton
        );

        // Frame generation multiplies whatever the renderer produces, so the
        // base rate has to come down as the multiplier goes up: on a 165 Hz
        // panel, 6x only needs ~27 fps of rendering and anything faster is
        // generated and then discarded. Automatic pairing is refresh /
        // multiplier; the cap itself is delivered by the Reflex frame limiter.
        final int displayRefreshHz =
                NativeVulkan.isLoaded()
                        ? NativeVulkan.getDisplayRefreshRateHz()
                        : 0;

        final int autoBaseHz =
                displayRefreshHz > 0
                        && this.pendingFGMultiplier > 0
                        ? Math.max(1, displayRefreshHz / this.pendingFGMultiplier)
                        : 0;

        final String reflexWarning =
                this.pendingReflexMode == 0
                        ? "（需 Reflex 开）"
                        : "";

        CycleButton<Integer> fgBaseLimitButton =
                CycleButton.builder(
                                fps -> {
                                    if(fps == RTXSettingsConfig.FG_BASE_LIMIT_AUTO) {
                                        return Component.literal(
                                                autoBaseHz > 0
                                                        ? "自动（" + autoBaseHz + "fps）"
                                                        : "自动"
                                        );
                                    }

                                    if(fps == RTXSettingsConfig.FG_BASE_LIMIT_OFF) {
                                        return Component.literal("关");
                                    }


                                    return Component.literal(
                                            fps + "fps" + reflexWarning
                                    );
                                },
                                this.pendingFGBaseFrameLimit
                        )
                        .withValues(
                                RTXSettingsConfig.FG_BASE_LIMIT_AUTO,
                                RTXSettingsConfig.FG_BASE_LIMIT_OFF,
                                30,
                                41,
                                55,
                                82,
                                120,
                                165,
                                240,
                                300
                        )
                        .create(
                                Component.literal("基础帧率上限"),
                                (button, fps) ->
                                        this.pendingFGBaseFrameLimit = fps
                        );

        this.addOptionRow(
                fgBaseLimitButton
        );

        // 2026-10-22（B2）：按刷新率钳制倍率。
        // 官方 DLSS-G §7.4：60Hz 安全上限 4×、75Hz 5×，再往上只是把渲染时间
        // 换成 pacer 里的队列延迟（体感=更粘手）。关掉后只受驱动上限约束。
        CycleButton<Boolean> fgRefreshCapButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开" : "关"
                                ),
                                this.pendingFGRefreshCap
                        )
                        .withValues(true, false)
                        .create(
                                Component.literal("按刷新率限制倍率"),
                                (button, enabled) ->
                                        this.pendingFGRefreshCap = enabled
                        );

        this.addOptionRow(
                fgRefreshCapButton
        );

        // 2026-10-22（D3）：Streamline development 构建 + sl.imgui。
        // 说明为什么标「重启生效」：interposer 一旦加载就无法换版本，且
        // sl.imgui 只在 dev 构建里加载 —— 这一项只能在下一次启动时决定。
        // dev 包需放在 natives 目录下的 sl_debug_dev\（脚本：
        // _tmp_sl_imgui_stage.ps1）。
        CycleButton<Boolean> slDevPluginsButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开（重启生效）" : "关"
                                ),
                                this.pendingSLDevPlugins
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("SL 调试 UI（dev 构建）"),
                                (button, enabled) ->
                                        this.pendingSLDevPlugins = enabled
                        );

        this.addOptionRow(
                slDevPluginsButton
        );

        // Ends the frame-generation group so the SER toggle keeps pairing with
        // the DLSS quality button as before.
        this.flushOptionRow();

        this.section = PAGE_PERF;

        CycleButton<Boolean> serButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开（重启后生效）" : "关（重启后生效）"
                                ),
                                this.pendingSerEnabled
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("SER 二级 GI"),
                                (button, enabled) -> this.pendingSerEnabled = enabled
                        );

        this.addOptionRow(
                serButton
        );

        // 2026-10-22（MV 总开关）：出问题的即时退路 —— 关掉后 rgen 每像素写 0，
        // 原生侧同时把 cameraMotionIncluded 翻成 eFalse，由 SL 用 depth +
        // clipToPrevClip 自己补相机运动。下一帧生效（reset 由翻转触发，不用重启）。
        CycleButton<Boolean> motionVectorsButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开" : "关"
                                ),
                                this.pendingMotionVectors != 0
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("运动矢量（关闭后由 DLSS 自补相机运动）"),
                                (button, enabled) ->
                                        this.pendingMotionVectors = enabled ? 1 : 0
                        );

        this.addOptionRow(
                motionVectorsButton
        );

        this.section = PAGE_MATERIAL;

        // Metal response for armour and other equipment. Off by default because a
        // glossy entity hit costs one extra full-resolution reflection ray per
        // pixel, which is why approaching armour used to drop the frame rate hard.
        // Takes effect on the next captured batch, so no restart is needed.
        CycleButton<Boolean> armorMetalButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "开" : "关"
                                ),
                                this.pendingArmorMetal
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("盔甲金属反射"),
                                (button, enabled) -> this.pendingArmorMetal = enabled
                        );

        this.addOptionRow(
                armorMetalButton
        );

        this.section = PAGE_UPSCALER;

        // =====================================================
// DLSS Ray Reconstruction quality
// =====================================================
//
// 渲染路径恒为 RR，这里只留一个档位下拉。原先并排放着「RR 档位 /
// DLSS 渲染路径 / SR 档位」三个控件，档位与路径互相牵扯：选中哪一路的
// 档位，另一路就读不到自己的那份 options（useSR 只认一路），用户侧表现
// 就是"SR 档位怎么调都不生效"和"调 RR 会影响 SR"。SR 超分那条路已退役
// （强依赖 RR），路径不再可选，档位唯一对应 rrOptions。

        CycleButton<Integer> rrQualityButton =
                CycleButton.builder(
                                RTXSettingsScreen::qualityLabel,
                                this.pendingRRMode
                        )
                        .withValues(
                                RTXSettingsConfig.RR_MODE_PERFORMANCE,
                                RTXSettingsConfig.RR_MODE_BALANCED,
                                RTXSettingsConfig.RR_MODE_QUALITY,
                                RTXSettingsConfig.RR_MODE_DLAA,
                                RTXSettingsConfig.RR_MODE_ULTRA_PERFORMANCE
                        )
                        .create(
                                Component.literal("DLSS RR 光线重构档位"),
                                (button, mode) -> this.pendingRRMode = mode
                        );

        // 独占一整行：默认配对宽度只有 150px，"Ultra Performance" 会被截断成
        // 看不出是哪个档位。
        this.addOptionRow(rrQualityButton);
        this.flushOptionRow();

        this.section = PAGE_PERF;

        CycleButton<Integer> giBouncesButton =
                CycleButton.builder(
                                value -> Component.literal(
                                        value == 0 ? "Off" : Integer.toString(value)
                                ),
                                this.pendingGiBounces
                        )
                        .withValues(0, 1, 2, 3, 4)
                        .create(
                                Component.literal("GI 跳数"),
                                (button, value) -> this.pendingGiBounces = value
                        );

        this.addOptionRow(giBouncesButton);

        this.addOptionRow(performanceButton("体积光 ReSTIR", pendingVolumetricRestir,
                List.of(0, 1), value -> pendingVolumetricRestir = value));
        // 2026-10-27：加一个真正的「关闭」档（值 5）。旧 0 只是 0~16 格不发
        // AO 射线（32~96 格仍硬编码 1 条），5 才让整条 RTAO 链停掉，
        // 见 rt_test.rgen 里 perfPrimary.x >= 5 的判据。
        this.addOptionRow(performanceButton("RTAO 近距采样", pendingAoNearSamples,
                List.of(5, 0, 1, 2, 3, 4), value -> pendingAoNearSamples = value));
        this.addOptionRow(performanceButton("RTAO 中距采样", pendingAoMidSamples,
                List.of(0, 1, 2), value -> pendingAoMidSamples = value));
        this.addOptionRow(performanceButton("太阳阴影采样", pendingSunShadowSamples,
                List.of(1, 2), value -> pendingSunShadowSamples = value));
        this.addOptionRow(performanceButton("局部光 RIS 候选", pendingLocalLightRisCandidates,
                List.of(1, 2, 4, 8, 16, 32), value -> pendingLocalLightRisCandidates = value));
        this.addOptionRow(performanceButton("局部光区域采样", pendingLocalLightAreaSamples,
                List.of(1, 2), value -> pendingLocalLightAreaSamples = value));
        this.addOptionRow(performanceButton("水面反射间隔", pendingWaterReflectionInterval,
                List.of(0, 1, 2, 4), value -> pendingWaterReflectionInterval = value));
        this.addOptionRow(performanceButton("光泽全采样距离", pendingGlossyFullDistance,
                List.of(16, 32, 64, 96), value -> pendingGlossyFullDistance = value));
        this.addOptionRow(performanceButton("动态 RTAS 间隔", pendingDynamicRtasInterval,
                List.of(1, 2, 4, 8), value -> pendingDynamicRtasInterval = value));
        this.addOptionRow(performanceButton("RT 粒子上限", pendingMaxRtParticles,
                List.of(0, 64, 128, 256), value -> pendingMaxRtParticles = value));

        // 2026-10-06 每像素采样数（spp）。离散档，最低 0.5 = 棋盘交错（每帧只渲染
        // 棋盘格的一半像素，另一半保留上一帧的值）；>= 1 = 像素内多采样、平均掉
        // 随机次级项（GI / 反射 / 局部光）的方差。性能页其它离散档位走
        // performanceButton（CycleButton<Integer>），这里是浮点档，内联一个
        // CycleButton<Float>。
        CycleButton<Float> rtSppButton =
                CycleButton.builder(
                                value -> Component.literal(
                                        value == Math.floor(value)
                                                ? Integer.toString((int) (float) value) + " spp"
                                                : value + " spp"
                                ),
                                this.pendingRtSpp
                        )
                        .withValues(0.5f, 1.0f, 2.0f, 3.0f, 4.0f)
                        .create(
                                Component.literal("每像素采样数 (spp)"),
                                (button, value) -> this.pendingRtSpp = value
                        );
        this.addOptionRow(rtSppButton);

        // 2026-10-07 Perf-L10 交错 A/B 实测后的两项 RT 优化（详见 RTXSettingsConfig
        // 里两个字段的注释与 rt_test.rgen 的 stage 11/12）：
        //   · GI 俄罗斯轮盘：能量补偿版终止判定，unbiased（只改方差），实测省
        //     5.9ms(rt) / 7.8ms(整帧)。代价是 GI 噪声上升，交给 DLSS RR 降噪。
        //   · 太阳透射远距简化：着色点离相机超过该距离时，那 6 步 march 只用 1 步。
        //     实测省 4.0ms(rt) / 6.0ms(整帧)。0 = 关 = 固定 6 步（旧档）。
        this.addOptionRow(performanceButton("GI 俄罗斯轮盘(1=开)", pendingGiRoulette,
                List.of(0, 1), value -> pendingGiRoulette = value));
        this.addOptionRow(performanceButton("太阳透射距离(0=关)", pendingSunMarchFarDistance,
                List.of(0, 8, 16, 32, 64, 128, 256), value -> pendingSunMarchFarDistance = value));
        // 2026-10-26 直接光采样模式（0 = 传统 RIS = 旧档，1 = RTXDI 式时域重用）。
        // 1 档复用上一帧选中的光源、只在合并后打**一根**可见性射线（Remix 的
        // RTXDI_UpdateSelectedReservoirVisibility 语义），省掉每像素 16 提案的
        // 阴影步。代价：光源/接收面快速运动时多一帧滞后。1 档需要 reservoir
        // 历史，切回 0 时旧历史会被标签判据拒绝，不会串味。
        this.addOptionRow(performanceButton("直接光采样(1=RTXDI复用)", pendingDirectLightMode,
                List.of(0, 1), value -> pendingDirectLightMode = value));
        // 2026-10-28 间接光模式（对齐 Remix 的 IntegrateIndirectMode）。
        //   0 = GI：现有 4 跳路径 + 俄罗斯轮盘，逐位等于改动前。
        //   1 = SHaRC：空间哈希辐射缓存，命中即截断该像素剩余的 GI 路径
        //       （rgen:25493 的 break）。代价是 update pass 每帧额外成本，
        //       实测本机 0.8~2.5ms（取决于场景与覆盖率）。
        // ⚠ 切到 1 需要**重启客户端**：四块缓冲（176MB）与第二条 RT 管线在
        //   native init 时按模式创建，运行期改值不生效。
        this.addOptionRow(performanceButton("间接光模式(1=SHaRC,需重启)", pendingIndirectMode,
                List.of(0, 1), value -> pendingIndirectMode = value));
        // 2026-10-26 稀疏渲染：每帧按像素白噪声只让约 N% 的像素跑 RT 求值，
        // 其余像素把 RT 项按 0 处理，靠历史/降噪补。深度、运动矢量、各类引导图
        // 始终密写（否则 DLSS/RR 会看到洞）。0 = 关 = 全像素计算（旧档）。
        this.addOptionRow(performanceButton("稀疏渲染采样率%(0=关)", pendingSparseRatePercent,
                List.of(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100),
                value -> pendingSparseRatePercent = value));
        // 2026-10-09 运动矢量**调试图**（诊断用，出厂 0 = 关）。
        //   1 = 幅值热图：黑 = 零位移。静止时**整屏应当全黑**；出现均匀灰 = 有相机
        //       成分漏进了 MV。移动物体是一团亮斑。
        //   2 = 有向色：中性灰 = 0；红 = 水平位移、绿 = 垂直位移（带 0.5 偏置，
        //       所以正负都能分辨；颜色反了就是符号写反）。
        // 切换下一帧即生效（写 UBO 保留 lane，不重建资源）⇒ 可以边跑边来回切。
        //
        // 紧邻的**总开关**：出问题的即时退路。关掉后 rgen 把 MV 图写 0（不是不写）
        // ⇒ 与接 MV 之前逐位一致（静态世界与相机运动由 DLSS 自己算对，只有移动
        // 物体的残影回来）。它也是最干净的一次二分：关掉后异常消失 = 问题来自 MV。
        // 与调试图两行一起加，本页两列配对恰好恢复（原本是偶数行）。
        this.addOptionRow(performanceButton("MV 调试图(0=关)", pendingMotionDebugView,
                List.of(0, 1, 2), value -> pendingMotionDebugView = value));
        // 2026-10-22：4 档 —— 0 关 / 1 像素(1/w,1/h) / 2 像素(1/w,-1/h) / 3 UV(1,1)。
        // MV 数据实测为像素位移（[MV PROBE] maxAbs=5.875），官方口径是 1/w，
        // 但实机 {1,1}→{1/w,1/h} 之后仍鬼影，说明方向也需要试，所以给 4 档。
        // 2026-10-22：运动矢量不再是用户可调项。
        // 引擎内部固定策略：只给 ENTITY 层（生物/掉落物）发位移段，粒子层与玩家层
        // 不发；mvecScale 固定为像素→UV 的 1/w,1/h。排查过程留下的 4 档开关只保留
        // 在 native 里（ORION_MV_LAYERS 等 env 仍可用于实验），界面上不再暴露。

        this.section = PAGE_SKY;

        this.addOptionRow(cloudSlider("太阳圆盘亮度", 0.0, 8.0, 0.01,
                pendingSunDiskLuminance, "%.2f", value -> pendingSunDiskLuminance = (float) value));
        this.addOptionRow(cloudSlider("太阳日冕强度", 0.0, 1.0, 0.01,
                pendingSunHaloStrength, "%.2f", value -> pendingSunHaloStrength = (float) value));
        this.addOptionRow(cloudSlider("太阳角直径（度）", 0.10, 2.00, 0.01,
                pendingSunAngularDiameterDegrees, "%.2f", value -> pendingSunAngularDiameterDegrees = (float) value));
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingPhysicalSkyEnabled)
                .withValues(false, true)
                .create(Component.literal("物理大气散射"),
                        (button, enabled) -> this.pendingPhysicalSkyEnabled = enabled));
        this.addOptionRow(cloudSlider("大气散射强度", 0.0, 2.0, 0.05,
                pendingScatterIntensity, "%.2f", value -> pendingScatterIntensity = (float) value));
        this.addOptionRow(cloudSlider("臭氧吸收强度", 0.0, 2.0, 0.05,
                pendingOzoneStrength, "%.2f", value -> pendingOzoneStrength = (float) value));
        this.addOptionRow(cloudSlider("星空亮度", 0.0, 2.0, 0.05,
                pendingStarIntensity, "%.2f", value -> pendingStarIntensity = (float) value));
        // 2026-10-18 末地银河已退役（天空改为 Star Nest），四个银河旋钮一并移除。
        // CameraUBO.galaxyParams 与配置字段保留：旧存档/旧 DLL 仍能正常读写，
        // 只是不再有任何渲染效果。
        this.addOptionRow(cloudSlider("太阳透射染色", 0.0, 1.0, 0.01,
                pendingSunTintStrength, "%.2f", value -> pendingSunTintStrength = (float) value));
        // 2026-10-10 极光：生物群系决定出不出（ParticleRtCapture 推 x），这里两条
        // 是叠加在它之上的用户旋钮 —— 亮度倍率（auroraParams.y）与流动速度倍率
        //（auroraParams.z，帘幕时间步的乘数）。都默认 1.0 = 现网观感。
        this.addOptionRow(cloudSlider("极光亮度", 0.0, 3.0, 0.05,
                pendingAuroraBrightness, "%.2f", value -> pendingAuroraBrightness = (float) value));
        this.addOptionRow(cloudSlider("极光流动速度", 0.0, 4.0, 0.05,
                pendingAuroraFlowSpeed, "%.2f", value -> pendingAuroraFlowSpeed = (float) value));
        this.flushOptionRow();
        this.section = PAGE_CLOUD;

        // =================================================================
 // 2026-10-17 体积云 v2的滑杆面。
        //
        // v1 时代堆起来的 40+ 个云滑杆里，v2 只读 9 个 lane；其余（MS 级联、
        // phi_fwd 四件套、剖面/絮状/软化曲线、天光色相与顶底统一、地面反射、
        // 粉状暗边、两级步进、光路红化、云型区域…）在 v2 路径下**不参与任何
        // 计算**，留着只会让人以为调了有用 ⇒ UI 上一律撤销。Java 字段 / JSON
        // 键保留（存档兼容），只是不再暴露给用户。
        //
        // 新增两项（都复用已有 lane，不动 UBO 布局）：
        //   · 噪声八度 → cloudParams1.z（原「翻腾速度」，v2 不读翻腾）
        //   · 天光强度 → cloudParams5.w（原「云粉状暗边」，v2 不读 powder 旋钮）
        // 「体积云质量」现在同时决定云图分辨率：性能/平衡 = 1/2，高质量 = 1/1。
        // =================================================================
        this.addSectionHeader("体积云");

        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingCloudEnabled)
                .withValues(false, true)
                .create(Component.literal("体积云"),
                        (button, enabled) -> this.pendingCloudEnabled = enabled));
        this.addOptionRow(CycleButton.builder(
                        value -> Component.literal(switch(value) {
                            case 0 -> "性能";
                            case 2 -> "高质量";
                            default -> "平衡";
                        }),
                        this.pendingCloudQuality)
                .withValues(0, 1, 2)
                .create(Component.literal("体积云质量"),
                        (button, value) -> this.pendingCloudQuality = value));
 // 2026-10-18 范围校正：
        //   云的大小 1.0 = 参考（噪声 tile ≈ 层厚×8）；>2 基本没有团块感
        //   层厚 128~2000：参考整层只有 206 格，16 km 必然糊成一片
        // 上限必须与 RTXSettingsConfig 的 validCloudSetting、JNI 校验逐字一致。
        //
        // 2026-10-28 **云量 / 云大小彻底解耦**（这两条滑条以前是耦合的：
        // 调云量会把每一朵云一起削大削小，因为云量被塞进了密度阈值）：
        //   · 云的大小   → base 噪声周期（着色器里 noiseScale = 0.55²/size）
        //                  ⇒ 只改"每朵多大"，值越大云越大
        //   · 云量       → 分布遮罩 presence（整片区域有/无云，软化宽度固定）
        //                  ⇒ 只改"多少朵"，云体本身的形状不被削减
        // 滑条显示"云的大小倍率"（越大云越大），而存档/UBO 里存的是 **noiseScale**
        // （越大云越小，基座周期 = HP_BASE_TILE / noiseScale）⇒ 显示值 = 0.55 / 存档值。
        // 为什么不反过来改着色器：那会让存量存档（cloudSize=1.45）从"格胞 460 格"
        // 跳到 3196 格（> 1.5× 层厚）⇒ 云被竖着拉成柱子（2026-10-28 实机确认）。
        // 着色器侧另有"格胞 ≤ 层厚/2"的钳制兜底（cloud_v5_core.glsl 的 minNoiseScale）。
        final float sizeRef = 0.55f;
        final float sizeSlider = sizeRef / Math.max(pendingCloudSize, 0.05f);
        this.addOptionRow(cloudSlider("云的大小", 0.30, 2.0, 0.05, sizeSlider, "%.2f 倍",
                value -> pendingCloudSize = sizeRef / (float) value));
        this.addOptionRow(cloudSlider("云量（分布密度）", 0.10, 0.90, 0.01,
                pendingCloudCoverage, "%.2f", value -> pendingCloudCoverage = (float) value));
        this.addOptionRow(cloudSlider("云底高度", 96.0, 4000.0, 16.0,
                pendingCloudBottom, "%.0f", value -> pendingCloudBottom = (float) value));
        // 2026-10-19：上限 2000 → 5000（厚层才有体积；3D 噪声不怕竖条）
        this.addOptionRow(cloudSlider("云层厚度", 128.0, 5000.0, 16.0,
                pendingCloudThickness, "%.0f", value -> pendingCloudThickness = (float) value));
        // 参考出厂 VOLUMETRIC_CLOUDS_DENSITY = 1.0，本滑杆出厂 5.0 对应它
        // （换算在 volumetric_clouds_v2.comp 的 V2_DENSITY_GUI_REFERENCE）。
        this.addOptionRow(cloudSlider("云体密度", 0.25, 5.0, 0.05,
                pendingCloudDensity, "%.2f", value -> pendingCloudDensity = (float) value));
        // 2026-10-19 退役「风速」：2D 密度模型不读 wind（云是静态场）。
        this.flushOptionRow();

        this.addSectionHeader("体积云 · 外形");
        // 2026-10-18：删掉了 v4 不读的四项，避免误调：
        //   · 边缘侵蚀强度  → cfg.detailStrength 只作用于已废弃的图集路径
        //   · 卷曲强度      → v3 的 curl，v4 无此概念
        //   · 天气驱动强度  → v3 的 weather 混合权重，v4 恒为"图集全驱动"
        //   · 云底软边      → 高度梯度（云型）已经接管底面
        // 字段与 UBO lane 都保留（旧存档不丢），只是不给入口。
        // 2026-10-19 **两个外形入口**（原「云团对比 / 云层上下分布」，Hillaire 下已退役）
        // 竖向拉伸：0 → 竖向周期 1.0 倍（扁片/薄毯），1 → 5.0 倍（高塔）
        this.addOptionRow(cloudSlider("竖向拉伸（0扁片↔1高塔）", 0.0, 1.0, 0.01,
                pendingCloudDetailStrength, "%.2f",
                value -> pendingCloudDetailStrength = (float) value));
        // 边缘卷曲：domain warp 幅度 0~0.40 个云团尺度，越大边缘越有机
        this.addOptionRow(cloudSlider("边缘卷曲（0圆润↔1絮状）", 0.0, 0.33, 0.01,
                pendingCloudBaseFeather, "%.2f",
                value -> pendingCloudBaseFeather = (float) value));
        // 2026-10-19：「逆光银边」已退役，同一条 lane 改为 **多次散射强度**
        // （Hillaire 的 msOctave 权重，出厂 0.45）。它控制云内"透光发灰"的程度：
        // 0 = 只剩单次散射（暗部最沉），1 = 多次散射拉满（整体发亮）。
        this.addOptionRow(cloudSlider("多次散射强度", 0.0, 1.0, 0.01,
                pendingCloudFarStepDensity, "%.2f",
                value -> pendingCloudFarStepDensity = (float) value));
 // 2026-10-18：接入 v4 的密度模型。本项现在是**云缘细节尺度**：
        // 值 = 侵蚀噪声频率，×8 进 cfg.detailScale（出厂 10 → 80，周期 ≈278 格）。
        // 调小 → 云缘光滑；调大 → 絮状撕扯更细（>12 后开始碎成噪点）。
        this.addOptionRow(cloudSlider("云缘细节尺度", 0.1, 12.0, 0.1,
                pendingCloudDetileStrength, "%.1f（×8）",
                value -> pendingCloudDetileStrength = (float) value));
        // 2026-10-18 重构：2D FBM 模型下这一项是**竖向铺展**（h 的权重），
        // 0 = 云压在层底（薄），1 = 铺满层厚（厚、有体积感）。
        this.addOptionRow(cloudSlider("云层竖向铺展（0薄↔1厚）", 0.0, 1.0, 0.01,
                pendingCloudType, "%.2f", value -> pendingCloudType = (float) value));
        // 云缘侵蚀幅度：1.0 = 出厂标定（remap 里的 1.7 基准）。>1.5 会开始碎成孤岛。
        this.addOptionRow(cloudSlider("云缘侵蚀幅度", 0.0, 2.0, 0.05,
                pendingCloudErosionScale, "%.2f",
                value -> pendingCloudErosionScale = (float) value));
        // 2026-10-19 退役「天气图尺度」：2D 密度模型不读 weatherPeriod。
        // 「云距」：超过这个距离直接不画云（硬上限）
        this.addOptionRow(cloudSlider("云距（硬上限）", 2000.0, 200000.0, 1000.0,
                pendingCloudViewDistance, "%.0f 格",
                value -> pendingCloudViewDistance = (float) value));
        // 2026-10-19 新增：**远处淡出起点**。从 (值 × 云距) 开始，云逐渐变透明，
        // 到云距处完全消失 —— 没有这条就是生硬的"切边"。0.75 = 最后 25% 距离做渐变。
        this.addOptionRow(cloudSlider("远处淡出起点", 0.0, 0.99, 0.01,
                pendingCloudWeatherScale, "%.2f",
                value -> pendingCloudWeatherScale = (float) value));
        // 2026-10-19 **逆光银边**：逆光时云缘被穿透的阳光点亮。出厂 0.08（"一丁点"），
        // 大了夕阳处会整片过曝。
        this.addOptionRow(cloudSlider("逆光银边", 0.0, 1.0, 0.01,
                pendingCloudSilver, "%.2f",
                value -> pendingCloudSilver = (float) value));
        this.flushOptionRow();

        // 2026-10-25 **v5（HP）全参数**：28 项，一项不漏地接到滑条上。
        // 表格驱动（RTXSettingsConfig.CLOUD_HP_LABELS / CLOUD_HP_RANGES），
        // 每一条改完立刻下发 + 落盘；不再需要 28 个 pending 字段。
        this.addSectionHeader("体积云 · HP 全参数（v5）");
        float[] hpValues = RTXSettingsConfig.getCloudHpSettings().toParamArray();
        for(int hpIndex = 0; hpIndex < RTXSettingsConfig.CLOUD_HP_LABELS.length; ++hpIndex)
        {
            final int hpSlot = hpIndex;
            final float[] hpRange = RTXSettingsConfig.CLOUD_HP_RANGES[hpSlot];
            this.addOptionRow(cloudSlider(RTXSettingsConfig.CLOUD_HP_LABELS[hpSlot],
                    hpRange[0], hpRange[1], hpRange[2], hpValues[hpSlot], "%.3f",
                    value -> {
                        float[] next = RTXSettingsConfig.getCloudHpSettings().toParamArray();
                        next[hpSlot] = (float) value;
                        RTXSettingsConfig.CloudHpSettings updated =
                                new RTXSettingsConfig.CloudHpSettings(next);
                        if(!NativeVulkan.isLoaded()
                                || NativeVulkan.setCloudHpParams(updated.toParamArray())) {
                            RTXSettingsConfig.setCloudHpSettings(updated);
                        }
                    }));
        }
        this.flushOptionRow();

        this.addSectionHeader("体积云 · 步进（性能 / 画质）");
        // 2026-10-19：这两项原来是 v3 的「高度梯度 / 银边强度」，退役后复用为步进覆盖。
        // 0 = 跟随质量档（低 32 / 中 48 / 高 64 步），>0 = 直接指定。
        this.addOptionRow(cloudSlider("光线步进（0=跟随质量档）", 0.0, 160.0, 8.0,
                pendingCloudSunReddening, "%.0f 步",
                value -> pendingCloudSunReddening = (float) value));
        this.addOptionRow(cloudSlider("阴影步进（0=跟随质量档）", 0.0, 32.0, 1.0,
                pendingCloudTopStretch, "%.0f 步",
                value -> pendingCloudTopStretch = (float) value));
        this.flushOptionRow();

        this.addSectionHeader("体积云 · 受光");
        this.addOptionRow(cloudSlider("太阳照云强度", 0.0, 3.0, 0.01,
                pendingCloudSunlightStrength, "%.2f", value -> pendingCloudSunlightStrength = (float) value));
        // 参考只有一个 skyColor（C5 的 sunColor*r + skyColor*g）。这是它的曝光
        // 倍率：1.0 = 参考口径，>1 提亮背光面。
        this.addOptionRow(cloudSlider("天光强度", 0.0, 3.0, 0.05,
                pendingPowderStrength, "%.2f", value -> pendingPowderStrength = (float) value));
        // 2026-10-19 退役两项：
        //   · 高度梯度  → 与「外形」里的「云层竖向铺展」重复（同一个 height gradient）
        //   · 银边强度  → 与「逆光银边」重复，且那是 v3 的参数，v4 不读
        this.addOptionRow(cloudSlider("月光强度", -1, 3, 0.05,
                moonStrength, "%.2f", value -> moonStrength = (float)value));
        this.addOptionRow(cloudSlider("月光相位", -1, 1, 0.05,
                moonPhase, "%.2f", value -> moonPhase = (float)value));
        this.flushOptionRow();

        // =================================================================
        // 2026-10-22 UE 模型（volumetric_clouds_v4.comp → cloud_ue_core.glsl）的
        // 八项散射/受光旋钮。
        //
        // 2026-10-22 搬位：这八项原先被追加在 **材质页的视差(POM)区**里，把
        // 「视差深度」和「视差采样」硬生生隔开 —— 在体积云页根本找不到它们，
        // 调云的时候无从下手（用户原话："把体积云相关设置搬到它该在的地方"）。
        // 现在归位到体积云页，紧跟在「受光」之后：这八项正是同一件事的
        // UE 侧对应物（多散射 octave / 天光遮蔽 / 地面反照 / 大气透射）。
        //
        // 索引与 NativeVulkan.setUeCloudParam / CameraUBO.ueCloudA|B
        // （偏移 1536 / 1552）严格一致；apply 在设置面板的「确定」里统一提交。
        // 8 项 = 偶数 ⇒ 两列配对，正好 4 行，不需要落单占满整行。
        // =================================================================
        // 2026-10-26：UE 体积云已删除，本节只剩 **HDRP 也会读**的四项
        // （ueCloudA = msOctaves / 每阶贡献 / 每阶遮蔽 / 散射亮度补偿）。
        // 后四项（ueCloudB：地面反照贡献 / 云 AO / 逐采样大气透射 / 天气图倍率）
        // v5 着色器完全不读，滑条已撤掉 —— 留着只会让人以为调了有用。
        this.addSectionHeader("体积云 · 多次散射 / 能量");

        for(int i = 0; i < 4; i++) {

            this.addOptionRow(
                    this.makeUeCloudSlider(
                            i,
                            this.UE_CLOUD_LABELS[i],
                            this.UE_CLOUD_MIN[i],
                            this.UE_CLOUD_MAX[i],
                            this.UE_CLOUD_STEP[i]
                    )
            );

            if(i % 2 == 1) {
                this.flushOptionRow();
            }
        }

        this.addSectionHeader("体积云 · 时域");
        // 近云边缘走现有 resolve 的时域重投影：开 = 用透射率加权平均云深
        // （cloudMarchParams.w），关 = 用首命中距离。
        this.addOptionRow(CycleButton.builder(
                enabled -> Component.literal(enabled ? "开" : "关"), msMeanReproject)
                .withValues(false, true).create(Component.literal("软边时域重投影"),
                        (button, value) -> msMeanReproject = value));
        this.flushOptionRow();

        // 2026-10-26 **撤掉「平面云」整节**：它是遗留的 2D 高空云（一个高度平面 +
        // 8 个 octave 的 2D FBM），和 HDRP 体积云是两套完全不同的东西，却把开关
        // 挂在同一个 lane（highCloudParams0.x）上 —— 默认开着时天上会多出一层
        // 27 km 高、碎成一颗颗、以 UV 速度平移的橘色团块（用户实测截图）。
        // 现在 native 默认已改 0，入口也删掉：想要 2D 高空云请单独做一套，别再和
        // 体积云共用一个开关。配置字段保留（旧存档能正常读，值恒为 false）。

        this.addSectionHeader("大气透视");
        // 2026-10-10 借鉴（同一 cloudAerialParams 尾块）：
        //   #1 云层大气透视（volumetric_clouds_v2.comp 末尾）
        //   #2 地面大气透视强度 / 地面遮挡衰减（rgen）
        this.addOptionRow(cloudSlider("云层大气透视", 0, 2, 0.05,
                cloudAerialStrength, "%.2f", value -> cloudAerialStrength = (float) value));
        this.addOptionRow(cloudSlider("地面大气透视强度", 0, 2, 0.05,
                groundAerialStrength, "%.2f", value -> groundAerialStrength = (float) value));
        this.addOptionRow(cloudSlider("地面遮挡衰减", 0, 1, 0.05,
                groundAerialOcclusion, "%.2f", value -> groundAerialOcclusion = (float) value));
        this.flushOptionRow();

        this.section = PAGE_FOG;

        // =====================================================
        // 体积雾（2026-10-19 第二次重做，B 方案）。
        //
        // 顺序与参考 Lib/Settings.glsl:522-551 的 VFOG_* 一一对应：
        //   VFOG_DENSITY_BASE / VFOG_HEIGHT / VFOG_HEIGHT_2 / HG g /
        //   VFOG_SUNLIGHT_DENSITY / VFOG_FOG_DENSITY / VFOG_SUNLIGHT_STEPS /
        //   VFOG_RAIN_DENSITY_MUL
        // 细节见 cpp/shaders/volumetric_fog.glsl。
        // =====================================================
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingFogEnabled)
                .withValues(false, true)
                .create(Component.literal("体积雾"),
                        (button, enabled) -> this.pendingFogEnabled = enabled));
        // 参考 Lib/Settings.glsl 的档位是 0..100（出厂 1.0）。上限必须给到 100：
        // 本工程的 baseDensity 按 `VFOG_DENSITY_BASE * (wetness*0.15+0.08) / far`
        // 归一化，1.0 在常见视距下只有 ~1.5e-4/格，几乎看不见。
        this.addOptionRow(cloudSlider("雾密度基准 (VFOG_DENSITY_BASE)", 0.00, 100.00, 0.50,
                pendingFogDensity, "%.2f", value -> pendingFogDensity = (float) value));
        this.addOptionRow(cloudSlider("雾高度上界 (VFOG_HEIGHT)", -64, 320, 1,
                pendingFogHeight, "%.0f 格", value -> pendingFogHeight = (float) value));
        this.addOptionRow(cloudSlider("雾高度下界 (VFOG_HEIGHT_2)", -64, 320, 1,
                pendingFogScaleHeight, "%.0f 格", value -> pendingFogScaleHeight = (float) value));
        this.addOptionRow(cloudSlider("太阳散射强度", 0.00, 4.00, 0.05,
                pendingFogSunStrength, "%.2f", value -> pendingFogSunStrength = (float) value));
        this.addOptionRow(cloudSlider("环境散射强度", 0.00, 4.00, 0.05,
                pendingFogAmbientStrength, "%.2f", value -> pendingFogAmbientStrength = (float) value));
        this.addOptionRow(cloudSlider("散射各向异性 g", 0.00, 0.90, 0.05,
                pendingFogAnisotropy, "%.2f", value -> pendingFogAnisotropy = (float) value));
        this.addOptionRow(cloudSlider("雨天雾增强", 0.0, 8.0, 0.25,
                pendingFogRainBoost, "%.2f 倍", value -> pendingFogRainBoost = (float) value));
        this.addOptionRow(cloudSlider("雾最远作用距离", 64, 1024, 32,
                pendingFogMaxDistance, "%.0f 格", value -> pendingFogMaxDistance = (float) value));
        this.addOptionRow(cloudSlider("近处留白距离", 0, 64, 2,
                pendingFogStartDistance, "%.0f 格", value -> pendingFogStartDistance = (float) value));
        // 唯一的成本旋钮：沿太阳方向对雾密度做 march 的步数（1..8）。
        // 它决定雾内自吸收的精度，不是旧的阴影图“太阳可见性取样点数”。
        this.addOptionRow(cloudSlider("太阳取样点（性能）", 1, 8, 1,
                pendingFogSunSteps, "%.0f", value -> pendingFogSunSteps = (int) Math.round(value)));
        this.flushOptionRow();

        // =====================================================
        // 2026-10-23 雨天地面雾浪：低空一层横向拉长的雾，被风吹着贴地爬。
        // 只在雨天出现（着色器里乘雨强 ⇒ 晴天调这些不影响画面）。
        // 成本：每个 march 步 +2 次 3D 噪声采样；"强度 = 0"时整段跳过。
        // =====================================================
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingFogWispEnabled)
                .withValues(false, true)
                .create(Component.literal("雨天地面雾浪"),
                        (button, enabled) -> this.pendingFogWispEnabled = enabled));
        this.addOptionRow(cloudSlider("雾浪强度", 0.0, 4.0, 0.05,
                pendingFogWispStrength, "%.2f",
                value -> pendingFogWispStrength = (float) value));
        // 层厚由波长推出（波长 × 0.035，钳在 1.5~20 格），所以没有单独的"高度"滑杆：
        // 浪越大本来就该越厚，拆成两个旋钮只会让人调到互相矛盾的状态。
        this.addOptionRow(cloudSlider("雾浪尺度（波长）", 32, 1024, 4,
                pendingFogWispWavelength, "%.0f 格",
                value -> pendingFogWispWavelength = (float) value));
        this.addOptionRow(cloudSlider("雾浪漂移速度", 0.0, 4.0, 0.05,
                pendingFogWispSpeed, "%.2f 格/秒",
                value -> pendingFogWispSpeed = (float) value));
        this.flushOptionRow();

        // =====================================================
        // Underwater volumetric light。
        //
        // 2026-10-19 用户定案：**空气体积光退役**（`VL_AIR_GODRAYS = 0`）。
        // 体积雾只有一份 VolumetricFog()，本工程的光柱现在完全由
        // 体积雾的 rayColor 提供；空气里再跑第二套 march 会让"没有缝隙的
        // 空旷场景也整片发光"，观感假。水下不受影响：雾不覆盖水下介质，
        // 由 volumetric_light.comp 的 marchUnderwaterGodRays 负责。
        // =====================================================
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingGodRayEnabled)
                .withValues(false, true)
                .create(Component.literal("水下体积光"),
                        (button, enabled) -> this.pendingGodRayEnabled = enabled));
        // 水下分支的两根纯成本旋钮：观感由着色器推导，这里只决定花多少采样。
        this.addOptionRow(cloudSlider("体积光采样步数（性能）", 4, 48, 2,
                pendingGodRaySteps, "%.0f", value -> pendingGodRaySteps = (int) Math.round(value)));
        this.addOptionRow(cloudSlider("体积光云影采样（性能）", 1, 6, 1,
                pendingGodRayOcclusionSamples, "%.0f", value -> pendingGodRayOcclusionSamples = (int) Math.round(value)));
        // 2026-10-06 设置清理：体积光雾内自阴影滑杆删除（出厂 0.08 近乎 no-op）。
        // 2026-10-03 体积光强度：乘在昼夜 strength 上的倍率（godRayParams[3]）。
        // 1.0 = 着色器编译期标定档（正午 GODRAY_NOON_STRENGTH、黎明
        // GODRAY_MORNING_STRENGTH 不变），0 = 关光柱，上限 4.0。改变的是
        // 10-02 标定的"出厂观感"，不碰相位与雾密度。
        this.addOptionRow(cloudSlider("体积光强度", 0.0, 3.0, 0.05,
                pendingGodRayIntensity, "%.2f", value -> pendingGodRayIntensity = (float) value));
        // 2026-10-04（P1）：时间重投影历史权重。0 = 每帧独立 march（无拖尾，等价
        // 旧行为）；越高越干净（抖动噪声被时间平均掉；当前为全分辨率 march），
        // 代价是云层快速变化/快速转视角时可能留下轻微拖尾。
        // 着色器侧另有"有界历史"钳制兜底。
        this.addOptionRow(cloudSlider("体积光时间重投影", 0.00, 0.95, 0.05,
                pendingGodRayReprojection, "%.2f",
                value -> pendingGodRayReprojection = (float) value));
        this.flushOptionRow();

        this.section = PAGE_RAIN;

        // =====================================================
        // RT rain streaks.  Java-side only: the weather lattice is rebuilt
        // every frame by ParticleRtCapture, so none of this needs a native
        // round trip and a failure can never desync the two sides.
        //
        // These exist because the streak look depends on how close the nearest
        // drop happens to be -- the cards are fixed-size billboards -- so it
        // has to be judged in game rather than baked in.
        // =====================================================
        // 粗细 comes first because it is the only knob that changes how thick
        // the rain looks.  It is an absolute world width, so unlike 长度 it
        // does not interact with anything else -- 长度 only stretches the
        // streak vertically now.  The floor used to be 2.0 mm with a 0.5 mm
        // step, which made the whole drizzle range unreachable: 1.0 mm is a
        // couple of screen pixels of streak at ten blocks, and the useful
        // travel for tuning is between there and ~4 mm.
        this.addOptionRow(cloudSlider("雨线粗细", 1.0, 40.0, 0.1,
                pendingRainWidth, "%.1f 毫米", value -> pendingRainWidth = (float) value));
        this.addOptionRow(cloudSlider("雨线长度", 0.4, 8.0, 0.1,
                pendingRainStreakLength, "%.1f 格", value -> pendingRainStreakLength = (float) value));
        // 浓度 is the vertex alpha.  It is mostly an opacity control, but a
        // lower value also trims the atlas' anti-aliased rim, so it moves the
        // apparent width by up to ~1.8x across its travel.  This is the only
        // real transparency control the RT weather pass has: survivors are
        // always drawn at full albedo, so "more transparent" means "fewer
        // pixels survive", not "a dimmer line".
        this.addOptionRow(cloudSlider("雨线浓度", 0.05, 1.0, 0.01,
                pendingRainOpacity, "%.2f", value -> pendingRainOpacity = (float) value));
        // 2026-10-23 卡片数量：唯一直接决定"雨丝几何成本"的旋钮（粒子层 BLAS 的
        // 三角形数 ∝ 这个值，主射线的 any-hit 次数也 ∝ 它）。0.25 ⇒ 1/4 卡片，
        // 视觉上雨幕变稀，但帧率会明显回升。区间故意只给到 2.0 以下——
        // 向上加量没有对应的观感收益，只是把性能还回去。
        this.addOptionRow(cloudSlider("雨线数量（性能）", 0.1, 2.0, 0.05,
                pendingRainDensityScale, "%.2f 倍",
                value -> pendingRainDensityScale = (float) value));
        this.addOptionRow(cloudSlider("雨线亮度", 0.05, 3.0, 0.01,
                pendingRainBrightness, "%.2f 倍", value -> pendingRainBrightness = (float) value));

        // =====================================================
        // 2026-10-07 雪片。与雨的 streak 组同一性质：ParticleRtCapture 每 20 Hz
        // 重建天气格时直接读 config，纯 Java 侧，应用不可能失配。倍率类旋钮
        // 的 1.0 就是出厂观感（不是区间中点）；密度只动格数、顶在 1764 格的
        // 硬顶上，拉满也不改变最坏 quad 成本。
        // =====================================================
        this.addOptionRow(cloudSlider("雪密度", 0.25, 4.0, 0.05,
                pendingSnowDensity, "%.2f 倍", value -> pendingSnowDensity = (float) value));
        this.addOptionRow(cloudSlider("雪片大小", 0.25, 4.0, 0.05,
                pendingSnowSize, "%.2f 倍", value -> pendingSnowSize = (float) value));
        // 与"雨线浓度"同语义：顶点 alpha = 射线存活率。拉低同时削掉 sprite
        // 的抗锯齿边缘，片会显得略小略脆。
        this.addOptionRow(cloudSlider("雪浓度", 0.05, 1.0, 0.01,
                pendingSnowOpacity, "%.2f", value -> pendingSnowOpacity = (float) value));
        this.addOptionRow(cloudSlider("雪亮度", 0.05, 3.0, 0.01,
                pendingSnowBrightness, "%.2f 倍", value -> pendingSnowBrightness = (float) value));
        this.addOptionRow(cloudSlider("雪下落速度", 0.25, 4.0, 0.05,
                pendingSnowFallSpeed, "%.2f 倍", value -> pendingSnowFallSpeed = (float) value));
        this.addOptionRow(cloudSlider("雪摇摆幅度", 0.0, 1.0, 0.05,
                pendingSnowSway, "%.2f 格", value -> pendingSnowSway = (float) value));

        // =====================================================
        // Wall run-off: the wet film and the runnels on vertical faces.
        //
        // Ordering is the same rule the fog and god-ray groups follow -- the
        // look first, the one cost knob last.  Unlike the streak block above
        // these do have to reach the native side (the shader reads them from
        // CameraUBO.rainWallParams), so a failed apply keeps the old values
        // instead of leaving the config and the shader out of step.
        //
        // Every scale slider sits at 1.0 out of the box, and 1.0 is exactly the
        // hand-tuned look, not a midpoint: the useful travel is mostly one-way
        // from there.  The ranges mirror RTXSettingsConfig.RainWallSettings.
        // =====================================================
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingRainWallEnabled)
                .withValues(false, true)
                .create(Component.literal("墙面雨痕"),
                        (button, enabled) -> this.pendingRainWallEnabled = enabled));
        // One wetness number instead of separate darkening and roughness
        // sliders: only their combination is visible, so two sliders would
        // move one quantity and neither would be predictable alone.
        this.addOptionRow(cloudSlider("墙面湿度", 0.0, 2.0, 0.05,
                pendingRainWallWetness, "%.2f 倍", value -> pendingRainWallWetness = (float) value));
        // Fraction of the rivulet columns that run.  This is the knob that goes
        // between "a sheen on the wall" and "a curtain", so it is the first one
        // to move when the wall looks wrong.
        this.addOptionRow(cloudSlider("墙面水滴密度", 0.0, 1.0, 0.01,
                pendingRainWallDripDensity, "%.2f", value -> pendingRainWallDripDensity = (float) value));
        // Real run-off is 0.05-0.30 blocks/s at 1.0; the dial scales both ends
        // at once, which keeps the spread between slow and fast columns fixed.
        this.addOptionRow(cloudSlider("墙面水流速度", 0.1, 4.0, 0.05,
                pendingRainWallDripSpeed, "%.2f 倍", value -> pendingRainWallDripSpeed = (float) value));
        // 2026-10-07（用户要求）：雨滴**大小**。它同时决定水滴尺寸与格距 —— 同一场域里
        // 做不到只改一个，所以"越大越疏"，要与浓度分开调就用上面的密度档。
        // 旧档那条"拖尾长度"随旧水柱模型退役，位置（连同取值范围 0.25~4.0）由这一条接管。
        this.addOptionRow(cloudSlider("雨滴大小", 0.25, 4.0, 0.05,
                pendingRainWallDripSize, "%.2f 倍", value -> pendingRainWallDripSize = (float) value));
        // Droplets are a normal-map effect: this bends the surface normal
        // outward and lets the specular highlight sell the water.  Too high and
        // the wall reads as beaten metal, which is why 1.0 out of 2.0 is the
        // default rather than the midpoint.
        this.addOptionRow(cloudSlider("墙面水滴凸起", 0.0, 2.0, 0.05,
                pendingRainWallDripBump, "%.2f 倍", value -> pendingRainWallDripBump = (float) value));
        // The only knob here that is also a cost control: it gates the film, the
        // droplets and the sky-exposure ray, so lowering it is the first thing
        // to try if the frame time matters.  The droplets cut out slightly
        // earlier than the film at any setting.
        this.addOptionRow(cloudSlider("墙面雨范围（性能）", 16, 160, 8,
                pendingRainWallDistance, "%.0f 格", value -> pendingRainWallDistance = (float) value));
        this.flushOptionRow();

        this.section = PAGE_WATER;

        // =====================================================
        // Water style (Waterlight turquoise look).  Native-backed like the
        // wall block above -- the shaders read these from
        // CameraUBO.waterStyleParams -- so the same apply rule holds: push to
        // the shader first, persist only if the native side accepted.
        //
        // All four are dials over the shader's hand-tuned constants, so
        // 波浪/水色/浓度 at 1.0 and 焦散 at 2.0 is exactly the shipped look.
        // =====================================================
        // One multiplier for every natural wave band; the interaction field
        // and rain ripples keep their own calibrated strength at any setting.
        this.addOptionRow(cloudSlider("波浪强度", 0.0, 2.0, 0.05,
                pendingWaterStyleWaveScale, "%.2f 倍", value -> pendingWaterStyleWaveScale = (float) value));
        // Natural-wave phase speed multiplier (2026-09-30): 1.0 = shipped
        // look, 0.0 freezes the natural waves.  The interaction field
        // (swimming rings / rain ripples) is a physical simulation and keeps
        // its real rate at any setting.  Dragging it rescales the accumulated
        // wave phase, so the surface snaps once per change -- expected.
        this.addOptionRow(cloudSlider("波速", 0.0, 3.0, 0.05,
                pendingWaterStyleWaveSpeed, "%.2f 倍", value -> pendingWaterStyleWaveSpeed = (float) value));
        // Noise-wave octave spectrum dials (2026-09-30 去重复 v2).  Each one
        // multiplies its own octave inside the non-periodic noise field:
        // 大涌起伏 = λ≈8.4-block swell shapes, 细波纹理 = λ≈3.7 dominant
        // ripples, 碎浪颗粒 = λ≈1.75 near-field chop.  1.0 = the shipped
        // look, 0.0 flattens that octave entirely.
        this.addOptionRow(cloudSlider("大涌起伏", 0.0, 2.0, 0.05,
                pendingWaterStyleSwellScale, "%.2f 倍", value -> pendingWaterStyleSwellScale = (float) value));
        this.addOptionRow(cloudSlider("细波纹理", 0.0, 2.0, 0.05,
                pendingWaterStyleMidScale, "%.2f 倍", value -> pendingWaterStyleMidScale = (float) value));
        this.addOptionRow(cloudSlider("碎浪颗粒", 0.0, 2.0, 0.05,
                pendingWaterStyleFineScale, "%.2f 倍", value -> pendingWaterStyleFineScale = (float) value));
        // 0.0 = the legacy blue-teal water, 1.0 = full turquoise.  Moves
        // absorption, scatter colour and the scattering amount together, so
        // the water cannot end up in an inconsistent pair.
        this.addOptionRow(cloudSlider("青绿水色", 0.0, 1.0, 0.01,
                pendingWaterStyleTintMix, "%.2f", value -> pendingWaterStyleTintMix = (float) value));
        // Multiplies the absorption coefficients: below 1.0 = clearer water
        // (see the bottom farther down), above 1.0 = murkier.  Clamped wider
        // than the other dials because the useful travel is genuinely large.
        this.addOptionRow(cloudSlider("水体浓度", 0.25, 4.0, 0.05,
                pendingWaterStyleDensityScale, "%.2f 倍", value -> pendingWaterStyleDensityScale = (float) value));
        // Brightness of the sun caustic web on underwater receivers.  The one
        // absolute value in the group: the shader has no natural identity for
        // a brightness multiplier, so the shipped look sits at 2.0.
        // 批次3：水体观感四件套。
        this.addOptionRow(cloudSlider("波峰透光强度", 0, 2, 0.05,
                wpCrestBacklit, "%.2f", value -> wpCrestBacklit = (float)value));
        this.addOptionRow(cloudSlider("焦散色散", 0, 1, 0.01,
                wpCausticDispersion, "%.2f", value -> wpCausticDispersion = (float)value));
        this.addOptionRow(cloudSlider("焦散水深弥散", 0, 1, 0.01,
                wpCausticDepthDiffusion, "%.2f", value -> wpCausticDepthDiffusion = (float)value));
        this.addOptionRow(CycleButton.builder(
                enabled -> Component.literal(enabled ? "开" : "关"), wpPhysicalPhase)
                .withValues(false, true).create(Component.literal("水下物理相位"),
                        (button, value) -> wpPhysicalPhase = value));
        this.addOptionRow(cloudSlider("焦散亮度", 0.0, 4.0, 0.05,
                pendingWaterStyleCausticStrength, "%.2f", value -> pendingWaterStyleCausticStrength = (float) value));
        // 2026-10-20：反射焦散（太阳被水面镜面弹回、照在**朝水的面**上：洞顶、悬垂、
        // 桥底）单独一根杆。原先它直接复用上面那根「焦散亮度」，两者照的面完全
        // 不同（折射 = 水下地板 / 反射 = 天花板），一起调没有意义。拆杆之前反射层借的
        // 就是上面那根（快照 1.3 / 实机 1.1），本杆出厂 4.0 ⇒ 本版默认更亮，正是
        // "不明显"的修法；想回旧强度拖到 1.3。0 = 关。
        // 传递走 waterWaveParams 的原保留 lane（见下面 WaterWaveSettings 那一段）。
        this.addOptionRow(cloudSlider("反射焦散强度", 0.0, 8.0, 0.05,
                waterReflectCausticStrength, "%.2f", value -> waterReflectCausticStrength = (float) value));

        // =================================================================
        // 2026-10-02 水面波面参考重构（HPWater）。
        // 参考的波面 = **波动方程高度场 → 中心差分法线 → XZ 相加 / Y 相乘混合**；
        // 边界是**海绵层吸收 + 障碍物诺伊曼反射**，波源是**带上限的高斯凸起**。
        // 我们保留自己的自然波频谱（远处仍有波，参考的 Demo 只有局部盒子），
        // 把上面这些机制补齐。每组标了"回退旧档"的取值。
        // =================================================================
        // 交互场法线标定：1 = 参考的严格中心差分 1/(2Δ)；0 = 旧档的实机标定系数。
        // 注意它约等于把交互波（游泳环/雨点尾迹）的坡度放大 2.6×。
        this.addOptionRow(cloudSlider("交互波标定", 0.0, 1.0, 0.05,
                waterWaveCalibration, "%.2f", value -> waterWaveCalibration = (float)value));
        this.addOptionRow(cloudSlider("交互波强度", 0.0, 2.0, 0.05,
                waterWaveStrength, "%.2f 倍", value -> waterWaveStrength = (float)value));
        // 波面混合：1 = 参考（两波叠加处更陡更集中）；0 = 旧档直接相加。
        this.addOptionRow(cloudSlider("波面混合", 0.0, 1.0, 0.05,
                waterWaveBlend, "%.2f", value -> waterWaveBlend = (float)value));
        // 海绵层宽度：参考 thickness = 模拟半宽的 10%。它让波在出界前被**吸收**，
        // 而不是撞上软墙反弹（0 = 关 ⇒ 回旧档的圆形幅度淡出）。
        this.addOptionRow(cloudSlider("波场海绵层", 0.0, 0.45, 0.01,
                waterSimSpongeWidth, "%.2f", value -> waterSimSpongeWidth = (float)value));
        // 波源形状：1 = 参考的高斯凸起（拖动/落水是一个平滑包）；0 = 旧档同心环。
        // 2026-10-04：改开/关按钮（同「粉末口径」：数值是布尔语义，滑杆步长 1.0
        // 只让 0/1 可达，着色器也按 >0.5 判）。
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "高斯凸起" : "同心环"),
                        waterSimGaussianSource > 0.5f)
                .withValues(false, true)
                .create(Component.literal("波源形状"),
                        (button, enabled) -> waterSimGaussianSource = enabled ? 1.0f : 0.0f));
        // 阻尼倍率：1 = 与今天一致；>1 波纹更快平息，<1 传得更远。
        this.addOptionRow(cloudSlider("波场阻尼", 0.0, 3.0, 0.05,
                waterSimDampingScale, "%.2f 倍", value -> waterSimDampingScale = (float)value));
        // 波场传播速度（参考 HPWaterWaveEquation 的 _WaveSpeed）：缩放波方程的
        // Laplacian 系数 ⇒ 波纹每秒传得更远/更近。内部把系数顶在 2D 稳定域内。
        this.addOptionRow(cloudSlider("波场传播速度", 0.0, 2.0, 0.05,
                waterSimPropagationSpeed, "%.2f 倍", value -> waterSimPropagationSpeed = (float)value));
        // 2026-10-03 薄层 SSS（参考 HPWater BSDF 的 thinLayerSSS）：
        // 非线性光程混合（0.5 = 参考）、散射能量补偿（2.0 = 参考）、
        // 相位各向异性 g（0.8 = 参考）、总强度（1.0 = 参考口径；0 = 关）。
        this.addOptionRow(cloudSlider("薄层SSS强度", 0.0, 2.0, 0.05,
                waterSssStrength, "%.2f 倍", value -> waterSssStrength = (float)value));
        this.addOptionRow(cloudSlider("SSS非线性光程", 0.0, 1.0, 0.05,
                waterSssNonlinear, "%.2f", value -> waterSssNonlinear = (float)value));
        this.addOptionRow(cloudSlider("SSS能量补偿", 0.0, 4.0, 0.1,
                waterSssBoost, "%.2f", value -> waterSssBoost = (float)value));
        this.addOptionRow(cloudSlider("SSS相位g", 0.0, 0.95, 0.05,
                waterSssPhaseG, "%.2f", value -> waterSssPhaseG = (float)value));

        // =================================================================
        // 2026-10-02 **波形本体**（决定"浪长什么样"，而不只是大小）。
        // 四项都作用在共享波场内部 ⇒ 可见水面与解析焦散自动同源同速。
        // =================================================================
        // 2026-10-03 接靶：这四个旋钮现在驱动**风源/波方程场**（旧解析波面已删除）：
        //   波纹尺度（>1 更细更密）/ 波纹方向（图案整体旋转）/ 波纹幅度（风源注入强度）/
        //   波峰不规则度（波前碎化；(w-1) 偏移 ⇒ 1.0 严格恒等）。
        // 2026-10-04 改名：原「波纹尺度」与下面海浪那根重名（两个完全不同的量：
        // 本源半径倍率 vs 平铺几何尺寸），玩家无法分辨。
        this.addOptionRow(cloudSlider("风源波纹尺度", 0.35, 3.0, 0.05,
        waterWaveLengthScale, "%.2f 倍", value -> waterWaveLengthScale = (float)value));
        // 波纹方向：把风源图案整体旋转（度）。0 = 出厂方向。
        this.addOptionRow(cloudSlider("波纹方向", 0.0, 355.0, 5.0,
        waterWaveDirection, "%.0f 度", value -> waterWaveDirection = (float)value));
                // 波纹幅度（2026-10-03 接靶）：风源注入强度倍率（旧的解析长波已删除）。
        this.addOptionRow(cloudSlider("波纹幅度", 0.0, 2.0, 0.05,
        waterWaveCrestSharpness, "%.2f 倍", value -> waterWaveCrestSharpness = (float)value));
                // 波峰不规则度（2026-10-03 接靶）：风源半径的缓慢抖动倍率（1 = 恒等）。
        this.addOptionRow(cloudSlider("波峰不规则度", 0.0, 2.0, 0.05,
        waterWaveWarpStrength, "%.2f 倍", value -> waterWaveWarpStrength = (float)value));
        this.flushOptionRow();

        // ===== 2026-10-15 ROUTE C 波面后端（陡剖面波列 + 逐层黄金角方位旋转）=====
 // 2026-10-17 起**可见水面**已定档 FFT 涌浪，ROUTE C 整块 `#if 0`；
 // 这两根杆现在只驱动**无描述符消费者**的解析细波层
        //（water_wave_field.glsl 的 #else 臂 = volumetric_light.comp 的水下波闪）。
        // 2026-10-20 移除了同组另两根死设置（域扭曲 / 波长带宽：只有那段 `#if 0`
        // 里的 ROUTE C 读它们，拖了没有任何后果）。
        // 成本 ≈「波数」× 5 次 SFU/像素；「波数」是掉帧时的第一根往下拉的杆。
        this.addOptionRow(cloudSlider("最长波长", 2.0, 8.0, 0.1,
        waterWaveAflScale, "%.1f 格", value -> waterWaveAflScale = (float)value));
        this.addOptionRow(cloudSlider("波数", 1.0, 12.0, 1.0,
        waterWaveAflLayers, "%.0f 根", value -> waterWaveAflLayers = (float)value));
        this.flushOptionRow();

 // ===== 2026-10-15 海浪=====
 // 振幅 = WAVE_HEIGHT_MULT（0 = 静水）；平铺尺度 = 世界平铺周期（格），
        // 越大重复越少（可解析的最细波长随之变粗）；波峰锐化 = 非线性陡化。
 // 2026-10-17 后风固定 normalize(1,1)、无独立涌浪层 ⇒ 删三项死设置。
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.waterFftEnabled)
                .withValues(false, true)
                .create(Component.literal("海浪"),
                        (button, enabled) -> this.waterFftEnabled = enabled));
        this.addOptionRow(cloudSlider("振幅", 0.0, 3.0, 0.05,
        waterFftAmplitude, "%.2f 倍", value -> waterFftAmplitude = (float)value));
        this.addOptionRow(cloudSlider("平铺尺度", 2.0, 128.0, 1.0,
        waterFftTilePeriod, "%.0f 格", value -> waterFftTilePeriod = (float)value));
        this.addOptionRow(cloudSlider("波峰锐化", 0.0, 3.0, 0.05,
        waterFftChoppiness, "%.2f", value -> waterFftChoppiness = (float)value));
        // 2026-10-19 波面细分：细节层（32 格平铺的亚格小波）相对倍率。
        // 0 = 关（退回单层 FFT）；只在宿主烘焙时乘进斜率图 ⇒ 改完 30 Hz 内生效。
        this.addOptionRow(cloudSlider("波浪细节", 0.0, 3.0, 0.05,
        waterFftDetail, "%.2f 倍", value -> waterFftDetail = (float)value));
        this.flushOptionRow();

        // ===== 2026-10-03 后处理（色彩映射 / 自动曝光 / gamma）=====
        this.section = PAGE_POST;

        this.addOptionRow(CycleButton.builder(
                        mode -> postTonemapLabel(mode), this.postTonemapMode)
                .withValues(0, 1, 2, 3, 4, 5, 6)
                .create(Component.literal("色彩映射"),
                        (button, value) -> this.postTonemapMode = value));
        // 2026-10-23 调色调（用户要求）：线性光 R/G/B 通道增益，作用在色彩映射**之前**
        // ⇒ 等效相机白平衡（1 = 逐位恒等）。色温可以靠 R/B 反向拉。
        // 想整体偏冷：R 降 / B 升（例如 0.90 / 0.98 / 1.14）。
        this.addOptionRow(cloudSlider("调色 R 增益", 0.25, 2.5, 0.01,
        postGainR, "%.2f", value -> postGainR = (float)value));
        this.addOptionRow(cloudSlider("调色 G 增益", 0.25, 2.5, 0.01,
        postGainG, "%.2f", value -> postGainG = (float)value));
        this.addOptionRow(cloudSlider("调色 B 增益", 0.25, 2.5, 0.01,
        postGainB, "%.2f", value -> postGainB = (float)value));
        // 自动曝光参与度：0 = 关闭自动曝光（画面只随场景亮度走）；出厂 0.6。
        this.addOptionRow(cloudSlider("自动曝光强度", 0.0, 1.0, 0.05,
        postExposureStrength, "%.2f", value -> postExposureStrength = (float)value));
        // 对比：作用于 Uchimura/GT 曲线的 a（其它映射下此旋钮不生效；
        // 想让所有映射都吃对比，用「色彩分级」里的「显示对比」）。
        this.addOptionRow(cloudSlider("对比度", 0.5, 2.0, 0.05,
        postContrast, "%.2f 倍", value -> postContrast = (float)value));
        this.addOptionRow(cloudSlider("饱和度", 0.0, 2.0, 0.05,
        postSaturation, "%.2f 倍", value -> postSaturation = (float)value));
        this.addOptionRow(cloudSlider("Gamma", 1.8, 2.6, 0.02,
        postGamma, "%.2f", value -> postGamma = (float)value));
        this.flushOptionRow();

        // 2026-10-07 泛光强度（后处理链第 9 个 lane）。0 = 关 = **逐位恒等**
        //（合成着色器整屏早退）。阈值/软膝等观感常量暂时写死在 bloom_prefilter.comp。
        this.addOptionRow(cloudSlider("泛光强度", 0.0, 2.0, 0.05,
        postBloomStrength, "%.2f (0=关)", value -> postBloomStrength = (float)value));
        // 2026-10-17（用户要求）：镜面弹射层数已挪到「材质与画面」页顶部
        // （它不是后处理项，而是 RT 递归深度 —— 见那边同名的注释）。
        // TLAS 实例距离剔除：超距 Section 不进 TLAS（重建+遍历双省）。
        // 0 = 关；远场画面由 DH 合成块负责，但次级射线会失去超距遮挡 ⇒ A/B 定。
        this.addOptionRow(cloudSlider("TLAS剔除距离", 0, 320, 32,
        postTlasCullDistance, "%.0f 格 (0=自适应)", value -> postTlasCullDistance = (int)(float)value));
        // TLAS 实例视锥剔除：视锥外（含背后）且超出外扩边距的 Section 不进 TLAS。
        // 值 = 视锥平面外扩边距（格）；0 = 关。次级射线（太阳阴影/GI/反射）需要
        // 屏幕外遮挡体，边距太小会在屏幕边缘缺树影/山影、反射缺角 ⇒ 从大往小 A/B。
        this.addOptionRow(cloudSlider("TLAS视锥剔除边距", 0, 96, 8,
        postTlasFrustumCullMargin, "%.0f 格 (0=关)", value -> postTlasFrustumCullMargin = (int)(float)value));
        // 远云 RR guide 补写：云是在 rgen 之后才合成进 RR 颜色输入的，guide 却由
        // rgen 写 ⇒ 云像素上 RR 拿到的是云后那个面（通常是天空：深度 0、65504 距离），
        // 于是按"无穷远静态背景"重投影，远处云被糊成一团。打开后，云距离 ≥ 阈值的
        // 像素改用"云面"guide（albedo = 云散射色、roughness = 1、距离/深度 = 云距离）。
        // 0 = 关闭（远处云继续走旧行为）。近云不受影响。
        this.addOptionRow(cloudSlider("远云RR引导距离", 0, 20000, 250,
        postRrFarCloudDistance, "%.0f 格 (0=关)", value -> postRrFarCloudDistance = (int)(float)value));
        // 2026-10-04 S2：云 march 分辨率下限 = 输出分辨率 × pct%（0 = 关 = 旧行为）。
        // 云的 march 分辨率此前恒等于 RR 内部渲染分辨率（超高性能档 707x376 @
        // 2120x1200），地平线掠射带只剩几个像素高 ⇒ 低档位下糊成一条带，只有平衡档
        // （采样多 3 倍）才不糊。>0 时只在降采样档抬云的分辨率、不动 RR 档位 ⇒
        // 代价只加在云 pass 的像素数上，而不是整帧（含 RT）一起升档。
        this.addOptionRow(cloudSlider("云分辨率下限", 0, 100, 5,
        pendingCloudResFloorPercent, "%.0f%%输出 (0=关)",
        value -> pendingCloudResFloorPercent = (int)(float)value));
        // 2026-10-17：v2 的视 march 用固定步长 + 弦长上限，"远段步长加密"与
        // "远淡出随RR档位"两个 v1 专用旋钮在 v2 路径下不参与计算 ⇒ UI 撤销
        // （field / JSON 键保留，存档兼容）。
        // 2026-10-06 远云剥离 RR。开 = 云层在输出分辨率渲染、合成挪到 RR evaluate
        // 之后 ⇒ 远云清晰度不再被 RR 档位的上采样限死（关 = 旧档：云进 RR 颜色
        // 输入）。开着时"云分辨率下限"与"远淡出随RR档位"对远云都已无意义。代价
        // 是云 pass 的像素数按 (输出/render)² 增长。
        this.addOptionRow(CycleButton.builder(
                        enabled -> Component.literal(enabled ? "开" : "关"),
                        this.pendingCloudPostRrComposite)
                .withValues(false, true)
                .create(Component.literal("远云脱离RR"),
                        (button, enabled) -> this.pendingCloudPostRrComposite = enabled));
        this.flushOptionRow();

        // 2026-10-26（用户要求）：色彩分级的**入口**。色彩映射本身留在本页切换，
        // 这里只是往下一个子页 —— 点进去是 24 项逐色调的详细参数（黑白场 / 逐通道
        // Lift-Gamma-Gain / 分离色调 / 自然饱和度 / 褪色 / 暗角 / 色相旋转 …）。
        // 入口占满一整行，且放在本页最后，不和其它旋钮混排。
        // ⚠ 这里直接走 list.addBig（不用 addOptionRow），所以**必须自己判 section** ——
        // addOptionRow 的页过滤在这个调用上不生效，否则每页都会多出一个按钮。
        this.flushOptionRow();
        if(this.section == this.page) {
            this.list.addBig(
                    Button.builder(
                                    Component.literal("▸ 色彩分级 · 详细参数（独立页面）"),
                                    button -> this.openPage(PAGE_COLORGRADE))
                            .width(310)
                            .build());
        }

        // =====================================================
        // 2026-10-26 「色彩分级」子页（从「后处理」页进入，不是 hub 上的独立分类）
        //
        // 内容 = 4 个预设 + 24 项逐色调参数（RTXSettingsConfig.COLOR_GRADE_* 表驱动）：
        //   ① 曝光与黑白场  ② 逐通道 Lift/Gamma/Gain（ASC-CDL）
        //   ③ 分离色调（暗部 / 亮部各推一个色相）④ 氛围（饱和度 / 褪色 / 暗角 / 色相）
        //
        // 这些滑条**拖动即生效**（与「体积云 HP 全参数」同款，不进 pending 草稿，
        // 所以本页不需要按「应用」也能看到变化）；预设同理，只改这 24 项，
        // 色彩映射模式仍由「后处理」页决定（冰岛/格陵兰建议配 AgX）。
        // =====================================================
        this.section = PAGE_COLORGRADE;

        // 预设只改这 24 项，"曲线"（色彩映射模式）仍由上一页决定 —— 冰岛/格陵兰
        // 这两套是照 AgX 调的，配 GT/Uchimura 也能用，只是高光更硬一点。
        this.addSectionHeader("预设（只改分级；色彩映射请在后处理页选，推荐 AgX）");
        this.addOptionRow(Button.builder(
                        Component.literal("预设：冰岛 · 冷寂"),
                        button -> this.applyColorGradePreset(PRESET_ICELAND))
                .width(150).build());
        this.addOptionRow(Button.builder(
                        Component.literal("预设：格陵兰 · 极夜"),
                        button -> this.applyColorGradePreset(PRESET_GREENLAND))
                .width(150).build());
        this.addOptionRow(Button.builder(
                        Component.literal("预设：晨雾 · 苍灰"),
                        button -> this.applyColorGradePreset(PRESET_MIST))
                .width(150).build());
        this.addOptionRow(Button.builder(
                        Component.literal("预设：复位（中性）"),
                        button -> this.applyColorGradePreset(PRESET_NEUTRAL))
                .width(150).build());
        this.flushOptionRow();

        // 24 项：表驱动（RTXSettingsConfig.COLOR_GRADE_LABELS / COLOR_GRADE_RANGES），
        // 拖动立刻 setColorGradeParams + 落盘。
        float[] cgValues = RTXSettingsConfig.getColorGradeSettings().toParamArray();
        for(int cgIndex = 0; cgIndex < RTXSettingsConfig.COLOR_GRADE_LABELS.length; ++cgIndex)
        {
            if(cgIndex == 0)
            {
                this.addSectionHeader("① 曝光与黑白场");
            }
            if(cgIndex == 5)
            {
                this.addSectionHeader("② 逐通道 Lift / Gamma / Gain（ASC-CDL）");
            }
            if(cgIndex == 14)
            {
                this.addSectionHeader("③ 分离色调（暗部 / 亮部各推一个色相）");
            }
            if(cgIndex == 18)
            {
                this.addSectionHeader("④ 氛围（饱和度 / 褪色 / 暗角 / 色相）");
            }

            final int cgSlot = cgIndex;
            final float[] cgRange = RTXSettingsConfig.COLOR_GRADE_RANGES[cgSlot];
            // 步进 >= 1 的值（色相、曝光档）不需要小数位。
            final String cgFormat =
                    cgRange[2] >= 1.0f ? "%.0f"
                            : (cgRange[2] >= 0.01f ? "%.2f" : "%.3f");
            this.addOptionRow(cloudSlider(RTXSettingsConfig.COLOR_GRADE_LABELS[cgSlot],
                    cgRange[0], cgRange[1], cgRange[2], cgValues[cgSlot], cgFormat,
                    value -> {
                        float[] next = RTXSettingsConfig.getColorGradeSettings().toParamArray();
                        next[cgSlot] = (float) value;
                        RTXSettingsConfig.ColorGradeSettings updated =
                                new RTXSettingsConfig.ColorGradeSettings(next);
                        if(!NativeVulkan.isLoaded()
                                || NativeVulkan.setColorGradeParams(updated.toParamArray())) {
                            RTXSettingsConfig.setColorGradeSettings(updated);
                        }
                    }));
        }
        this.flushOptionRow();

        this.section = PAGE_MATERIAL;

        // 2026-10-17（用户要求）：镜面弹射层数入口放这里（原先藏在「后处理」页，
        // 语义也不对：它不是后处理项，而是 RT 的递归深度）。
        //
        // 纯镜面（LabPBR 金属码 > 0.5 且粗糙度 < 0.05）之间的确定性镜像续弹层数。
        // rt_test.rgen 的 mirrorBounceLimit = clamp(UBO, 1, MIRROR_BOUNCE_HARD_CAP=8)，
        // 到顶后用该面自身的直射/自发光兜底 ⇒ 镜厅里表现为"第 N+1 层镜像变成平光"。
        //   1  = 出厂：单次反射（镜厅里看得见两层）
        //   8  = 最深：主命中 + 8 次续弹
        // 代价：每层多一次 10000 距离的全 TLAS 遍历 + 命中处一整套
        // evaluateOpaqueSurfaceRadiance（含太阳阴影射线）⇒ 镜面占屏大时按档位慢慢加。
        this.addOptionRow(cloudSlider("镜面弹射层数", 1, 8, 1,
        postMirrorBounceCount, "%.0f 层", value -> postMirrorBounceCount = (int)(float)value));

        // =====================================================
// No-direct-light ambient brightness
//
// range: 0.00 - 1.00
// step : 0.01
// default: 0.10
// =====================================================

        AbstractSliderButton ambientBrightnessSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingNoDirectLightAmbient
                ) {

                    {
                        this.updateMessage();
                    }


                    @Override
                    protected void updateMessage() {

                        double snapped =
                                Math.round(
                                        this.value * 10000.0
                                ) / 10000.0;


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "无直射光环境亮度: %.4f",
                                                snapped
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {

                        double snapped =
                                Math.round(
                                        this.value * 10000.0
                                ) / 10000.0;


                        this.value =
                                snapped;


                        RTXSettingsScreen.this.pendingNoDirectLightAmbient =
                                (float) snapped;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                ambientBrightnessSlider
        );

        // =====================================================
        // POM parallax depth
        //
        // range: 0.00 - 0.20
        // step : 0.01
        // default: 0.10
        // Applied when the existing Confirm button is pressed.
        // =====================================================

        AbstractSliderButton pomDepthSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingPOMDepth / 0.20
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedDepth() {
                        return Math.round(
                                this.value *
                                        0.20 *
                                        100.0
                        ) / 100.0;
                    }


                    @Override
                    protected void updateMessage() {
                        double depth =
                                this.snappedDepth();


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "视差深度: %.2f",
                                                depth
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double depth =
                                this.snappedDepth();


                        this.value =
                                depth / 0.20;


                        RTXSettingsScreen.this.pendingPOMDepth =
                                (float) depth;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                pomDepthSlider
        );

        // =====================================================
        // 2026-10-18 视差高度场采样：平滑（双线性）⇄ 锐利（单纹素）。
        // 默认平滑（地形现状）。只影响不透明地形 POM 的高度场采样，
        // 位移 / 深度 / 自阴影口径不变。
        // Applied when the existing Confirm button is pressed.
        // =====================================================

        CycleButton<Boolean> pomSamplingButton =
                CycleButton.builder(
                                sharp -> Component.literal(
                                        sharp ? "锐利（单纹素）" : "平滑（双线性）"
                                ),
                                this.pendingPOMSharp
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("视差采样"),
                                (button, sharp) -> this.pendingPOMSharp = sharp
                        );

        this.addOptionRow(
                pomSamplingButton
        );

        // =====================================================
        // 2026-10-25 近场 GI 采样率（性能）
        //
        // 只降 GI 信号的发射频率，像素照常每帧全写 ⇒ 不出锯齿；代价是 GI 方差变大，
        // 由 DLSS-RR 时间累积抹平。实测（室内固定站位）rt 18.04 → 10.79ms。
        // =====================================================

        CycleButton<Integer> giNearRateButton =
                CycleButton.builder(
                                level -> Component.literal(
                                        level == 0 ? "全速"
                                                : (level == 1 ? "半速" : "四分之一")
                                ),
                                this.pendingGiNearRate
                        )
                        .withValues(0, 1, 2)
                        .create(
                                Component.literal("GI 近场采样"),
                                (button, level) -> this.pendingGiNearRate = level
                        );

        this.addOptionRow(
                giNearRateButton
        );

        // =====================================================
        // 2026-10-25 光泽反射按粗糙度降速（性能）
        // =====================================================

        CycleButton<Boolean> glossyRoughLodButton =
                CycleButton.builder(
                                enabled -> Component.literal(
                                        enabled ? "按粗糙度降速" : "全速"
                                ),
                                this.pendingGlossyRoughLod
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("光泽采样"),
                                (button, enabled) -> this.pendingGlossyRoughLod = enabled
                        );

        this.addOptionRow(
                glossyRoughLodButton
        );

        // =====================================================
        // 2026-10-19 树叶次表面散射：太阳背光（仅直射）⇄ 真次表面散射。
        // 太阳背光 = 旧档：只有太阳高于地平线且叶面背光时才加透射，阴影/夜间
        // 不触发。真次表面散射 = 叶面背半球一条透射射线，miss 时按天空辐亮度
        // 加透射光，不依赖太阳直射。Applies on Confirm.
        // =====================================================

        CycleButton<Boolean> subsurfaceButton =
                CycleButton.builder(
                                trueSss -> Component.literal(
                                        trueSss
                                                ? "真次表面散射（天光透射）"
                                                : "太阳背光（仅直射）"
                                ),
                                this.pendingTrueSubsurfaceScattering
                        )
                        .withValues(false, true)
                        .create(
                                Component.literal("树叶次表面散射"),
                                (button, trueSss) ->
                                        this.pendingTrueSubsurfaceScattering = trueSss
                        );

        this.addOptionRow(
                subsurfaceButton
        );

        // =====================================================
        // Local light max distance
        //
        // range: 4 - 30 blocks
        // step : 1
        // default: 30 (the shader's compiled-in VPT_LOCAL_MAX_DISTANCE)
        // Applied when the existing Confirm button is pressed.
        // =====================================================

        AbstractSliderButton localLightDistanceSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        (this.pendingLocalLightMaxDistance - 4.0) / 26.0
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedDistance() {
                        return Math.round(
                                this.value * 26.0 + 4.0
                        );
                    }


                    @Override
                    protected void updateMessage() {
                        double dist =
                                this.snappedDistance();


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "本地光最远照明距离: %d 格",
                                                (int) dist
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double dist =
                                this.snappedDistance();


                        this.value =
                                (dist - 4.0) / 26.0;


                        RTXSettingsScreen.this.pendingLocalLightMaxDistance =
                                (float) dist;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                localLightDistanceSlider
        );

        // =====================================================
        // 本地光源照射强度
        //
        // range: 0.00 - 4.00
        // step : 0.05
        // default: 1.00（= 着色器里的 LOCAL_LIGHT_ENERGY_SCALE，
        //               也就是出厂观感；不动任何已调好的数值）
        // 0.00 = 关掉本地光源。
        // 点已有的 Confirm 按钮才下发。
        // =====================================================

        AbstractSliderButton localLightIntensitySlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingLocalLightIntensity / 4.0
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedIntensity() {
                        return Math.round(
                                this.value * 80.0
                        ) / 20.0;
                    }


                    @Override
                    protected void updateMessage() {
                        double intensity =
                                this.snappedIntensity();


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "本地光源照射强度: %.2fx",
                                                intensity
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double intensity =
                                this.snappedIntensity();


                        this.value =
                                intensity / 4.0;


                        RTXSettingsScreen.this.pendingLocalLightIntensity =
                                (float) intensity;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                localLightIntensitySlider
        );

        // =====================================================
        // 2026-10-07 移动光源强度（手持发光物 + 自带发光的实体）
        //
        // 0 = 关。与「本地光源照射强度」的区别：这个值**不经过 native setter** ——
        // 它随每帧的 uploadDynamicLights(..., intensity) 下发（强度就在动态光源那条
        // SSBO 的头部），所以确认时只落到配置，DynamicLightCapture 每帧从配置读，
        // 改完立刻生效。
        // =====================================================
        final AbstractSliderButton dynamicLightIntensitySlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        this.pendingDynamicLightIntensity / 4.0
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedIntensity() {
                        return Math.round(
                                this.value * 80.0
                        ) / 20.0;
                    }


                    @Override
                    protected void updateMessage() {
                        double intensity =
                                this.snappedIntensity();


                        this.setMessage(
                                Component.literal(
                                        intensity <= 0.0
                                                ? "移动光源: 关"
                                                : String.format(
                                                        java.util.Locale.ROOT,
                                                        "移动光源强度: %.2fx",
                                                        intensity
                                                )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double intensity =
                                this.snappedIntensity();


                        this.value =
                                intensity / 4.0;


                        RTXSettingsScreen.this.pendingDynamicLightIntensity =
                                (float) intensity;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                dynamicLightIntensitySlider
        );

        final boolean currentHDR =
                this.pendingHDR;


        CycleButton<Boolean> hdrButton =
                CycleButton.builder(
                                enabled ->
                                        Component.literal(
                                                enabled
                                                        ? "On"
                                                        : "Off"
                                        ),
                                currentHDR
                        )
                        .withValues(
                                false,
                                true
                        )
                        .create(
                                Component.literal(
                                        "HDR"
                                ),
                                (button, enabled) ->
                                        this.pendingHDR =
                                                enabled
                        );


        this.addOptionRow(
                hdrButton
        );

        // =====================================================
        // RR-7 auto exposure
        //
        // The path tracer has no tone curve, so without this the scene average
        // is whatever the lighting happens to produce and bright ground clips.
        // Shown next to HDR because both are switches on the final display
        // image, and neither needs a restart.
        // =====================================================

        CycleButton<Boolean> autoExposureButton =
                CycleButton.builder(
                                enabled ->
                                        Component.literal(
                                                enabled
                                                        ? "On"
                                                        : "Off"
                                        ),
                                this.pendingAutoExposureEnabled
                        )
                        .withValues(
                                false,
                                true
                        )
                        .create(
                                Component.literal(
                                        "自动曝光"
                                ),
                                (button, enabled) ->
                                        this.pendingAutoExposureEnabled =
                                                enabled
                        );


        this.addOptionRow(
                autoExposureButton
        );


        this.flushOptionRow();

        // =====================================================
        // RR-7 exposure compensation
        //
        // range: -2.0 - +2.0 EV
        // step : 0.1
        // default: 0.0 (= the look the auto exposure was calibrated against)
        // Applied when the Confirm button is pressed.
        // =====================================================

        AbstractSliderButton exposureCompSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        310,
                        20,
                        Component.empty(),
                        (this.pendingAutoExposureCompensation + 2.0) / 4.0
                ) {

                    {
                        this.updateMessage();
                    }


                    private double snappedEV() {
                        return Math.round(
                                (this.value * 4.0 - 2.0) *
                                        10.0
                        ) / 10.0;
                    }


                    @Override
                    protected void updateMessage() {
                        double ev =
                                this.snappedEV();


                        this.setMessage(
                                Component.literal(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "曝光补偿: %+.1f EV",
                                                ev
                                        )
                                )
                        );
                    }


                    @Override
                    protected void applyValue() {
                        double ev =
                                this.snappedEV();


                        this.value =
                                (ev + 2.0) / 4.0;


                        RTXSettingsScreen.this.pendingAutoExposureCompensation =
                                (float) ev;


                        this.updateMessage();
                    }
                };


        this.addOptionRow(
                exposureCompSlider
        );
        this.flushOptionRow();
    }


    private void applyPendingSettings() {

        boolean allApplied =
                true;


        // =====================================================
        // NVIDIA Reflex
        // =====================================================

        final boolean reflexAvailable =
                NativeVulkan.isLoaded()
                        && NativeVulkan.isReflexAvailable();


        if(reflexAvailable)
        {
            if(
                    NativeVulkan.setReflexMode(
                            this.pendingReflexMode
                    )
            )
            {
                RTXSettingsConfig.setReflexMode(
                        this.pendingReflexMode
                );
            }
            else
            {
                this.pendingReflexMode =
                        NativeVulkan.getReflexMode();

                allApplied =
                        false;
            }
        }

        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFGFrameSync(
                        this.pendingFGFrameSync
                ))
        {
            RTXSettingsConfig.setFGFrameSyncEnabled(
                    this.pendingFGFrameSync
            );
        }
        else
        {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFGRefreshCapEnabled(
                        this.pendingFGRefreshCap
                ))
        {
            RTXSettingsConfig.setFGRefreshCapEnabled(
                    this.pendingFGRefreshCap
            );
        }
        else
        {
            allApplied = false;
        }

        // 2026-10-22（D3）：只落盘，不动当前进程 —— 下一次启动时由
        // VulkanInstanceMixin 在 initStreamlineEarly 之前读取并下发。
        RTXSettingsConfig.setSLDevPluginsEnabled(
                this.pendingSLDevPlugins
        );

        // The requested ratio is saved even when the driver cannot reach it yet
        // (no multi-frame unlock): native clamps at apply time and keeps running
        // 2x, and the wish survives until the ceiling rises.
        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFGMultiplier(
                        this.pendingFGMultiplier
                ))
        {
            RTXSettingsConfig.setFGMultiplier(
                    this.pendingFGMultiplier
            );
        }
        else
        {
            this.pendingFGMultiplier =
                    RTXSettingsConfig.getFGMultiplier();

            allApplied = false;
        }

        // The cap is delivered through Reflex, so it lands with Reflex disabled
        // too -- it is just inert there. The label says so rather than hiding it.
        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFGBaseFrameLimit(
                        this.pendingFGBaseFrameLimit
                ))
        {
            RTXSettingsConfig.setFGBaseFrameLimit(
                    this.pendingFGBaseFrameLimit
            );
        }
        else
        {
            this.pendingFGBaseFrameLimit =
                    RTXSettingsConfig.getFGBaseFrameLimit();

            allApplied = false;
        }

        RTXSettingsConfig.setSerEnabled(
                this.pendingSerEnabled
        );

        // 2026-10-22（MV 总开关）：setMotionVectorScaleMode 同时写 g_mvScaleMode 与
        // g_motionVectorsEnabled，并回传夹紧后的值 —— 用返回值回填，界面显示的就是
        // 原生真正采纳的档位。
        if(!NativeVulkan.isLoaded())
        {
            RTXSettingsConfig.setMotionVectorsEnabled(
                    this.pendingMotionVectors
            );
        }
        else
        {
            this.pendingMotionVectors =
                    NativeVulkan.setMotionVectorScaleMode(
                            this.pendingMotionVectors
                    );

            RTXSettingsConfig.setMotionVectorsEnabled(
                    this.pendingMotionVectors
            );
        }

        // Java-only setting: the entity capture reads it when it assigns a
        // texture slot, so it needs no native call.
        RTXSettingsConfig.setEntityArmorMetalEnabled(
                this.pendingArmorMetal
        );


        // =====================================================
        // DLSS Ray Reconstruction
        // =====================================================

        // 与其余设置组保持同一写法：native 未加载时短路成"已应用"，否则
        // setRRMode/getRRMode 会直接抛 UnsatisfiedLinkError（这里曾经是唯一
        // 一个没有 isLoaded() 守卫的设置组）。
        if(
                !NativeVulkan.isLoaded() ||
                        NativeVulkan.setRRMode(
                                this.pendingRRMode
                        )
        )
        {
            RTXSettingsConfig.setRRMode(
                    this.pendingRRMode
            );
        }
        else
        {
            this.pendingRRMode =
                    NativeVulkan.getRRMode();

            allApplied =
                false;
        }

        // 渲染路径恒为 RR：SR 超分入口已从 PAGE_UPSCALER 移除（强依赖 RR）。
        // 这里不再回读 pendingUpscalerMode，而是显式钉死 RR —— 配置文件里可能
        // 残留早先存的 upscalerMode=1(SR)，GUI 已无入口切回来，不钉就会把人锁在
        // SR 路径上，而 SR 档位控件也没了，等于卡在一个调不动的状态。
        // srMode 的那份 options 只在 useSR 时被读取，路径恒 RR 后它不参与渲染，
        // 所以不再下发 setSRMode（JNI / 宿主原子量保留以支持回溯）。
        final boolean upscalerApplied =
                !NativeVulkan.isLoaded() ||
                        NativeVulkan.setUpscalerMode(RTXSettingsConfig.UPSCALER_MODE_RR);

        if(upscalerApplied)
        {
            RTXSettingsConfig.setUpscalerMode(RTXSettingsConfig.UPSCALER_MODE_RR);
        }
        else
        {
            allApplied = false;
        }

        RTXSettingsConfig.PerformanceSettings updatedPerformance =
                new RTXSettingsConfig.PerformanceSettings(
                        this.pendingAoNearSamples,
                        this.pendingAoMidSamples,
                        this.pendingGiBounces,
                        this.pendingSunShadowSamples,
                        this.pendingLocalLightRisCandidates,
                        this.pendingLocalLightAreaSamples,
                        this.pendingWaterReflectionInterval,
                        this.pendingGlossyFullDistance,
                        this.pendingDynamicRtasInterval,
                        this.pendingMaxRtParticles,
                        this.pendingRtSpp,
                        this.pendingVolumetricRestir
                );
        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setPerformanceSettings(updatedPerformance))
        {
            RTXSettingsConfig.setPerformanceSettings(updatedPerformance);
        }
        else
        {
            allApplied = false;
        }

        // Purely Java-side: ParticleRtCapture reads this straight out of the
        // config while it rebuilds the weather lattice, so unlike the
        // native-backed groups there is no call here that can fail.
        RTXSettingsConfig.setRainSettings(
                new RTXSettingsConfig.RainSettings(
                        this.pendingRainOpacity,
                        this.pendingRainStreakLength,
                        this.pendingRainBrightness,
                        this.pendingRainWidth,
                        this.pendingRainDensityScale
                )
        );

        // 同上：雪片设置也是纯 Java 侧。
        RTXSettingsConfig.setSnowSettings(
                new RTXSettingsConfig.SnowSettings(
                        this.pendingSnowSize,
                        this.pendingSnowDensity,
                        this.pendingSnowOpacity,
                        this.pendingSnowBrightness,
                        this.pendingSnowFallSpeed,
                        this.pendingSnowSway
                )
        );

        // The wall model is not Java-side, so this one is voted on the same way
        // the fog and god-ray groups are: push to the shader first and only
        // persist if the native side accepted it.
        RTXSettingsConfig.RainWallSettings updatedRainWall =
                new RTXSettingsConfig.RainWallSettings(
                        this.pendingRainWallEnabled,
                        this.pendingRainWallWetness,
                        this.pendingRainWallDripDensity,
                        this.pendingRainWallDripSpeed,
                        this.pendingRainWallDripSize,
                        this.pendingRainWallDripBump,
                        this.pendingRainWallDistance
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setRainWallSettings(
                updatedRainWall.enabled(),
                updatedRainWall.wetness(),
                updatedRainWall.dripDensity(),
                updatedRainWall.dripSpeed(),
                updatedRainWall.dripSize(),
                updatedRainWall.dripBump(),
                updatedRainWall.distance()
        )) {
            RTXSettingsConfig.setRainWallSettings(updatedRainWall);
        } else {
            allApplied = false;
        }

        // 末地银河旋钮（2026-10-08）同样原生侧应用：先推 JNI，接受才落盘。
        RTXSettingsConfig.GalaxySettings updatedGalaxy =
                new RTXSettingsConfig.GalaxySettings(
                        this.pendingGalaxyScale,
                        this.pendingGalaxyTiltDegrees,
                        this.pendingGalaxyYawDegrees,
                        this.pendingGalaxyMix
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setGalaxySettings(
                updatedGalaxy.scale(),
                updatedGalaxy.tiltDegrees(),
                updatedGalaxy.yawDegrees(),
                updatedGalaxy.mix()
        )) {
            RTXSettingsConfig.setGalaxySettings(updatedGalaxy);
        } else {
            allApplied = false;
        }

        // Water style dials are native-backed the same way the wall block is:
        // the shader reads them from CameraUBO.waterStyleParams every frame.
        RTXSettingsConfig.WaterStyleSettings updatedWaterStyle =
                new RTXSettingsConfig.WaterStyleSettings(
                        this.pendingWaterStyleWaveScale,
                        this.pendingWaterStyleTintMix,
                        this.pendingWaterStyleDensityScale,
                        this.pendingWaterStyleCausticStrength,
                        this.pendingWaterStyleWaveSpeed,
                        this.pendingWaterStyleSwellScale,
                        this.pendingWaterStyleMidScale,
                        this.pendingWaterStyleFineScale
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterStyleSettings(
                updatedWaterStyle.waveScale(),
                updatedWaterStyle.tintMix(),
                updatedWaterStyle.densityScale(),
                updatedWaterStyle.causticStrength(),
                updatedWaterStyle.waveSpeed(),
                updatedWaterStyle.swellScale(),
                updatedWaterStyle.midScale(),
                updatedWaterStyle.fineScale()
        )) {
            RTXSettingsConfig.setWaterStyleSettings(updatedWaterStyle);
        } else {
            allApplied = false;
        }

        // 2026-10-02 水面波面参考重构（HPWater）：两组各自独立应用（不要用 && 串联，
        // 一处失败会短路掉后面的项）。
        RTXSettingsConfig.WaterWaveSettings updatedWaterWave =
                new RTXSettingsConfig.WaterWaveSettings(
                        waterWaveCalibration, waterWaveStrength, waterWaveBlend,
                        waterReflectCausticStrength);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterWaveParams(updatedWaterWave.toParamArray())) {
            RTXSettingsConfig.setWaterWaveSettings(updatedWaterWave);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.WaterSssSettings updatedWaterSss =
                new RTXSettingsConfig.WaterSssSettings(
                        waterSssNonlinear, waterSssBoost, waterSssPhaseG, waterSssStrength);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterSssParams(updatedWaterSss.toParamArray())) {
            RTXSettingsConfig.setWaterSssSettings(updatedWaterSss);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.WaterSimWaveSettings updatedWaterSimWave =
                new RTXSettingsConfig.WaterSimWaveSettings(
                        waterSimSpongeWidth, waterSimGaussianSource,
                        waterSimDampingScale, waterSimPropagationSpeed);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterSimWaveParams(updatedWaterSimWave.toParamArray())) {
            RTXSettingsConfig.setWaterSimWaveSettings(updatedWaterSimWave);
        } else {
            allApplied = false;
        }
        // 2026-10-02 波形本体四件套。
        RTXSettingsConfig.WaterWaveShapeSettings updatedWaterWaveShape =
                new RTXSettingsConfig.WaterWaveShapeSettings(
                        waterWaveLengthScale, waterWaveDirection, waterWaveCrestSharpness,
                        waterWaveWarpStrength);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterWaveShapeParams(updatedWaterWaveShape.toParamArray())) {
            RTXSettingsConfig.setWaterWaveShapeSettings(updatedWaterWaveShape);
        } else {
            allApplied = false;
        }
        // 2026-10-15 ROUTE C 波面后端四件套。
        RTXSettingsConfig.WaterWaveAflSettings updatedWaterAfl =
                new RTXSettingsConfig.WaterWaveAflSettings(
                        waterWaveAflScale, waterWaveAflLayers);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterWaveAflParams(updatedWaterAfl.toParamArray())) {
            RTXSettingsConfig.setWaterWaveAflSettings(updatedWaterAfl);
        } else {
            allApplied = false;
        }
        // 2026-10-03 FFT 频谱海面：开关 + 旋钮，应用后立即生效。
 //（2026-10-17 清理：删「浪速/海浪风向/涌浪强度」三项死设置，
        //   新增「平铺尺度」。）
        RTXSettingsConfig.WaterFftSettings updatedWaterFft =
                new RTXSettingsConfig.WaterFftSettings(
                        waterFftEnabled, waterFftAmplitude, waterFftChoppiness,
                        waterFftTilePeriod, waterFftDetail);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterFftParams(updatedWaterFft.toParamArray())) {
            RTXSettingsConfig.setWaterFftSettings(updatedWaterFft);
        } else {
            allApplied = false;
        }
        // 2026-10-03 后处理：色彩映射 + 参数，应用后立即生效（push 热切换）。
        // TLAS 剔除距离 0 = **自适应**：游戏视距(格) × 1.25（随视距设置联动）。
        int effectiveTlasCull = postTlasCullDistance;
        if(effectiveTlasCull == 0 && this.minecraft != null) {
            effectiveTlasCull = Math.min(512, Math.max(64,
                    this.minecraft.options.renderDistance().get() * 16 * 5 / 4));
        }
        RTXSettingsConfig.PostProcessingSettings updatedPost =
                new RTXSettingsConfig.PostProcessingSettings(
                        postTonemapMode, postExposureStrength, postContrast,
                        postSaturation, postGamma, postMirrorBounceCount,
                        postTlasCullDistance, postTlasFrustumCullMargin,
                        postRrFarCloudDistance);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setPostProcessingParams(
                new float[] {
                    updatedPost.tonemapMode(), updatedPost.exposureStrength(),
                    updatedPost.contrast(), updatedPost.saturation(),
                    updatedPost.gamma(), updatedPost.mirrorBounceCount(),
                    effectiveTlasCull, postTlasFrustumCullMargin,
                    this.postBloomStrength,
                    this.postGainR, this.postGainG, this.postGainB })) {
            RTXSettingsConfig.setPostProcessingSettings(updatedPost);
            // 2026-10-08 泛光强度**不在** PostProcessingSettings record 里（它是后处理链
            // 第 9 个 lane 的独立标量），上面那句落盘因此不覆盖它。此前 setPostBloomStrength
            // 全仓库没有调用点 ⇒ 滑块只对 native 当场生效、从不写盘，重启被
            // RTXSettingsConfig.applyData 的默认值 0 顶掉。跟随同一份 apply 的成功分支落盘。
            RTXSettingsConfig.setPostBloomStrength(this.postBloomStrength);
            // 2026-10-23 调色调增益：与上面同一批 lane，同批落盘。
            RTXSettingsConfig.setPostGains(this.postGainR, this.postGainG, this.postGainB);
        } else {
            allApplied = false;
        }
        // 2026-10-03 远云 RR guide 补写（独立的 push 参数，不在后处理 lane 里）：
        // 0 = 关闭。relaxEnabled 恒 true —— 那是 2026-10-01 的既有功能，这里只搭车。
        // 落盘跟随上面的 updatedPost（同一份 record），这里不再重复 save。
        if(NativeVulkan.isLoaded() &&
                !NativeVulkan.setRrGuideSettings(true, postRrFarCloudDistance)) {
            this.postRrFarCloudDistance = (int)NativeVulkan.getRrFarCloudDistance();
            allApplied = false;
        }
        // 2026-10-04 S2：云 march 分辨率下限（0 = 关 = 旧行为）。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudResFloorPercent(
                this.pendingCloudResFloorPercent)) {
            RTXSettingsConfig.setCloudResFloorPercent(
                    this.pendingCloudResFloorPercent);
        } else {
            this.pendingCloudResFloorPercent =
                    NativeVulkan.getCloudResFloorPercent();
            allApplied = false;
        }
        // 2026-10-04 S3：远段步长加密（1.0 = 旧档，逐位不变）。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudFarStepDensity(
                this.pendingCloudFarStepDensity)) {
            RTXSettingsConfig.setCloudFarStepDensity(
                    this.pendingCloudFarStepDensity);
        } else {
            this.pendingCloudFarStepDensity =
                    NativeVulkan.getCloudFarStepDensity();
            allApplied = false;
        }
        // 2026-10-07：GI 俄罗斯轮盘（1 = 开 = 出厂）。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setGiRoulette(
                this.pendingGiRoulette)) {
            RTXSettingsConfig.setGiRoulette(
                    this.pendingGiRoulette);
        } else {
            this.pendingGiRoulette =
                    NativeVulkan.getGiRoulette();
            allApplied = false;
        }
        // 2026-10-26：直接光采样模式 / 稀疏渲染采样率。与上面同一套路：
        // 切换只写 UBO 的 sparseParams，下一帧生效，不重建资源。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setDirectLightMode(
                this.pendingDirectLightMode)) {
            RTXSettingsConfig.setDirectLightMode(
                    this.pendingDirectLightMode);
        } else {
            this.pendingDirectLightMode =
                    NativeVulkan.getDirectLightMode();
            allApplied = false;
        }
        // 2026-10-28 间接光模式：写入配置后需重启客户端才生效（见 UI 行的说明）。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setIndirectMode(
                this.pendingIndirectMode)) {
            RTXSettingsConfig.setIndirectMode(
                    this.pendingIndirectMode);
        } else {
            this.pendingIndirectMode =
                    NativeVulkan.getIndirectMode();
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setSparseRatePercent(
                this.pendingSparseRatePercent)) {
            RTXSettingsConfig.setSparseRatePercent(
                    this.pendingSparseRatePercent);
        } else {
            this.pendingSparseRatePercent =
                    NativeVulkan.getSparseRatePercent();
            allApplied = false;
        }
        // 2026-10-22：运动矢量已改为引擎内部固定策略，界面不再下发。
        // 由 NativeVulkan 静态初始化时统一设成"像素档 + 只发实体层"。
        // 2026-10-09：运动矢量调试视图（0 = 关 / 1 = 幅值热图 / 2 = 有向色）。
        // 与上面两项同一套路：native 没加载就只落配置；推送失败（理论上不会）则
        // 把 pending 拉回 native 的真值，保证界面显示的永远是实际生效值。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setMotionDebugView(
                this.pendingMotionDebugView)) {
            RTXSettingsConfig.setMotionDebugView(
                    this.pendingMotionDebugView);
        } else {
            this.pendingMotionDebugView =
                    NativeVulkan.getMotionDebugView();
            allApplied = false;
        }
        // 2026-10-07：太阳透射 march 远距简化距离（格；0 = 关 = 固定 6 步）。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setSunMarchFarDistance(
                this.pendingSunMarchFarDistance)) {
            RTXSettingsConfig.setSunMarchFarDistance(
                    this.pendingSunMarchFarDistance);
        } else {
            this.pendingSunMarchFarDistance =
                    NativeVulkan.getSunMarchFarDistance();
            allApplied = false;
        }

        RTXSettingsConfig.CloudHpSettings updatedCloudHp =
                new RTXSettingsConfig.CloudHpSettings(
                        RTXSettingsConfig.getCloudHpSettings().toParamArray());
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudHpParams(updatedCloudHp.toParamArray())) {
            RTXSettingsConfig.setCloudHpSettings(updatedCloudHp);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.CloudSettings updatedClouds =
                new RTXSettingsConfig.CloudSettings(
                        this.pendingCloudEnabled,
                        this.pendingCloudBottom,
                        this.pendingCloudThickness,
                        this.pendingCloudCoverage,
                        this.pendingCloudDensity,
                        this.pendingCloudDetailStrength,
                        this.pendingCloudWindSpeed,
                        this.pendingCloudEvolution,
                        this.pendingCloudQuality,
                        this.pendingCloudSize,
                        this.pendingCloudDistribution,
                        this.pendingCloudBaseFeather,
                        this.pendingCloudSunlightStrength
                );

        // 2026-10-03 远淡出跟随 RR 档位（独立 JNI：不在 setCloudSettings 的参数
        // 列表里，因为它是"云 × 上采样档位"的耦合项，不属于云的观感参数）。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudFadeFollowsRrMode(
                this.pendingCloudFadeFollowsRrMode)) {
            RTXSettingsConfig.setCloudFadeFollowsRrMode(
                    this.pendingCloudFadeFollowsRrMode);
        } else {
            this.pendingCloudFadeFollowsRrMode =
                    RTXSettingsConfig.isCloudFadeFollowsRrMode();
            allApplied = false;
        }
        // 2026-10-06 远云剥离 RR（独立 JNI：改变云 pass 的分辨率与合成时点，不属于
        // 云的观感参数）。关掉即逐字节回到"云进 RR 颜色输入"的旧档。
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudPostRrComposite(
                this.pendingCloudPostRrComposite)) {
            RTXSettingsConfig.setCloudPostRrComposite(
                    this.pendingCloudPostRrComposite);
        } else {
            this.pendingCloudPostRrComposite =
                    RTXSettingsConfig.isCloudPostRrComposite();
            allApplied = false;
        }
        RTXSettingsConfig.CloudMsSettings updatedCloudMs =
                new RTXSettingsConfig.CloudMsSettings(
                        msPhiIntensity, msPhiDepthPow, msPhiDepthBias, msOctaveStrength);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudMsParams(updatedCloudMs.toParamArray())) {
            RTXSettingsConfig.setCloudMsSettings(updatedCloudMs);
        } else {
            allApplied = false;
        }
 // 2026-10-09 平面云。9 个分量一次下发到
        // CameraUBO.highCloudParams 的前 9 个 lane。
        RTXSettingsConfig.PlaneCloudSettings updatedPlaneCloud =
                new RTXSettingsConfig.PlaneCloudSettings(
                        this.pendingPlaneCloudEnabled,
                        this.pendingPlaneCloudHeightOffset,
                        this.pendingPlaneCloudScale,
                        this.pendingPlaneCloudSpeed,
                        this.pendingPlaneCloudDensity,
                        this.pendingPlaneCloudCoverage,
                        Math.round(this.pendingPlaneCloudOctaves),
                        this.pendingPlaneCloudFadeDistance,
                        this.pendingPlaneCloudBrightness);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setPlaneCloudParams(updatedPlaneCloud.toParamArray())) {
            RTXSettingsConfig.setPlaneCloudSettings(updatedPlaneCloud);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudPhiSettings updatedCloudPhi =
                new RTXSettingsConfig.CloudPhiSettings(
                        phiBoundaryConfidence, phiMsBuildScale, phiCompress, phiConeRatio);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudPhiParams(updatedCloudPhi.toParamArray())) {
            RTXSettingsConfig.setCloudPhiSettings(updatedCloudPhi);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudLightSettings updatedCloudLight =
                new RTXSettingsConfig.CloudLightSettings(
                        lightSourceOdScale, lightSourceCurvePow, lightAoUpwardScale,
                        lightSunsetAmbientTint);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudLightParams(updatedCloudLight.toParamArray())) {
            RTXSettingsConfig.setCloudLightSettings(updatedCloudLight);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudShapeSettings updatedCloudShape =
                new RTXSettingsConfig.CloudShapeSettings(
                        shapeBottomSmoothHeight, shapeBottomSmoothPow, shapeTwoTierThreshold);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudShapeParams(updatedCloudShape.toParamArray())) {
            RTXSettingsConfig.setCloudShapeSettings(updatedCloudShape);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudMoonSettings updatedCloudMoon =
                new RTXSettingsConfig.CloudMoonSettings(moonStrength, moonPhase);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudMoonParams(updatedCloudMoon.toParamArray())) {
            RTXSettingsConfig.setCloudMoonSettings(updatedCloudMoon);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudShape2Settings updatedCloudShape2 =
                new RTXSettingsConfig.CloudShape2Settings(
                        shapeDetailScale, shapeDetailContrast, shapeProfileCurve);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudShape2Params(updatedCloudShape2.toParamArray())) {
            RTXSettingsConfig.setCloudShape2Settings(updatedCloudShape2);
        } else {
            allApplied = false;
        }
        // 2026-10-02 参考重构 #2~#7：五组各自独立应用（与上面同范式；注意不要用
        // && 串联 —— 一处失败会短路掉后面的项，那正是之前 SR 档位被静默吞掉的原因）。
        RTXSettingsConfig.CloudAmbientSettings updatedCloudAmbient =
                new RTXSettingsConfig.CloudAmbientSettings(
                        ambTopMul, ambBottomMul, ambSunSide, ambHueFollow);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudAmbientParams(updatedCloudAmbient.toParamArray())) {
            RTXSettingsConfig.setCloudAmbientSettings(updatedCloudAmbient);
        } else {
            allApplied = false;
        }
        // 2026-10-14 日落天光修正。
        RTXSettingsConfig.CloudSkyHueSettings updatedCloudSkyHue =
                new RTXSettingsConfig.CloudSkyHueSettings(skyHueCool, skyHueEnergy,
                        skyDirStrength, ambientUnify);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSkyHueParams(updatedCloudSkyHue.toParamArray())) {
            RTXSettingsConfig.setCloudSkyHueSettings(updatedCloudSkyHue);
        } else {
            allApplied = false;
        }
        // 2026-10-15 云地面反射与视觉回退项。
        RTXSettingsConfig.CloudPhysicsSettings updatedCloudPhysics =
                new RTXSettingsConfig.CloudPhysicsSettings(cloudGroundAlbedo,
                        cloudGroundBounce, cloudSunlightResidual, cloudPhaseFloor);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudPhysicsParams(updatedCloudPhysics.toParamArray())) {
            RTXSettingsConfig.setCloudPhysicsSettings(updatedCloudPhysics);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudMs2Settings updatedCloudMs2 =
                new RTXSettingsConfig.CloudMs2Settings(
                        ms2ScatterTint, ms2Octaves, ms2Attenuation, ms2Eccentricity);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudMs2Params(updatedCloudMs2.toParamArray())) {
            RTXSettingsConfig.setCloudMs2Settings(updatedCloudMs2);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudLight2Settings updatedCloudLight2 =
                new RTXSettingsConfig.CloudLight2Settings(
                        l2SunPathReddening, l2AtmosphereExtinction, l2PowderMode);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudLight2Params(updatedCloudLight2.toParamArray())) {
            RTXSettingsConfig.setCloudLight2Settings(updatedCloudLight2);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudProfileSettings updatedCloudProfile =
                new RTXSettingsConfig.CloudProfileSettings(profileType, profileTypeSpread);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudProfileParams(updatedCloudProfile.toParamArray())) {
            RTXSettingsConfig.setCloudProfileSettings(updatedCloudProfile);
        } else {
            allApplied = false;
        }
        RTXSettingsConfig.CloudDetailSettings updatedCloudDetail =
                new RTXSettingsConfig.CloudDetailSettings(
                        wispyStrength, wispyEdgeWidth, wispyReach, wispyTopHeight);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudDetailParams(updatedCloudDetail.toParamArray())) {
            RTXSettingsConfig.setCloudDetailSettings(updatedCloudDetail);
        } else {
            allApplied = false;
        }
 // 2026-10-10（#3/#4 云时域）。
        RTXSettingsConfig.CloudTemporalSettings updatedCloudTemporal =
                new RTXSettingsConfig.CloudTemporalSettings(
                        cloudSkyVisibilityStrength, cloudSkyVisibilitySteps, cloudTemporalDampStrength,
                        cloudBottomOcclusion);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudTemporalParams(updatedCloudTemporal.toParamArray())) {
            RTXSettingsConfig.setCloudTemporalSettings(updatedCloudTemporal);
        } else {
            allApplied = false;
        }
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSettings(
                updatedClouds.enabled(),
                updatedClouds.bottom(),
                updatedClouds.thickness(),
                updatedClouds.coverage(),
                updatedClouds.density(),
                updatedClouds.detailStrength(),
                updatedClouds.windSpeed(),
                updatedClouds.evolution(),
                updatedClouds.quality(),
                updatedClouds.size(),
                updatedClouds.distribution(),
                updatedClouds.baseFeather(),
                updatedClouds.sunlightStrength()
        )) {
            RTXSettingsConfig.setCloudSettings(updatedClouds);
        } else {
            allApplied = false;
        }

        // 2026-10-14 修复：这一处的实参表还停在**旧雾模型**（amount / centerHeight /
        // thickness / scale / speed / detailLayers —— 那六项随体积雾重写一起删除），
        // 于是 Java 侧编译失败（"找不到符号: 变量 pendingFogAmount" 等 6 条）。
        // 改成与新记录的 11 个分量逐位对应，顺序与紧接着
        // setVolumetricFogSettings(...) 的取值顺序完全一致。
        RTXSettingsConfig.VolumetricFogSettings updatedFog =
                new RTXSettingsConfig.VolumetricFogSettings(
                        this.pendingFogEnabled,
                        this.pendingFogDensity,
                        this.pendingFogHeight,
                        this.pendingFogScaleHeight,
                        this.pendingFogAnisotropy,
                        this.pendingFogSunStrength,
                        this.pendingFogAmbientStrength,
                        this.pendingFogMaxDistance,
                        this.pendingFogStartDistance,
                        this.pendingFogSunSteps,
                        this.pendingFogRainBoost
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setVolumetricFogSettings(
                updatedFog.enabled(),
                updatedFog.density(),
                updatedFog.height(),
                updatedFog.scaleHeight(),
                updatedFog.anisotropy(),
                updatedFog.sunStrength(),
                updatedFog.ambientStrength(),
                updatedFog.maxDistance(),
                updatedFog.startDistance(),
                updatedFog.sunSteps(),
                updatedFog.rainBoost()
        )) {
            RTXSettingsConfig.setVolumetricFogSettings(updatedFog);
        } else {
            allApplied = false;
        }

        // 2026-10-23 雨天地面雾浪（独立记录 + 独立 native 入口）。
        RTXSettingsConfig.FogWispSettings updatedFogWisp =
                new RTXSettingsConfig.FogWispSettings(
                        this.pendingFogWispEnabled,
                        this.pendingFogWispStrength,
                        this.pendingFogWispWavelength,
                        this.pendingFogWispSpeed
                );
        if(!NativeVulkan.isLoaded() ||
                NativeVulkan.setFogWispParams(updatedFogWisp.toParamArray())) {
            RTXSettingsConfig.setFogWispSettings(updatedFogWisp);
        } else {
            allApplied = false;
        }
        // 2026-10-14：fogExtras（彩虹 / 闪电 / 晨雾两项）随体积雾重写一并停用 ——
        // 那四项在旧实现里只作用于雾的介质，现在雾没有噪声层可以挂它们。
        // 记录与 native 入口暂留（C++ 侧清理时一起删）。

        // 2026-10-10 借鉴（#1 云后空气透视 / #2 地面遮挡衰减）。
        RTXSettingsConfig.CloudAerialSettings updatedCloudAerial =
                new RTXSettingsConfig.CloudAerialSettings(
                        cloudAerialStrength,
                        groundAerialStrength,
                        groundAerialOcclusion);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudAerialParams(updatedCloudAerial.toParamArray())) {
            RTXSettingsConfig.setCloudAerialSettings(updatedCloudAerial);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.VolumetricLightSettings updatedGodRays =
                new RTXSettingsConfig.VolumetricLightSettings(
                        this.pendingGodRayEnabled,
                        this.pendingGodRaySteps,
                        this.pendingGodRayOcclusionSamples,
                        this.pendingGodRayIntensity,
                        this.pendingGodRayReprojection
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setVolumetricLightSettings(
                updatedGodRays.enabled(),
                updatedGodRays.steps(),
                updatedGodRays.occlusionSamples(),
                updatedGodRays.intensity()
        )) {
            RTXSettingsConfig.setVolumetricLightSettings(updatedGodRays);
            // 2026-10-04（P1）：时间重投影权重走独立入口（cloudProfileParams.z）。
            if(NativeVulkan.isLoaded() &&
                    !NativeVulkan.setGodRayReprojection(updatedGodRays.reprojection())) {
                allApplied = false;
            }
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.SunVisualSettings updatedSunVisuals =
                new RTXSettingsConfig.SunVisualSettings(
                        this.pendingSunDiskLuminance,
                        this.pendingSunHaloStrength,
                        this.pendingSunAngularDiameterDegrees
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setSunVisualSettings(
                updatedSunVisuals.diskLuminance(),
                updatedSunVisuals.haloStrength(),
                updatedSunVisuals.angularDiameterDegrees()
        )) {
            RTXSettingsConfig.setSunVisualSettings(updatedSunVisuals);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.AtmosphereSettings updatedAtmosphere =
                new RTXSettingsConfig.AtmosphereSettings(
                        this.pendingStarIntensity,
                        this.pendingSunTintStrength,
                        this.pendingPhysicalSkyEnabled,
                        this.pendingScatterIntensity,
                        this.pendingOzoneStrength
                );
        if(!NativeVulkan.isLoaded() || NativeVulkan.setAtmosphereSettings(
                updatedAtmosphere.starIntensity(),
                updatedAtmosphere.sunTintStrength(),
                updatedAtmosphere.physicalSkyEnabled(),
                updatedAtmosphere.scatterIntensity(),
                updatedAtmosphere.ozoneStrength()
        )) {
            RTXSettingsConfig.setAtmosphereSettings(updatedAtmosphere);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setPowderStrength(
                this.pendingPowderStrength
        )) {
            RTXSettingsConfig.setPowderStrength(
                    this.pendingPowderStrength
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setAuroraBrightness(
                this.pendingAuroraBrightness
        )) {
            RTXSettingsConfig.setAuroraBrightness(
                    this.pendingAuroraBrightness
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setAuroraFlowSpeed(
                this.pendingAuroraFlowSpeed
        )) {
            RTXSettingsConfig.setAuroraFlowSpeed(
                    this.pendingAuroraFlowSpeed
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSunForwardScattering(
                this.pendingCloudSunForwardScattering
        )) {
            RTXSettingsConfig.setCloudSunForwardScattering(
                    this.pendingCloudSunForwardScattering
            );
        } else {
            allApplied = false;
        }


        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSunReddening(
                this.pendingCloudSunReddening
        )) {
            RTXSettingsConfig.setCloudSunReddening(
                    this.pendingCloudSunReddening
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudTopStretch(
                this.pendingCloudTopStretch
        )) {
            RTXSettingsConfig.setCloudTopStretch(
                    this.pendingCloudTopStretch
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudDetileStrength(
                this.pendingCloudDetileStrength
        )) {
            RTXSettingsConfig.setCloudDetileStrength(
                    this.pendingCloudDetileStrength
            );
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudType(this.pendingCloudType)) {
            RTXSettingsConfig.setCloudType(this.pendingCloudType);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudErosionScale(
                this.pendingCloudErosionScale
        )) {
            RTXSettingsConfig.setCloudErosionScale(this.pendingCloudErosionScale);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudWeatherScale(
                this.pendingCloudWeatherScale
        )) {
            RTXSettingsConfig.setCloudWeatherScale(this.pendingCloudWeatherScale);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudSilver(this.pendingCloudSilver)) {
            RTXSettingsConfig.setCloudSilver(this.pendingCloudSilver);
        } else {
            allApplied = false;
        }

        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudViewDistance(
                this.pendingCloudViewDistance
        )) {
            RTXSettingsConfig.setCloudViewDistance(this.pendingCloudViewDistance);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.CloudMarchSettings updatedCloudMarch =
                new RTXSettingsConfig.CloudMarchSettings(
                        msAdaptiveLightRange, msTwoTierStep, msMeanReproject,
                        msFarFadeDistance);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setCloudMarchParams(updatedCloudMarch.toParamArray())) {
            RTXSettingsConfig.setCloudMarchSettings(updatedCloudMarch);
        } else {
            allApplied = false;
        }

        RTXSettingsConfig.WaterParamsSettings updatedWaterParams =
                new RTXSettingsConfig.WaterParamsSettings(
                        wpCrestBacklit, wpCausticDispersion, wpCausticDepthDiffusion,
                        wpPhysicalPhase);
        if(!NativeVulkan.isLoaded() || NativeVulkan.setWaterParams(updatedWaterParams.toParamArray())) {
            RTXSettingsConfig.setWaterParamsSettings(updatedWaterParams);
        } else {
            allApplied = false;
        }


        // =====================================================
        // No-direct-light ambient
        // =====================================================

        if(
                NativeVulkan.setNoDirectLightAmbient(
                        this.pendingNoDirectLightAmbient
                )
        )
        {
            RTXSettingsConfig.setNoDirectLightAmbient(
                    this.pendingNoDirectLightAmbient
            );
        }
        else
        {
            this.pendingNoDirectLightAmbient =
                    NativeVulkan.getNoDirectLightAmbient();

            allApplied =
                    false;
        }



        // =====================================================
        // POM parallax depth
        // =====================================================

        if(
                NativeVulkan.setPOMDepth(
                        this.pendingPOMDepth
                )
        )
        {
            RTXSettingsConfig.setPOMDepth(
                    this.pendingPOMDepth
            );
        }
        else
        {
            this.pendingPOMDepth =
                    NativeVulkan.getPOMDepth();

            allApplied =
                    false;
        }

        // =====================================================
        // 2026-10-18 视差高度场采样：平滑（双线性）⇄ 锐利（单纹素）。
        // =====================================================

        if(
                NativeVulkan.setPOMSharp(
                        this.pendingPOMSharp
                )
        )
        {
            RTXSettingsConfig.setPOMSharp(
                    this.pendingPOMSharp
            );
        }
        else
        {
            this.pendingPOMSharp =
                    NativeVulkan.getPOMSharp();

            allApplied =
                    false;
        }

        // =====================================================
        // 2026-10-25 两个性能档（会话内生效，不写配置）
        // =====================================================

        if(
                !NativeVulkan.setGiNearRate(
                        this.pendingGiNearRate
                )
        )
        {
            this.pendingGiNearRate =
                    NativeVulkan.getGiNearRate();

            allApplied =
                    false;
        }

        if(
                !NativeVulkan.setGlossyRoughLod(
                        this.pendingGlossyRoughLod
                )
        )
        {
            this.pendingGlossyRoughLod =
                    NativeVulkan.getGlossyRoughLod();

            allApplied =
                    false;
        }

        // =====================================================
        // 2026-10-22 UE 风格体积云八项（索引同 NativeVulkan.setUeCloudParam）
        // =====================================================

        // 2026-10-22：这里必须和其它项一样带 isLoaded() 短路。
        // 曾经不带，于是在宿主侧还没实现 JNI 的那一版里，第一次调用就抛
        // UnsatisfiedLinkError —— apply() 从这一行直接中断，**后面所有设置都不提交**
        // （用户看到的就是"云那些设置怎么调都不动"）。宿主实现已补齐
        // （VulkanCore.cpp 的 Java_..._setUeCloudParam），这里再上一道保险：
        // DLL 没加载时退化成"只存配置"，不再把整页设置带崩。
        for(int i = 0; i < RTXSettingsConfig.UE_CLOUD_PARAM_COUNT; i++) {

            if(
                    !NativeVulkan.isLoaded()
                            || NativeVulkan.setUeCloudParam(
                                    i,
                                    this.pendingUeCloud[i]
                            )
            )
            {
                RTXSettingsConfig.setUeCloud(
                        i,
                        this.pendingUeCloud[i]
                );
            }
            else
            {
                this.pendingUeCloud[i] =
                        NativeVulkan.getUeCloudParam(i);

                allApplied =
                        false;
            }
        }

        // =====================================================
        // 2026-10-19 树叶次表面散射：太阳背光（旧档）⇄ 真次表面散射。
        // =====================================================

        if(
                NativeVulkan.setSSSMode(
                        this.pendingTrueSubsurfaceScattering
                )
        )
        {
            RTXSettingsConfig.setTrueSubsurfaceScattering(
                    this.pendingTrueSubsurfaceScattering
            );
        }
        else
        {
            this.pendingTrueSubsurfaceScattering =
                    NativeVulkan.getSSSMode();

            allApplied =
                    false;
        }

        // =====================================================
        // Local light max distance
        // =====================================================

        if(
                NativeVulkan.setLocalLightMaxDistance(
                        this.pendingLocalLightMaxDistance
                )
        )
        {
            RTXSettingsConfig.setLocalLightMaxDistance(
                    this.pendingLocalLightMaxDistance
            );
        }
        else
        {
            this.pendingLocalLightMaxDistance =
                    NativeVulkan.getLocalLightMaxDistance();

            allApplied =
                    false;
        }

        // =====================================================
        // 本地光源照射强度
        // =====================================================

        if(
                NativeVulkan.setLocalLightIntensity(
                        this.pendingLocalLightIntensity
                )
        )
        {
            RTXSettingsConfig.setLocalLightIntensity(
                    this.pendingLocalLightIntensity
            );
        }
        else
        {
            this.pendingLocalLightIntensity =
                    NativeVulkan.getLocalLightIntensity();

            allApplied =
                    false;
        }

        // =====================================================
        // 2026-10-07 移动光源强度（0 = 关）
        //
        // 没有 native setter：这个值随每帧的 uploadDynamicLights(..., intensity) 下发
        //（强度就在动态光源那条 SSBO 的头部），所以这里只落到配置 ——
        // DynamicLightCapture 每帧从配置读，确认后立刻生效。
        // =====================================================

        try
        {
            RTXSettingsConfig.setDynamicLightIntensity(
                    this.pendingDynamicLightIntensity
            );
        }
        catch(IllegalArgumentException invalidDynamicLightIntensity)
        {
            this.pendingDynamicLightIntensity =
                    RTXSettingsConfig.getDynamicLightIntensity();

            allApplied =
                    false;
        }

        // =====================================================
        // HDR
        //
        // The commit also reaches the native display transform.  The HDR10
        // format/colour space is only read when the swapchain is rebuilt, so
        // flipping this marks the surface dirty for a reconfigure.
        // =====================================================

        final boolean hdrChanged =
                RTXSettingsConfig.isHDREnabled() !=
                        this.pendingHDR;

        if(NativeVulkan.isLoaded())
        {
            NativeVulkan.setHDREnabledNative(
                    this.pendingHDR
            );

            if(hdrChanged)
            {
                NativeVulkan.hdrSwapchainDirty =
                        true;
            }
        }

        RTXSettingsConfig.setHDREnabled(
                this.pendingHDR
        );

        // =====================================================
        // RR-7 auto exposure
        //
        // Neither value touches the swapchain, so there is nothing to mark
        // dirty here -- the frame reads both on the same thread that commits
        // this.  The enable switch cannot fail; the compensation can, so it is
        // read back from native and reported the way the other sliders are.
        // Turning it off does not freeze the exposure where it stands: the
        // native side drives it back to neutral, so "off" matches the build
        // from before the feature existed.
        // =====================================================

        if(NativeVulkan.isLoaded())
        {
            NativeVulkan.setAutoExposureEnabledNative(
                    this.pendingAutoExposureEnabled
            );

            if(!NativeVulkan.setAutoExposureCompensationNative(
                    this.pendingAutoExposureCompensation
            ))
            {
                this.pendingAutoExposureCompensation =
                        NativeVulkan.getAutoExposureCompensation();

                allApplied =
                        false;
            }
        }

        RTXSettingsConfig.setAutoExposureEnabled(
                this.pendingAutoExposureEnabled
        );

        RTXSettingsConfig.setAutoExposureCompensation(
                this.pendingAutoExposureCompensation
        );


        System.out.println(
                allApplied
                        ? "[RTX SETTINGS] Confirmed"
                        : "[RTX SETTINGS] Confirmed with native failure"
        );

        // 2026-10-23：本方法里的 setter 只置脏，这里统一落盘一次
        // （此前每个 setter 各自 save()，一次「确认」约 80 次整文件重写）。
        RTXSettingsConfig.flush();
    }

    private static Component qualityLabel(int mode) {
        return switch(mode) {
            case RTXSettingsConfig.RR_MODE_PERFORMANCE -> Component.literal("Performance");
            case RTXSettingsConfig.RR_MODE_BALANCED -> Component.literal("Balanced");
            case RTXSettingsConfig.RR_MODE_QUALITY -> Component.literal("Quality");
            case RTXSettingsConfig.RR_MODE_DLAA -> Component.literal("DLAA");
            case RTXSettingsConfig.RR_MODE_ULTRA_PERFORMANCE -> Component.literal("Ultra Performance");
            default -> Component.literal("Quality");
        };
    }

    private CycleButton<Integer> performanceButton(
            String label,
            int current,
            List<Integer> values,
            IntConsumer setter
    ) {
        return CycleButton.builder(
                        value -> Component.literal(
                                value == 5
                                        ? "关闭"
                                        : value == 0 ? "Off" : Integer.toString(value)
                        ),
                        current
                )
                .withValues(values)
                .create(
                        Component.literal(label),
                        (button, value) -> setter.accept(value)
                );
    }

    private void loadCloudSettings(RTXSettingsConfig.CloudSettings clouds) {
        this.pendingCloudEnabled = clouds.enabled();
        this.pendingCloudBottom = clouds.bottom();
        this.pendingCloudThickness = clouds.thickness();
        this.pendingCloudCoverage = clouds.coverage();
        this.pendingCloudDensity = clouds.density();
        this.pendingCloudDetailStrength = clouds.detailStrength();
        this.pendingCloudWindSpeed = clouds.windSpeed();
        this.pendingCloudEvolution = clouds.evolution();
        this.pendingCloudQuality = clouds.quality();
        this.pendingCloudSize = clouds.size();
        this.pendingCloudDistribution = clouds.distribution();
        this.pendingCloudBaseFeather = clouds.baseFeather();
        this.pendingCloudSunlightStrength = clouds.sunlightStrength();
    }

    private void loadCloudMsSettings(RTXSettingsConfig.CloudMsSettings ms) {
        this.msPhiIntensity = ms.phiIntensity();
        this.msPhiDepthPow = ms.phiDepthPow();
        this.msPhiDepthBias = ms.phiDepthBias();
        this.msOctaveStrength = ms.octaveStrength();
    }

    private void loadCloudPhiSettings(RTXSettingsConfig.CloudPhiSettings phi) {
        this.phiBoundaryConfidence = phi.boundaryConfidence();
        this.phiMsBuildScale = phi.msBuildScale();
        this.phiCompress = phi.compress();
        this.phiConeRatio = phi.coneRatio();
    }

    private void loadCloudLightSettings(RTXSettingsConfig.CloudLightSettings light) {
        this.lightSourceOdScale = light.sourceOdScale();
        this.lightSourceCurvePow = light.sourceCurvePow();
        this.lightAoUpwardScale = light.aoUpwardScale();
        this.lightSunsetAmbientTint = light.sunsetAmbientTint();
    }

    private void loadCloudShapeSettings(RTXSettingsConfig.CloudShapeSettings shape) {
        this.shapeBottomSmoothHeight = shape.bottomSmoothHeight();
        this.shapeBottomSmoothPow = shape.bottomSmoothPow();
        this.shapeTwoTierThreshold = shape.twoTierThreshold();
    }

    private void loadCloudMoonSettings(RTXSettingsConfig.CloudMoonSettings moon) {
        this.moonStrength = moon.moonStrength();
        this.moonPhase = moon.moonPhase();
    }

    private void loadCloudShape2Settings(RTXSettingsConfig.CloudShape2Settings shape2) {
        this.shapeDetailScale = shape2.detailScale();
        this.shapeDetailContrast = shape2.detailContrast();
        this.shapeProfileCurve = shape2.profileCurve();
    }

    private void loadCloudAmbientSettings(RTXSettingsConfig.CloudAmbientSettings ambient) {
        this.ambTopMul = ambient.topMultiplier();
        this.ambBottomMul = ambient.bottomMultiplier();
        this.ambSunSide = ambient.sunSideStrength();
        this.ambHueFollow = ambient.hueFollow();
    }

    private void loadCloudSkyHueSettings(RTXSettingsConfig.CloudSkyHueSettings skyHue) {
        this.skyHueCool = skyHue.skyHueCool();
        this.skyHueEnergy = skyHue.skyHueEnergy();
        this.skyDirStrength = skyHue.skyDirStrength();
        this.ambientUnify = skyHue.ambientUnify();
    }

    private void loadCloudPhysicsSettings(RTXSettingsConfig.CloudPhysicsSettings physics) {
        this.cloudGroundAlbedo = physics.groundAlbedo();
        this.cloudGroundBounce = physics.groundBounce();
        this.cloudSunlightResidual = physics.sunlightResidual();
        this.cloudPhaseFloor = physics.phaseFloor();
    }

    private void loadCloudMs2Settings(RTXSettingsConfig.CloudMs2Settings ms2) {
        this.ms2ScatterTint = ms2.scatterTint();
        this.ms2Octaves = ms2.octaves();
        this.ms2Attenuation = ms2.attenuation();
        this.ms2Eccentricity = ms2.eccentricity();
    }

    private void loadCloudLight2Settings(RTXSettingsConfig.CloudLight2Settings light2) {
        this.l2SunPathReddening = light2.sunPathReddening();
        this.l2AtmosphereExtinction = light2.atmosphereExtinction();
        this.l2PowderMode = light2.powderMode();
    }

    private void loadCloudProfileSettings(RTXSettingsConfig.CloudProfileSettings profile) {
        this.profileType = profile.cloudType();
        this.profileTypeSpread = profile.typeSpread();
    }

    private void loadCloudDetailSettings(RTXSettingsConfig.CloudDetailSettings detail) {
        this.wispyStrength = detail.wispyStrength();
        this.wispyEdgeWidth = detail.wispyEdgeWidth();
        this.wispyReach = detail.wispyReach();
        this.wispyTopHeight = detail.wispyTopHeight();
    }

 // 2026-10-10（#3 天光自遮挡 / #4 时域速度抑制）。
    private void loadCloudTemporalSettings(RTXSettingsConfig.CloudTemporalSettings temporal) {
        this.cloudSkyVisibilityStrength = temporal.skyVisibilityStrength();
        this.cloudSkyVisibilitySteps = temporal.skyVisibilitySteps();
        this.cloudTemporalDampStrength = temporal.temporalDampStrength();
        this.cloudBottomOcclusion = temporal.bottomOcclusion();
    }

 // 2026-10-10（#5 晨雾 / #6 彩虹 + 闪电）。
    private void loadFogExtrasSettings(RTXSettingsConfig.FogExtrasSettings extras) {
        this.fogExtrasDynamicLightStrength = extras.dynamicLightStrength();
        this.fogExtrasGroundAnchorStrength = extras.groundAnchorStrength();
        this.fogExtrasSkyAmbientStrength = extras.skyAmbientStrength();
        this.fogExtrasTemporalJitterStrength = extras.temporalJitterStrength();
    }

    // 2026-10-10 借鉴（#1 云后空气透视 / #2 地面遮挡衰减）。
    private void loadCloudAerialSettings(RTXSettingsConfig.CloudAerialSettings aerial) {
        this.cloudAerialStrength = aerial.cloudAerialStrength();
        this.groundAerialStrength = aerial.groundAerialStrength();
        this.groundAerialOcclusion = aerial.groundAerialOcclusion();
    }

    private void loadCloudMarchSettings(RTXSettingsConfig.CloudMarchSettings m) {
        this.msAdaptiveLightRange = m.adaptiveLightRange();
        this.msFarFadeDistance = m.farFadeDistance();
        this.msTwoTierStep = m.twoTierStep();
        this.msMeanReproject = m.meanDepthReproject();
    }

    private void loadWaterParamsSettings(RTXSettingsConfig.WaterParamsSettings wp) {
        this.wpCrestBacklit = wp.crestBacklit();
        this.wpCausticDispersion = wp.causticDispersion();
        this.wpCausticDepthDiffusion = wp.causticDepthDiffusion();
        this.wpPhysicalPhase = wp.physicalPhase();
    }

    private void loadWaterWaveSettings(RTXSettingsConfig.WaterWaveSettings wave) {
        this.waterWaveCalibration = wave.calibration();
        this.waterWaveStrength = wave.strength();
        this.waterWaveBlend = wave.blend();
        this.waterReflectCausticStrength = wave.reflectCausticStrength();
    }

    private void loadWaterSimWaveSettings(RTXSettingsConfig.WaterSimWaveSettings sim) {
        this.waterSimSpongeWidth = sim.spongeWidth();
        this.waterSimGaussianSource = sim.gaussianSource();
        this.waterSimDampingScale = sim.dampingScale();
        this.waterSimPropagationSpeed = sim.propagationSpeed();
    }

    private void loadWaterFftSettings(RTXSettingsConfig.WaterFftSettings fft) {
        this.waterFftEnabled = fft.enabled();
        this.waterFftAmplitude = fft.amplitude();
        this.waterFftChoppiness = fft.choppiness();
        this.waterFftTilePeriod = fft.tilePeriod();
        this.waterFftDetail = fft.detail();
    }

    // =====================================================
    // 2026-10-26 调色预设（「后处理 → 色彩分级」子页顶部的四个按钮）
    //
    // **只改色彩分级那 24 项**（setColorGradeParams + 落盘，与滑条同一条路径）。
    // 色彩映射模式 / 三通道增益 / 自动曝光 / 对比 / 饱和 / gamma 一律不动 ——
    // 那些仍归「后处理」页（用户 2026-10-26 明确要求色彩映射留在后处理页）。
    // 冰岛 / 格陵兰这两套是照 AgX 调的，配 GT/Uchimura 也能用，只是高光更硬一点。
    //
    // 24 项的顺序与 RTXSettingsConfig.COLOR_GRADE_LABELS 严格一致，改一处必须改另一处。
    // 数值全部落在 COLOR_GRADE_RANGES 内（ColorGradeSettings 构造里还会再钳一次）。
    // =====================================================
    private static final int PRESET_NEUTRAL = 0;
    private static final int PRESET_ICELAND = 1;
    private static final int PRESET_GREENLAND = 2;
    private static final int PRESET_MIST = 3;

    // 冰岛 · 冷寂：降饱和 + 青蓝阴影 + 轻微褪色/暗角。
    // 这一套是照"冰岛黑沙滩那种阴天"调的：亮部不刺眼、暗部偏青、整体发灰。
    private static final float[] GRADE_ICELAND = {
            0.10f, 0.015f, 0.94f, 1.05f, 0.17f,
            0.008f, 0.014f, 0.024f,
            0.98f, 1.00f, 1.05f,
            0.94f, 1.00f, 1.07f,
            195.0f, 0.32f, 215.0f, 0.15f,
            -0.08f, 0.16f, 0.20f, 0.62f, -6.0f, 0.05f
    };

    // 格陵兰 · 极夜：压曝光 + 更强蓝 + 暗角收边，饱和度再收一档。
    private static final float[] GRADE_GREENLAND = {
            -0.25f, 0.02f, 0.88f, 1.12f, 0.14f,
            0.005f, 0.010f, 0.026f,
            0.96f, 1.00f, 1.08f,
            0.90f, 1.00f, 1.12f,
            205.0f, 0.45f, 220.0f, 0.10f,
            -0.15f, 0.10f, 0.34f, 0.50f, -10.0f, 0.02f
    };

    // 晨雾 · 苍灰：抬黑压白最狠的一档，几乎无色，只剩亮度结构（最"寂寞"的一档）。
    private static final float[] GRADE_MIST = {
            0.20f, 0.05f, 0.85f, 0.90f, 0.20f,
            0.030f, 0.032f, 0.036f,
            1.00f, 1.00f, 1.00f,
            0.98f, 1.00f, 1.02f,
            180.0f, 0.15f, 210.0f, 0.10f,
            -0.25f, 0.30f, 0.12f, 0.70f, 0.0f, 0.0f
    };

    private void applyColorGradePreset(int preset) {
        float[] grade = RTXSettingsConfig.DEFAULT_COLOR_GRADE.clone();

        if(preset == PRESET_ICELAND) {
            grade = GRADE_ICELAND.clone();
        } else if(preset == PRESET_GREENLAND) {
            grade = GRADE_GREENLAND.clone();
        } else if(preset == PRESET_MIST) {
            grade = GRADE_MIST.clone();
        }

        // 只动分级 24 项 —— 色彩映射模式 / 三通道增益 / 曝光 / 对比 / 饱和 / gamma
        // 全在「后处理」页由用户自己管，预设不越界去改它们（2026-10-26 用户要求）。
        // 与滑条同一条路径：先 native 接受，再审过才落盘。
        RTXSettingsConfig.ColorGradeSettings updated =
                new RTXSettingsConfig.ColorGradeSettings(grade);
        if(!NativeVulkan.isLoaded()
                || NativeVulkan.setColorGradeParams(updated.toParamArray())) {
            RTXSettingsConfig.setColorGradeSettings(updated);
        }

        // 滑条位置是构建时从字段读的，所以换完预设要重建这一页。
        if(this.minecraft != null) {
            this.minecraft.execute(this::rebuildPage);
        } else {
            this.rebuildPage();
        }
    }

    private static Component postTonemapLabel(int mode) {

        switch (mode) {
            case 1: return Component.literal("AgX");
            case 2: return Component.literal("PBR 中性");
            case 3: return Component.literal("ACES");
            case 4: return Component.literal("Uncharted 2");
            case 5: return Component.literal("Reinhard");
            case 6: return Component.literal("ACES 2.0");
            default: return Component.literal("Uchimura / GT");
        }
    }

    private void loadPostProcessingSettings(RTXSettingsConfig.PostProcessingSettings post) {
        this.postTonemapMode = post.tonemapMode();
        this.postExposureStrength = post.exposureStrength();
        this.postContrast = post.contrast();
        this.postSaturation = post.saturation();
        this.postGamma = post.gamma();
        this.postMirrorBounceCount = post.mirrorBounceCount();
        this.postTlasCullDistance = post.tlasCullDistance();
        this.postTlasFrustumCullMargin = post.tlasFrustumCullMargin();
        this.postRrFarCloudDistance = post.rrFarCloudDistance();
        // 2026-10-07 泛光强度不在上面的 record 里（它是后处理链第 9 个 lane 的独立标量）。
        this.postBloomStrength = RTXSettingsConfig.getPostBloomStrength();
        // 2026-10-23 调色调增益（第 10~12 个 lane，同样是独立标量）。
        this.postGainR = RTXSettingsConfig.getPostGainR();
        this.postGainG = RTXSettingsConfig.getPostGainG();
        this.postGainB = RTXSettingsConfig.getPostGainB();
    }

    private void loadWaterWaveShapeSettings(RTXSettingsConfig.WaterWaveShapeSettings shape) {
        this.waterWaveLengthScale = shape.lengthScale();
        this.waterWaveDirection = shape.directionDegrees();
        this.waterWaveCrestSharpness = shape.crestSharpness();
        this.waterWaveWarpStrength = shape.warpStrength();
    }

    private void loadWaterWaveAflSettings(RTXSettingsConfig.WaterWaveAflSettings afl) {
        this.waterWaveAflScale = afl.wavelength();
        this.waterWaveAflLayers = afl.octaves();
    }

    private void loadWaterSssSettings(RTXSettingsConfig.WaterSssSettings sss) {
        this.waterSssNonlinear = sss.nonlinear();
        this.waterSssBoost = sss.boost();
        this.waterSssPhaseG = sss.phaseG();
        this.waterSssStrength = sss.strength();
    }

    private void addOptionRow(AbstractWidget widget) {
        // The group that created this widget has already run; drop it unless it
        // belongs to the page being rebuilt.  Groups from other pages are
        // skipped here rather than not built, so their code stays in place.
        if(this.section != this.page) {
            return;
        }
        widget.setWidth(150);
        this.optionRow.add(widget);
        if(this.optionRow.size() == 2) {
            this.list.addSmall(this.optionRow.get(0), this.optionRow.get(1));
            this.optionRow.clear();
        }
    }

    // Ends the current group with one full-width row, so that no group shares
    // a row with the next one.
    private void flushOptionRow() {
        if(!this.optionRow.isEmpty()) {
            this.list.addBig(this.optionRow.get(0));
            this.optionRow.clear();
        }
    }

    // A full-width, non-interactive title row.  Ends the previous group first,
    // so a header always starts its own row and never shares one with a slider.
    // Used to split the cloud page into 体积云 / 平面云 and the volume-cloud
    // block into 外形 / 受光性质.
    private void addSectionHeader(String title) {
        if(this.section != this.page) {
            return;
        }
        this.flushOptionRow();
        Button header = Button.builder(Component.literal(title), button -> {})
                .bounds(0, 0, 310, 20)
                .build();
        header.active = false;
        this.list.addBig(header);
    }

    private AbstractSliderButton cloudSlider(
            String label,
            double minimum,
            double maximum,
            double increment,
            float current,
            String format,
            DoubleConsumer setter
    ) {
        return new AbstractSliderButton(
                0,
                0,
                310,
                20,
                Component.empty(),
                (current - minimum) / (maximum - minimum)
        ) {
            // AbstractSliderButton only refreshes its message on drag, so
            // without this the slider reads as a blank bar until it is moved.
            {
                this.updateMessage();
            }

            private double snapped() {
                return Math.round((minimum + this.value * (maximum - minimum)) / increment) * increment;
            }

            @Override
            protected void updateMessage() {
                // 2026-10-02：负值是"用着色器内置默认"的语义 —— 各参考旋钮的 UBO
                // lane 以 <0 判定回落到编译期宏。直接显示成文字，避免把它读成一个
                // 奇怪的数值（也把原先只有手改 JSON 才能触发的通道变成明档）。
                final double shown = this.snapped();
                this.setMessage(Component.literal(label + ": " + (
                        shown < 0.0
                                ? "内置默认"
                                : String.format(
                                        java.util.Locale.ROOT, format, shown))));
            }

            @Override
            protected void applyValue() {
                double value = Math.clamp(this.snapped(), minimum, maximum);
                this.value = (value - minimum) / (maximum - minimum);
                setter.accept(value);
                this.updateMessage();
            }
        };
    }

    @Override
    protected void addFooter() {

        LinearLayout footer =
                LinearLayout.horizontal()
                        .spacing(
                                8
                        );


        if(this.page == PAGE_HUB) {
            footer.addChild(
                    Button.builder(
                                    Component.literal(
                                            "确认"
                                    ),
                                    button ->
                                            this.applyPendingSettings()
                            )
                            .width(
                                    150
                            )
                            .build()
            );


            footer.addChild(
                    Button.builder(
                                    Component.literal(
                                            "返回"
                                    ),
                                    button ->
                                            this.onClose()
                            )
                            .width(
                                    150
                            )
                            .build()
            );
        } else {
            // A sub-page has no draft of its own -- the pending* fields belong
            // to the screen and survive every page switch -- so there is
            // nothing here to discard.  返回 just walks back to the hub with
            // the edits still in memory, and 应用 writes them out without
            // leaving the page, so a group can be dialled in and judged
            // without a round trip through the hub each time.
            footer.addChild(
                    Button.builder(
                                    Component.literal(
                                            "应用"
                                    ),
                                    button ->
                                            this.applyPendingSettings()
                            )
                            .width(
                                    150
                            )
                            .build()
            );


            footer.addChild(
                    Button.builder(
                                    Component.literal(
                                            "返回"
                                    ),
                                    button ->
                                            this.openPage(this.parentPage())
                            )
                            .width(
                                    150
                            )
                            .build()
            );
        }


        this.layout.addToFooter(
                footer
        );
    }


    // 2026-10-26 子页的「返回」目标：色彩分级是从「后处理」点进去的，返回就回后处理，
    // 别一下子甩回 hub（否则调一格就要重新点两次才能回到邻居旋钮）。
    private int parentPage() {
        return this.page == PAGE_COLORGRADE ? PAGE_POST : PAGE_HUB;
    }


    // Rebuilding on the next tick rather than inside the click keeps the widget
    // that raised the event from being freed while the input handler still
    // holds it.
    private void openPage(int target) {
        this.page = target;

        if(this.minecraft != null) {
            this.minecraft.execute(this::rebuildPage);
        } else {
            this.rebuildPage();
        }
    }


    // OptionsSubScreen creates HeaderAndFooterLayout once, in the constructor,
    // and init() only ever appends to it -- addTitleHeader/addToContents/
    // addToFooter are plain addChild calls.  Re-running init() without emptying
    // the layout first would leave the previous page's header, OptionsList and
    // footer in the frames, and init() re-registers everything the layout
    // visits, so the old page would keep drawing underneath the new one.
    private void rebuildPage() {
        this.layout.removeChildren();
        this.rebuildWidgets();
    }


    private void addHubButtons() {
        this.addOptionRow(this.hubButton("超分与帧生成", PAGE_UPSCALER));
        this.addOptionRow(this.hubButton("光追性能", PAGE_PERF));
        this.addOptionRow(this.hubButton("天空与大气", PAGE_SKY));
        this.addOptionRow(this.hubButton("体积云", PAGE_CLOUD));
        this.addOptionRow(this.hubButton("体积雾与体积光", PAGE_FOG));
        this.addOptionRow(this.hubButton("降雨", PAGE_RAIN));
        this.addOptionRow(this.hubButton("水面", PAGE_WATER));
        this.addOptionRow(this.hubButton("材质与画面", PAGE_MATERIAL));
        // 2026-10-26：色彩分级**不**在 hub 上单开分类 —— 它是「后处理」页里的
        // 子页（用户要求：色彩映射留在后处理，另给一个入口点进详细调色）。
        this.addOptionRow(this.hubButton("后处理", PAGE_POST));
        this.flushOptionRow();
    }


    private AbstractWidget hubButton(String label, int target) {
        return Button.builder(
                Component.literal(label),
                button -> this.openPage(target)
        ).width(150).build();
    }


}
