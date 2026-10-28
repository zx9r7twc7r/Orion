// ===========================================================================
// 体积云 v6 —— HPVolumeCloud（HanPi Volume Cloud）**完整重构移植**
//
// 蓝本：AshenOneArt/HPVolumeCloud（MIT + 署名条款）
//       HanPi Volume Cloud © AshenOneArt
//       https://github.com/AshenOneArt/HPVolumeCloud
//       （其 VolumetricClouds.hlsl 为 Unity HDRP VolumetricClouds 的 HanPi
//         改动版；本仓库 cpp/.tmp/vc_hanpi.hlsl 为逐行阅读注释版）
//
// 历史说明：本项目 TYNDALL_CLOUDS.md 的 phi_fwd 多重散射漫射场（2026-10-02）
// 即移植自同一作者的系统；本文件是**整个密度+光照系统**的完整重构移植。
//
// 移植对照表（HanPi 原名 → 本文件）：
//   EvaluateCloudProperties      → hpEvaluateCloudProperties
//   EvaluateSunLuminance         → hpEvaluateSunLuminance（锥形采样 + hp_phi_fwd）
//   EvaluateCloud                → hpEvaluateCloud（散射积分）
//   HPTraceVolumetricRay         → hpTraceVolumetricRay（两层自适应步进）
//   _CloudMapTexture             → weatherTex（R=LoCoverage G=CloudType B=ScMask）
//   _Worley128RGBA.r             → baseTex.r（PerlinWorley 基座）
//   _ErosionNoise                → erosionTex（RGBA = WispyLo/WispyHi/BillowyLo/BillowyHi）
//   _CloudLutTexture             → hpProfileLUT()（程序化，无纹理）
//   _HP_ScCellNoiseTex           → 天气图 B 通道（worley 团块场复用）
// 已知有意差异（均记录在案）：
//   1. 天气图 REPEAT 平铺（HanPi 为有限地图 CLAMP）——MC 世界尺度需要无限场；
//   2. Profile LUT 程序化（仓内无 LUT 资产，三型解析剖面 + hpRadial 驱动）；
//   3. 光步 mipOffset 无 mips 可用，省略（锥形采样本身近密远疏）；
//   4. 高空云（Ac/As）独立循环不移植——游戏已有 high_cloud_field 平面云层。
//   5. 光步跳过 erosion（cfg.lightErosion=0；参考是 simpleMode=false 全算）——
//      3D 体积目前 mipLevels=1，参考靠 mip 把远光步换粗层的机制在本仓空转，
//      erosion 采样于是成了光步里最贵的一笔。见结构体里的成本账。
//   6. 光步按 cfg.lightReuse 复用（参考每个命中步都算）——相邻命中步只差 stepSmall。
//
// 以下参数默认值取自 HanPi 注释之推荐范围，经本仓库预览器标定。
// ===========================================================================
#ifndef CLOUD_HP_CORE_GLSL
#define CLOUD_HP_CORE_GLSL

// ---------------------------------------------------------------------------
// 世界尺度与常量
// ---------------------------------------------------------------------------
// ⚠ 2026-10-27：24 km → **96 km**。1024² 纹理按 REPEAT 采样，周期就是它，
// 而云距是 44 km ⇒ 视野里同一张天气图重复约 2 次，远处云斑按 24 km 周期重现，
// 掠射看就是"一排排重复的云区/条带"。放大到 96 km（94 格/texel）后视野内不重复；
// hpRenderWeather 的频率同步 ×4，特征尺度仍是 6/3/1.5/0.75/0.375 km。
const float HP_WEATHER_TILE = 96000.0;   // 天气图世界周期（WeatherMapWorldSize）
const float HP_BASE_TILE    = 8000.0;    // PerlinWorley 基座周期（Worley128 世界尺寸）
const float HP_EROSION_TILE = 1400.0;    // 侵蚀噪声周期（ErosionNoise 世界尺寸）

// 参考的 CLOUD_DENSITY_TRESHOLD（低云的"有云"判据）。2026-10-28 之后本仓不再用它做
// 早退 —— 早退改走「云量 → presence」那条（见 hpEvaluateCloudProperties），这样"没云的区域"
// 的判据和最终密度遮罩是**同一个量**，不会出现"早退放过、最后密度被遮成 0"的白跑。
#define HP_DENSITY_THRESHOLD     0.1     // CLOUD_DENSITY_TRESHOLD（目前无读者，保留作对照）
// 云体大小阈值：**常数**，与云量无关（云量改走 presence 遮罩）。取值 = 旧版在
// 出厂档（云量 0.55、天气图 R 均值 0.66）下 threshold 的中位数 0.41。
#define HP_DENSITY_BASE_THRESHOLD 0.41
// 分布遮罩的软化宽度（无量纲，作用在天气图 R 通道上）：越小，云区边界越硬；
// 0.10 ≈ 天气图上 9.6 km 的过渡带（HP_WEATHER_TILE=96 km）。
#define HP_PRESENCE_SOFT         0.10
#define HP_PHIFWD_OMEGA0         0.999   // 单次散射反照率（写死，见 PhiFwd_FromRTE.md）
#define HP_PHIFWD_KAPPA_SCALE    sqrt(3.0 * (1.0 - HP_PHIFWD_OMEGA0))
#define HP_CONE_RATIO            2.0     // 锥形采样相邻步比率
#define HP_CONE_MIN_STEP         5.0     // 首步宽度下限（m）
#define HP_CONE_MAX_DISTANCE     6000.0  // 锥形采样总覆盖距离（m）

struct HPCloudConfig
{
    float bottomHeight;        // 云底（格）
    float topHeight;           // 云顶（格）
    float planetRadius;

    float coverage;            // [HanPi] coverageMultiplier（天气图 R 的基础缩放）
    float densityMult;         // [HanPi] _HP_DensityMultiplier（σt 标定，每格）
    float noiseScale;          // [HanPi] _HP_NoiseScale 倍率（基座世界尺度）
    float detailScale;         // [HanPi] _HP_DetailNoiseScale 倍率
    float detailStrength;      // [HanPi] detailStrength 总倍率（erosionStrength lane）
    float typeBias;            // （遗留 lane，本模型由天气图 G 驱动云型——不读）

    int   viewSteps;           // [HanPi] _NumPrimarySteps
    int   lightSteps;          // [HanPi] _NumLightSteps
    float shadowDistance;      // （遗留 lane——锥形采样自带 CONE_MAX_DISTANCE）

    int   msOctaves;           // NUM_MULTI_SCATTERING_OCTAVES（1..3）
    float msAttenuation;       // [HanPi] _HP_MS_Attenuation（消光衰减率/octave，默认 0.5）
    float msContribution;      // [HanPi] _HP_MS_Contribution（能量权重/octave，默认 0.5）
    float msEccentricity;      // [HanPi] _HP_MS_Eccentricity（偏心率衰减/octave，默认 0.5）
    float luminanceScale;      // 本仓能量总倍率（游戏 UBO ueCloudA.w）

    float forwardEcc;          // [HanPi] _HP_ForwardEccentricity（默认 0.85）
    float backwardEcc;         // [HanPi] _HP_BackwardEccentricity（默认 0.3）
    float phaseMix;            // （遗留 lane——双叶为加法混合，不读）

    vec3  sunDir;              // 指向太阳
    vec3  sunColor;            // 直射辐亮度
    vec3  ambientTop;          // [HanPi] ambientTermTop（含 AmbientTopMultiplier）
    vec3  ambientBottom;       // [HanPi] ambientTermBottom（含 BottomMultiplier）
    vec3  groundAlbedo;        // （遗留 lane）
    float groundBounce;        // （遗留 lane——环境光走 upwardAO 路径）

    float viewDistance;
    float fadeStart;
    float minTransmittance;
    float atmosphereDensity;   // （遗留 lane）
    float time;                // 秒（风）
    float jitter;              // [0,1) 本像素本帧抖动

    // 相机在 **altitude 空间**（y=海拔，与 positionAlt 同一套坐标）。由
    // hpTraceVolumetricRay 入口从射线原点反解，调用方不必填 —— 见「距离淡出」。
    vec3  cameraAlt;

    // ── 外形（HP 全参数暴露，预览面板可调）──
    float detailStrengthCu;    // _HP_DetailStrengthCu（默认 0.55）
    float detailStrengthTcu;   // _HP_DetailStrengthTcu（默认 0.70）
    float detailStrengthCb;    // _HP_DetailStrengthCb（默认 0.85）
    float bottomSmoothHeight;  // _HP_BottomSmoothHeight（默认 0.08）
    float wispyEdgeWidth;      // _HP_WispyEdgeWidth（默认 0.4）
    float wispyReach;          // _HP_WispyReach（默认 0.15）
    float wispyTopHeight;      // _HP_WispyTopHeight（默认 0.75）
    float wispyTopHardness;    // _HP_WispyTopHardness（衰减指数 ×10，默认 2）
    float wispyWeight;         // wispy 通道权重（默认 1）
    float billowyWeight;       // billowy 通道权重（默认 1）
    float loCoverTopMax;       // _HP_LoCoverTopMax（默认 2.2）
    float loCoverTopStr;       // _HP_LoCoverTopStr（默认 1.0）
    float covMapScale;         // coverage 映射总倍率（标定 0.6）
    float scWorleyStrength;    // _HP_ScWorleyStrength（默认 0.25）
    float scHeightScale;       // _HP_ScHeightScale（默认 0.35）
    float scCellThickStr;      // _HP_ScCellThickStr（默认 0.6）

    // ── 受光（HP 全参数暴露）──
    // 注：_HP_AmbientTopMultiplier / _HP_AmbientBottomMultiplier 在本移植里由
    // **调用方乘进 ambientTop / ambientBottom**（与参考的 EvaluateEnvironmentLighting
    // 同一处、同一含义），所以结构体里不再留这两个字段——留着的下场是
    // "写了没人读"，2026-10-25 的原版本就是初始化缺失 + 静默无效。
    float powderIntensity;     // _PowderEffectIntensity（默认 1）
    float aoUpwardScale;       // _HP_AOUpwardScale（默认 1）
    float phiFwdIntensity;     // _HP_HpHpPhiFwd_Intensity（默认 0.6）
    float phiFwdDepthPow;      // _HP_HpHpPhiFwd_DepthPow（默认 1）
    float phiFwdDepthBias;     // _HP_HpHpPhiFwd_DepthBias（默认 0）
    float phiFwdCompress;      // _HP_HpHpPhiFwd_Compress（默认 1）
    float phiFwdBoundary;      // BoundaryConfidence（0=跳过 4 邻域采样，性能）
    float phiFwdMSBuild;       // _HP_HpHpPhiFwd_MSBuildScale（默认 1）
    float scatterODScale;      // _HP_ScatterSourceODScale（默认 0.1）
    float scatterCurvePow;     // _HP_ScatterSourceCurvePow（默认 1）

    // ── 光步性能档（2026-10-28「对齐 + 性能优先」轮）──
    // 整个云 pass 的绝对大头是光步：每命中步 = lightSteps(8) ×
    // (weather 2D + base 3D + erosion 3D) ≈ 24 次体积采样，而视线步只有 3 次。
    // 参考把远光步换成粗 mip（`max(noiseMipOffset,0)`，最多 +3 级），但本仓两张
    // 3D 体积 mipLevels=1（VulkanCore createVolume）⇒ 那个 mipOffset 是空转的，
    // 光步一直按全分辨率全价付。在补齐 mip 链之前，先用这两个开关给光步降档：
    //   lightErosion : 0 = 光步只算 base（跳过 erosion；参考是 false = 全算）
    //   lightReuse   : N>1 = 每 N 个命中步重算一次光步，中间复用（1 = 每步都算）
    float lightErosion;
    float lightReuse;
};

// ---------------------------------------------------------------- 工具
// 出厂配置（HanPi 注释里的推荐默认值）。
// 为什么要有这个函数：GLSL 的 struct 没有成员默认值，调用方只要漏填一个字段
// 就是**未定义行为**（实测：mode 13 诊断切片漏了 covMapScale ⇒ coverage=0 ⇒
// 整屏全黑；游戏端漏了 ambientTopMult 之类则完全静默）。所有构造点一律从
// hpDefaultConfig() 起手，再覆盖自己要改的字段。
HPCloudConfig hpDefaultConfig()
{
    HPCloudConfig c;
    c.bottomHeight = 1800.0;
    c.topHeight = 3600.0;
    c.planetRadius = 6360000.0;

    c.coverage = 0.5;
    c.densityMult = 0.004;
    c.noiseScale = 1.0;
    c.detailScale = 1.0;
    c.detailStrength = 1.0;
    c.typeBias = 0.0;

    c.viewSteps = 96;
    c.lightSteps = 8;
    c.shadowDistance = 0.0;

    c.msOctaves = 2;
    c.msAttenuation = 0.5;
    c.msContribution = 0.5;
    c.msEccentricity = 0.5;
    c.luminanceScale = 1.0;

    c.forwardEcc = 0.85;
    c.backwardEcc = 0.3;
    c.phaseMix = 0.5;

    c.sunDir = vec3(0.0, 1.0, 0.0);
    c.sunColor = vec3(0.0);
    c.ambientTop = vec3(0.0);
    c.ambientBottom = vec3(0.0);
    c.groundAlbedo = vec3(0.0);
    c.groundBounce = 0.0;

    c.viewDistance = 0.0;        // 0 = 不做距离淡出（诊断用）
    c.fadeStart = 0.3;
    c.minTransmittance = 0.003;
    c.atmosphereDensity = 0.0;
    c.time = 0.0;
    c.jitter = 0.5;
    c.cameraAlt = vec3(0.0);

    c.detailStrengthCu = 0.55;
    c.detailStrengthTcu = 0.70;
    c.detailStrengthCb = 0.85;
    c.bottomSmoothHeight = 0.08;
    c.wispyEdgeWidth = 0.40;
    c.wispyReach = 0.15;
    c.wispyTopHeight = 0.75;
    c.wispyTopHardness = 2.0;
    c.wispyWeight = 1.0;
    c.billowyWeight = 1.0;
    c.loCoverTopMax = 2.2;
    c.loCoverTopStr = 1.0;
    // 标定（2026-10-25，用 mode 13 密度切片量"天空被遮比例"）：
    //   covMapScale 0.6 → 2% / 1.0 → 52% / 1.4 → 83% / 1.8 → 91%
    // 取 1.0，让「云量」滑条 ≈ 天空被遮比例（参考里这就是个 1.0 的乘子，
    // 没有额外自由标定；0.6 是原重构期手填的,结果云稀到几乎看不见）。
    c.covMapScale = 1.0;
    c.scWorleyStrength = 0.25;
    c.scHeightScale = 0.35;
    c.scCellThickStr = 0.6;

    c.powderIntensity = 1.0;
    c.aoUpwardScale = 1.0;
    c.phiFwdIntensity = 0.6;
    c.phiFwdDepthPow = 1.0;
    c.phiFwdDepthBias = 0.0;
    c.phiFwdCompress = 1.0;
    c.phiFwdBoundary = 0.0;
    c.phiFwdMSBuild = 1.0;
    c.scatterODScale = 0.10;
    c.scatterCurvePow = 1.0;

    // 性能档出厂值：光步跳过 erosion + 每 2 个命中步重算一次光步
    // ⇒ 每命中步的体积采样 24 → 8 次（约 3×）。外观代价用预览器 A/B 标定，
    // 见 .tmp/cloudalign（改动前后三视角图的逐像素差）。
    c.lightErosion = 0.0;
    c.lightReuse = 2.0;
    return c;
}

float hpSaturate(float v) { return clamp(v, 0.0, 1.0); }


// HanPi DensityRemap(x, a, b, c, d)
float hpRemap(float x, float a, float b, float c, float d)
{
    // b==a 时原式是 0/0 ⇒ NaN，一进 tonemap 就是整块黑/白，而且不可追踪。
    // 语义上"区间宽度为 0"应当退化成**阶跃**：x<a → c，x≥a → d。
    float denom = b - a;
    if(abs(denom) < 1.0e-6)
        return (x < a) ? c : d;
    return (((x - a) / denom) * (d - c)) + c;
}

// HanPi HenyeyGreenstein（归一化，1/4π）
float hpHG(float cosAngle, float g)
{
    float g2 = g * g;
    return (1.0 / (4.0 * 3.14159265359)) * (1.0 - g2) /
        pow(max(1.0 + g2 - 2.0 * g * cosAngle, 1.0e-4), 1.5);
}

// HanPi PowderEffect（smoothstep(0.5, -0.5, cos)——背光侧生效）
float hpPowderEffect(float cloudDensity, float cosAngle, float intensity)
{
    float powder = 1.0 - exp(-cloudDensity * 4.0);
    powder = hpSaturate(powder * 2.0);
    return mix(1.0, mix(1.0, powder, smoothstep(0.5, -0.5, cosAngle)), intensity);
}

// 球壳求交（近交点；数值稳定写法——相机在层上俯视时 b、s 量级 3.6e6 相消）
float hpIntersectShell(float originRadius, float rayDirY, float sphereRadius)
{
    float b = originRadius * rayDirY;
    float c0 = originRadius * originRadius - sphereRadius * sphereRadius;
    float d = b * b - c0;
    if(d < 0.0) return -1.0;
    float s = sqrt(d);
    float q = -(b + (b >= 0.0 ? s : -s));
    if(abs(q) < 1.0e-9) return 0.0;
    return c0 / q;
}

float hpNormalizedHeight(vec3 pos, HPCloudConfig cfg)
{
    float h = max(cfg.topHeight - cfg.bottomHeight, 1.0);
    return (length(pos) - (cfg.planetRadius + cfg.bottomHeight)) / h;
}

float hpSlab(HPCloudConfig cfg)
{
    return max(cfg.topHeight - cfg.bottomHeight, 1.0);
}

// ---------------------------------------------------------------------------
// Profile LUT（程序化）。HanPi 的 _CloudLutTexture：UV=(lutSampleHeight,
// radialDist)，RGB=(Cu, Tcu, Cb) 三型剖面。仓内无 LUT 资产，这里按三型积云
// 的公认形状解析实现；radial 用天气图 B 通道（ScMask）驱动——云团核心
// （Cb 偏向）到边缘（Cu 偏向）剖面渐变。
// ---------------------------------------------------------------------------
// Cu 通道单独成函数：参考的 Sc 混合是 `lerp(heightGradient, profiles.r, scStr)`，
// 即在**同一个 lutSampleHeight** 上取 LUT 的 R(Cu) 通道。我们原来用
// `hpProfileLUT(lutSampleHeight, radial, 0.0)` 再算一遍（等于 lo = mix(cu,cb,radial)），
// 既多一次 6×smoothstep 的完整廓形求值（光步里每步都要付），语义也不对。
float hpProfileCu(float h)
{
    return smoothstep(0.02, 0.10, h) * (1.0 - smoothstep(0.28, 0.60, h));
}

float hpProfileLUT(float h, float radial, float type)
{
    // ⚠⚠ 2026-10-26 **第二次修正**，这次是拿数字算出来的，不是凭观感：
    //
    // 上一版把三档廓形的上端截断推到 0.70 / 0.85 / 0.95（为了消掉"只有底部一条
    // 薄毯"）。但高度坐标不是直接进廓形的 —— 先被 Coverage 的 Möbius 变换压缩：
    //     loCoverTopScale = 1 + 1.2 × coverageHeight        （覆盖率 1.0 时 = 2.2）
    //     lut = h / (1 + (scale-1)·h)                      （h=1 ⇒ lut 只有 0.45）
    // 于是 h ∈ [0.13, 1.0] 这一整段（占层厚 ~87%）都落在"平台"里，廓形恒等于 1.0
    // ⇒ 密度与高度无关 ⇒ 云填满整个 slab，顶面就是 slab 的几何顶面（一个平面），
    // 渲染出来就是用户截图里那块**刀切一样的实心平板**（全遮时尤其明显）。
    // 实测（.tmp/analyze_profile.py）：旧廓形在 coverage 0.30/0.45/0.78/1.00 下
    // 恒为 1 的高度区间分别是 84% / 87% / 87% / 86% 的层厚。
    //
    // 正确的廓形必须是**钟形**（真实 HDRP CloudLUT 的 R/G/B 三通道就是这样）：
    // 底部快速起、在 lut≈0.2~0.3 达峰、到 lut≈0.6~0.85 归零。这样即使 Möbius 把
    // 坐标压到 0.45，顶部仍然落在衰减段里 ⇒ 云顶是"噪声 + 廓形"共同决定的有机面，
    // 而不是 slab 的平面。Cu 最短、Tcu 居中、Cb 几乎占满层高（与参考的三档语义一致）。
    // Cu：积云——lut 0.28 起收、0.60 归零
    float cu  = hpProfileCu(h);
    // Tcu：塔状积云——更高更立
    float tcu = smoothstep(0.02, 0.09, h) * (1.0 - smoothstep(0.42, 0.72, h));
    // Cb：积雨云——几乎全高，只在最顶收
    float cb  = smoothstep(0.01, 0.07, h) * (1.0 - smoothstep(0.55, 0.85, h));
    // radial（0=云团核心 → 1=边缘）：核心偏 Cb、边缘偏 Cu
    float radialCore = 1.0 - clamp(radial, 0.0, 1.0);
    float lo  = mix(cu, cb, radialCore);
    float gradient;
    if(type < 0.5)
        gradient = mix(lo, tcu, clamp(type * 2.0, 0.0, 1.0));
    else
        gradient = mix(tcu, cb, clamp((type - 0.5) * 2.0, 0.0, 1.0));
    return gradient;
}

// ---------------------------------------------------------------------------
// hpEvaluateCloudProperties —— HanPi 密度评估（逐式移植）
// positionAlt : 世界坐标（y=海拔）
// simpleMode  : true = 跳过 erosion（两层步进的快速探测）
// outDensity / outLocalHeight（云体 profile 局部高度，供 phi_fwd）
// ---------------------------------------------------------------------------
void hpEvaluateCloudProperties(vec3 positionAlt, float slabH, float noiseMipOffset,
    bool simpleMode, HPCloudConfig cfg, sampler2D weatherTex, sampler3D baseTex,
    sampler3D erosionTex, out float outDensity, out float outLocalHeight)
{
    outDensity = 0.0;
    outLocalHeight = 0.0;

    // [HanPi] PointInsideCloudVolume + 归一化高度。参考里层外直接 return，
    // 这里原来只判 h>=1 ⇒ slab 以下（光步掠射/相机在云下看远场）仍会跑完
    // 天气图 + 2 次 3D 采样才发现密度是 0。h<=0 早退是纯收益。
    float h = (length(positionAlt + vec3(0.0, cfg.planetRadius, 0.0))
        - (cfg.planetRadius + cfg.bottomHeight)) / max(slabH, 1.0);
    if(h <= 0.0 || h >= 1.0) return;

    // ── 天气图（REPEAT 平铺，有意差异 #1）──
    vec2 windUV = vec2(0.862, 0.507) * (14.0 * 0.35 * cfg.time);
    vec2 weatherUV = (positionAlt.xz - windUV) / HP_WEATHER_TILE;
    vec4 weather = texture(weatherTex, weatherUV);
    float cloudType   = weather.g;
    float scAtlasMask = weather.b;

    // ── Coverage 双路（Cover/Height 各自可调，本移植共用一套标定）──
    float coverageRaw    = weather.r;
#if HP_DIAG_UNIFORM_COVERAGE
    // 诊断开关（默认 0）：coverage 取常数，把「天气图」整条链路从密度里摘掉，
    // 只留下 base 噪声 + 廓形。用来二分"条纹来自天气图还是来自 3D 噪声"。
    coverageRaw = 0.62;
#endif
    float coverage       = hpSaturate(coverageRaw * cfg.coverage * cfg.covMapScale);
    float coverageHeight = hpSaturate(coverageRaw * cfg.coverage * cfg.covMapScale);

    // ── 云量（分布密度）→ 存在性遮罩，与「云的大小」解耦（2026-10-28）──
    //
    // 旧实现：`threshold = mix(0.62, 0.14, pow(coverage, 0.8))`，即云量越低、阈值越高。
    // 阈值是"噪声值的切割线"，抬高它等于把**每一朵**云都削一圈 ⇒ 用户说的
    // "调云量，云也跟着变小"。这两件事必须拆开：
    //   云量   → 这里的 presence：整片区域有/无云（软化宽度固定）⇒ 只改"多少朵"
    //   云的大小 → base 噪声频率（cfg.noiseScale）⇒ 只改"每朵多大"
    // 判据：presence 乘在密度上，阈值改成常数 HP_DENSITY_BASE_THRESHOLD。
    //
    // 曲线标定（两层证据，全部可复算）：
    // ① 天气图 R 的分布（--dump-atlas 导出 512²/1920×1080 RGBA32F 后统计）：
    //    P(R>0.20)=0.972  P(R>0.30)=0.934  P(R>0.40)=0.864  P(R>0.50)=0.757
    //    P(R>0.60)=0.612  P(R>0.70)=0.456  P(R>0.90)=0.177  P(R>0.95)=0.129
    // ② 旧实现的"云量→天空被遮比例"实测（--model v5top，量 R>0 的面积占比）：
    //    云量 0.10→0.8%   0.19→75.8%   0.30→93.9%   0.48→97.8%   0.90→99.6%
    //    也就是说旧滑条几乎是"开关"（全剧变集中在 0.10~0.20），而且存量存档
    //    正好落在 0.19。所以切割线必须按**旧曲线**反解，否则玩家一进游戏就觉得
    //    "云量 0.19 的天气变了"。用 (1-u)^5 拟合上面五点（u = 归一化滑条）：
    //    0.10→1.00(≈8% 有云)  0.19→0.55(≈74%)  0.30→0.24(≈96%)  0.48→0.04(≈99.6%)
    // 注意下限只能到 ~8%：天气图本身有 13% 面积饱和到 1.0，"万里无云"做不到。
    float presT = hpSaturate((cfg.coverage - 0.10) / 0.80);
    float presCut = pow(1.0 - presT, 5.0);
    float presence = smoothstep(presCut - HP_PRESENCE_SOFT,
        presCut + HP_PRESENCE_SOFT, coverageRaw);
    if(presence <= 0.002)
        return;   // 既省掉整条密度链（8 次 ALU + 2 次 3D 采样），又是"这片天没云"的语义

    // ── Sc 细胞（B 通道 worley 团块复用为细胞场）──
    float scStr = cfg.scWorleyStrength * scAtlasMask;
    float scCell = hpSaturate(weather.b * 1.25);

    float scCoverage = hpSaturate(coverageRaw * cfg.coverage * (cfg.covMapScale * 1.1));
    coverage = mix(coverage, scCoverage * scCell, scStr);

    // ── Coverage 驱动高度拉伸（Möbius，底部锚定，只加不减）──
    float loCoverTopScale = 1.0 + 1.2 * hpSaturate(coverageHeight);
    float heightForLUT = h / (1.0 + (loCoverTopScale - 1.0) * h);

    // ── Sc 高度压缩：积云廓形压进 slab 底部 25% ──
    float scCompressedHeight = hpSaturate(h / max(cfg.scHeightScale, 0.05));
    float lutSampleHeight = mix(heightForLUT, scCompressedHeight, scStr);
    outLocalHeight = lutSampleHeight;

    // ── Profile 高度梯度（radial：云团核心 → 边缘）──
    // 参考是 `saturate(length(weatherUV - 0.5) * 2.0)`：整张天气图上从中心到边缘的
    // 一圈**空间**径向渐变。本移植的天气图是 REPEAT 平铺的，套原式会变成每 24 km
    // 重复一次的菱形（= 给云高加一层棋盘格）。
    //
    // 2026-10-26 修正：我上一版用 `1 - coverage` 代替它，等于把**覆盖率耦合进廓形**：
    //   radialCore = 1 - radial = coverage
    //   lo = mix(cu, cb, radialCore)   ⇒ 覆盖率越高越偏向 Cb（几乎占满层高的廓形）
    // 于是"全遮"必然得到一整块填满 slab 的实心板 —— 这正是用户截图那个天花板。
    // 覆盖率是"有多少云"，不该决定"云长多高"。这里取**中性常数 0.5**
    // （= cu/cb 各半），云型仍由天气图 G 通道的 cloudType 决定。
    // 若以后要复现参考的径向语义，需要先把天气图改成不平铺（带 center + 边界）。
    float hpRadial = 0.5;
    // ⚠ 自创（参考里没有这一句）：Möbius 压缩把 lut 最高只推到 ~0.45（覆盖率 1.0 时），
    // 而任何解析廓形都在 lut≈0.5 以上才衰减 ⇒ 高覆盖时廓形可能整层恒为 1（实测 Cb
    // 在 coverage ≥ 0.78 时就是 1.0 到头），云被填满 slab、顶面 = 几何顶面（刀切平面）。
    // 这里用**未压缩**的 h 补一道顶部软边，保证顶面永远是坡面。
    // 参考不需要它是因为它的 CloudLUT 纹理本身在低 lut 就衰减完（真实资产）；
    // 等我们有了真 LUT，这句话可以删。
    float heightGradient = hpProfileLUT(lutSampleHeight, hpRadial, cloudType);
    // 对齐参考：Sc 用 **同一高度取样** 的 Cu 通道混合（profiles.r），不再二次求廓形。
    if(scStr > 0.0)
        heightGradient = mix(heightGradient, hpProfileCu(lutSampleHeight), scStr);
    // 放在 scStr 混合**之后**：否则 Sc 那次 mix 会把软边又抹掉一部分。
    heightGradient *= 1.0 - smoothstep(0.82, 1.0, h);

    // ── Base shape（R = PerlinWorley；pow(0.6) 软化高端分布）──
    //
    // ⚠⚠ 2026-10-26 **风偏移的单位错了，这就是"云每帧完全变一次"的根因**：
    // `windUV` 是**世界单位**（第 322 行 `weatherUV = (positionAlt.xz - windUV)/TILE`
    // 就是这么用的），要变成本纹理的 UV 只能**除一次** HP_BASE_TILE。
    // 原来这里先乘了 `HP_WEATHER_TILE * 0.6`（= 14400）再除 8000 ⇒ 实际速度被放大
    // **14400 倍**：4.9 格/秒 → 70 560 格/秒 ≈ **1.2 km/帧**，而 base 纹理一个格胞
    // 才 1.2 km ⇒ 每帧正好平移一个云团宽度。表现出来不是"移动"，而是**逐帧闪动**
    // （每帧都是一组全新的云），跟"毫秒级抖动变化形态"完全一致。
    // 参考里 `windOffset = _HP_NoiseWindOffset * _HP_BaseNoiseWindSpeed` 是**UV 量**，
    // 直接加在 baseUVW 上；我们的 windUV 是世界量，所以必须先归一化一次。
    vec3 windOffset = vec3(windUV.x, 0.0, windUV.y);
    // ⚠⚠ 2026-10-28 **"云被拉成柱状"的成因与安全钳制**：
    // base 体积一格里是 12 个 Perlin-Worley 格胞 ⇒ 世界格胞边长 = (HP_BASE_TILE/noiseScale)/12。
    // 而云层厚度可能只有 2000 格左右。格胞一旦接近或超过层厚，竖直方向上就**不到一个格胞**
    // ⇒ 密度沿 y 几乎恒定 ⇒ 云的横截面被原样竖向拉到底 = 柱子/板（不是 bug，是采样尺度
    // 的物理限制：想要更大的云，必须先加云层厚度）。
    // 实测（用户存档 cloudSize 1.45）：旧映射 noiseScale=1.45 ⇒ 格胞 460 格（层内 4.5 个）
    // 正常；把 size→noiseScale 翻转后变成 3196 格（>1.5×层厚）⇒ 满屏柱子。
    // 约束：格胞 ≤ 层厚/2 ⇒ baseTile ≤ 6·slabH ⇒ noiseScale ≥ HP_BASE_TILE/(6·slabH)。
    // 层厚 2080 时下限 ≈ 0.64，与用户存档的 1.45 不冲突（所以不影响现有世界）。
    float minNoiseScale = HP_BASE_TILE / (6.0 * slabH);
    float noiseScaleClamped = max(cfg.noiseScale, minNoiseScale);
    vec3 baseUVW = positionAlt / (HP_BASE_TILE / max(noiseScaleClamped, 0.02))
        + windOffset / HP_BASE_TILE;
    // [HanPi] SAMPLE_TEXTURE3D_LOD(..., max(noiseMipOffset, 0))：参考的光步按
    // j/(N-1)*3 递增 mip（远步换粗噪声），主步进的 simple 门控固定 mip1。
    // 这里的 noiseMipOffset 以前是**收了不用的死参数**，现在真正生效。
    // 本仓 3D 噪声当前只有 mip0（VulkanCore createVolume: mipLevels=1）⇒ 显式 LOD
    // 与 texture() 逐位一致；将来噪声链补上 mip，这里自动吃收益。
    float baseNoiseR = textureLod(baseTex, baseUVW, max(noiseMipOffset, 0.0)).r;
    float baseShape = pow(abs(baseNoiseR), 0.6);

    // ── 云底平滑：localHeight→0 时 baseShape→1、detail→0（2D 分布接管）──
    // 注意：这里没有局部副本 —— 一律用 cfg.bottomSmoothHeight（面板/UBO 可调）。
    float bottomNoiseFade = pow(hpSaturate(lutSampleHeight
        / max(cfg.bottomSmoothHeight, 0.001)), 2.0);
    baseShape = mix(1.0, baseShape, bottomNoiseFade);

    // ── Detail erosion（R=WispyLo G=WispyHi B=BillowyLo A=BillowyHi）──
    float billowy = 0.0;
    float wispy = 0.0;
    if(!simpleMode)
    {
        // 同上：世界量 → UV 只能除一次，而且除数必须是 detailUVW 用的那个
        // （HP_EROSION_TILE / detailScale），否则 detailScale 一变风速就跟着变。
        float detailTile = HP_EROSION_TILE / max(cfg.detailScale, 0.02);
        vec3 detailWindOffset = vec3(windUV.x, 0.0, windUV.y) * 0.9
            + vec3(0.0, cfg.time * 6.0, 0.0);   // 竖直风速（DetailNoiseVerticalWindSpeed）
        vec3 detailUVW = vec3(positionAlt.x, -positionAlt.y, positionAlt.z) / detailTile
            + detailWindOffset / detailTile;
        vec4 d = texture(erosionTex, detailUVW);
        billowy = (d.b * 0.8 + d.a * 0.9) * bottomNoiseFade * clamp(cfg.billowyWeight, 0.0, 2.0);
        wispy   = (d.r * 0.7 + d.g * 0.8) * bottomNoiseFade * clamp(cfg.wispyWeight, 0.0, 2.0);
    }

    // ── 低云密度 ──
    float base_cloud = 0.0;
    {
        // Sc 细胞厚度调制
        // 参考是 _HP_ScCellThickPow（可调）；本仓固定 2.0 ⇒ 用乘法而不是 pow。
        float scCellSafe = max(scCell, 0.001);
        float scCellShaped = scCellSafe * scCellSafe;
        float scCellFactor = mix(1.0, scCellShaped, 0.6);
        float heightClip = mix(1.0, scCellFactor, scStr);

        // 阈值是**常数**：它决定"一朵云的边界落在噪声的哪个等值面"= 云的大小。
        // 一旦让它跟着云量走（旧版 mix(0.62,0.14,coverage^0.8)），调云量就会把所有
        // 云一起削大/削小 —— 那正是"云量也控制云大小"的机制。现在云量只走上面的
        // presence 遮罩（整朵开/关），这里彻底与云量解耦。
        float threshold = HP_DENSITY_BASE_THRESHOLD;

        // 云型三段插值侵蚀强度（Cu/Tcu/Cb）
        float detailStrength;
        if(cloudType < 0.5)
            detailStrength = mix(cfg.detailStrengthCu, cfg.detailStrengthTcu, cloudType * 2.0);
        else
            detailStrength = mix(cfg.detailStrengthTcu, cfg.detailStrengthCb,
                (cloudType - 0.5) * 2.0);
        detailStrength = mix(detailStrength, 0.45, scStr);
        detailStrength *= clamp(cfg.detailStrength, 0.0, 2.0);

        // detail 侵蚀：DensityRemap 把 baseShape 按 detail 噪声向低端收缩。
        // ⚠ 2026-10-26 自审：`hpRemap(x, a, 1.0, 0, 1) = (x-a)/(1-a)`，**a ≥ 1 时分母变号**，
        // 分子（x<a）也是负的 ⇒ 结果为正 ⇒ saturate 成 1.0 ⇒ 整个体积密度饱和 = 实心板。
        // a = billowy × detailStrength，而 billowy 上限是
        //   (0.8+0.9) × bottomNoiseFade × billowyWeight(面板可到 2.0) × perType(0.85) × detailStrength(面板可到 2.0)
        // ⇒ 用户把「侵蚀权重/侵蚀幅度」调高就会踩到，症状和之前的天花板一模一样。
        // 钳到 0.95：留 5% 弹性，同时保证分母 ≥ 0.05。
        float erosionBillowy = min(billowy * detailStrength, 0.95);
        float erosionWispy   = min(wispy   * detailStrength, 0.95);
        float erodedBillowy = hpSaturate(hpRemap(baseShape, erosionBillowy, 1.0, 0.0, 1.0));
        float erodedWispy   = hpSaturate(hpRemap(baseShape, erosionWispy,   1.0, 0.0, 1.0));
        // ⚠⚠ 2026-10-26 **第三次**改这里，这次有实测证据（游戏截图：天上一片片
        // 巨大的灰色平板，边缘笔直、向灭点收敛）。
        //
        // 廓形不能乘在阈值**之前**。原因是个纯数学问题：
        //   density = remap(eroded × grad, threshold, threshold+edge)
        // 过阈条件 = **eroded × grad > threshold**。而 grad 只随高度变化（单峰），
        // eroded 才是水平方向的噪声。当 threshold 落在 eroded 的典型值附近
        // （覆盖率 0.55 ⇒ threshold 0.45，而 eroded 均值 0.52），条件退化成
        // **grad > 0.45/0.52 = 0.87** ⇒ 只剩 grad 峰值那一层高度能过阈
        // ⇒ 密度成了"某个固定高度上的薄壳"，掠射看就是一片片巨大的平板。
        // 这不是"云"，是 **该乘积的等值面**。
        //
        // HZD/Schneider 的原始顺序是**先阈值、后乘廓形**：
        //   density = saturate(remap(eroded, thr, thr+edge)) × grad
        // 这样"哪一列有云"只由 3D 噪声（各向同性）决定，廓形只负责竖向收边，
        // 不会产生任何等值面薄壳。参考实现（重构到一半）与它不同，但那条路
        // 在有真实美术 LUT 时靠 LUT 的陡降掩盖了这个问题；我们用的是解析廓形，
        // 必须按物理正确的顺序来。

        // wispyThreshold：比 billowy 低——wispy 在 billowy 归零后仍向外延伸
        // wispy 的阈值比 billowy 低：billowy 归零之后 wispy 仍向外延伸。
        float wispyThreshold = threshold - clamp(cfg.wispyReach, 0.0, 0.5);
        float edgeSoftness = 0.25;
        float densityBillowy = hpSaturate(hpRemap(erodedBillowy, threshold,
            threshold + edgeSoftness, 0.0, 1.0));
        float densityWispy = hpSaturate(hpRemap(erodedWispy, wispyThreshold,
            wispyThreshold + edgeSoftness, 0.0, 1.0));

        // 廓形在**阈值之后**乘（见上）：水平分布只由 3D 噪声决定，廓形只管竖向。
        densityBillowy *= heightGradient;
        densityWispy   *= heightGradient;

        // Wispy 高度衰减（幂函数，无硬边；WispyTopHeight=0.75, Hardness=2 → ×10）
        float wispyT = hpSaturate((h - clamp(cfg.wispyTopHeight, 0.0, 0.99))
            / max(1.0 - clamp(cfg.wispyTopHeight, 0.0, 0.99), 0.001));
        float wispyHeightMask = pow(1.0 - wispyT, max(cfg.wispyTopHardness * 10.0, 0.01));
        densityWispy *= wispyHeightMask;

        // 致密核心→billowy；边缘→wispy
        float billowyByShape = smoothstep(0.0, max(cfg.wispyEdgeWidth, 0.01), densityBillowy);
        base_cloud = mix(densityWispy, densityBillowy, billowyByShape) * heightClip;

        // per-type 密度倍率（Cu/Tcu/Cb）
        float typeDensityMult;
        if(cloudType < 0.5)
            typeDensityMult = mix(1.0, 1.25, cloudType * 2.0);
        else
            typeDensityMult = mix(1.25, 1.5, (cloudType - 0.5) * 2.0);
        base_cloud *= typeDensityMult;
    }

    float density = max(0.0, base_cloud);
    // 分布遮罩（云量）：区域边界是软过渡，云体本身的形状/大小完全由噪声+阈值决定。
    density *= presence;
    // Cover 越低密度压越多（HP_HI_A_DENSITY_SCALE(coverage)，Intensity 0.35 / Contrast 2.0）
    density *= 1.0 - hpSaturate(0.35 * (1.0 - pow(hpSaturate(coverage), 2.0)));

    // [HanPi] _HP_DensityMultiplier 在 EvaluateCloudProperties **内部**乘入——
    // 光步与视线共用同一 σt。第一版把倍率放在视线路径调用侧，光步的
    // σ_t = 裸密度（每米 0-1！）→ 锥形采样 OD 累积上千、太阳项全灭（实测全屏奶白）。
    density *= cfg.densityMult;

    // ── 距离淡出（逐采样；HDRP DensityFadeValue 的对应物）──────────────
    // 为什么必须**逐采样**、而不是在 trace 末尾对整条射线乘一次：
    // 地平线方向的射线在球壳里要走 100~300 km 才穿出（掠射弦长）。末尾那一次
    // 用的是"首个命中距离"这一个标量 ⇒ 远处一整层云被整块保留/整块压掉，
    // 交界是硬边，且远场采样已经严重失真（预览 --view-dist 8000 时扇状条纹
    // 完全消失、>8km 才出现 ⇒ 病灶在远场）。逐采样按该点到相机的距离淡出，
    // 远云才会真的"逐渐"溶进天空。
    if(cfg.viewDistance > 1.0)
    {
        float camDist = length(positionAlt - cfg.cameraAlt);
        float fadeT = smoothstep(cfg.viewDistance * clamp(cfg.fadeStart, 0.0, 0.99),
            cfg.viewDistance, camDist);
        density *= 1.0 - fadeT;
    }

    outDensity = max(0.0, density);
}

// [HanPi] HP_EvaluateHpHpPhiFwdTopHeightProxy —— 用低云天气图重建的"柱厚度代理"，
// 只服务于 phi_fwd 的边界受光判断与云底软高度，不参与密度本身。
// 参考用 LoCoverTopMax/LoCoverTopStr 对 coverage 做一次 Möbius 抬升后归一化；
// 我们原来写死 `coverageHeight * 1.2 / 2.0`（只对 max=2.2/str=1 的默认档近似）。
float hpPhiFwdTopProxy(vec2 worldXZ, HPCloudConfig cfg, sampler2D weatherTex)
{
    vec2 windUV = vec2(0.862, 0.507) * (14.0 * 0.35 * cfg.time);
    vec2 uv = (worldXZ - windUV) / HP_WEATHER_TILE;
    float ch = hpSaturate(texture(weatherTex, uv).r * cfg.coverage * cfg.covMapScale);
    // _HP_LoCoverTopCurvePow 未暴露 ⇒ 指数 1（与参考默认曲线一致）
    float scale = mix(1.0, max(cfg.loCoverTopMax, 1.0),
        ch * clamp(cfg.loCoverTopStr, 0.0, 1.0));
    return hpSaturate(ch * scale / max(cfg.loCoverTopMax, 1.0));
}

// ---------------------------------------------------------------------------
// hpEvaluateSunLuminance —— 锥形光步 + hp_phi_fwd 漫射场 + Hillaire MS octaves
// （HanPi EvaluateSunLuminance 逐式移植）
// ---------------------------------------------------------------------------
vec3 hpEvaluateSunLuminance(vec3 positionAlt, float currentLocalHeight,
    HPCloudConfig cfg, vec3 sunColor, float phaseFunction[3],
    sampler2D weatherTex, sampler3D baseTex, sampler3D erosionTex,
    out float lightExtinctionOD, out float phiFwd)
{
    float totalLightDistance = 0.0;
    vec3 luminance = vec3(0.0);
    lightExtinctionOD = 0.0;
    phiFwd = 0.0;

    // GetCloudVolumeIntersection_Light：光 ray 从体积内出发必穿出层顶
    float originRadius = length(positionAlt + vec3(0.0, cfg.planetRadius, 0.0));
    float topRadius = cfg.planetRadius + cfg.topHeight;
    float b = originRadius * cfg.sunDir.y;
    float c0 = originRadius * originRadius - topRadius * topRadius;
    float disc = max(b * b - c0, 0.0);
    totalLightDistance = -b + sqrt(disc);

    // 注：参考的 GetCloudVolumeIntersection_Light 还有半句"太阳射线被行星挡住 ⇒ 无直射光"
    // （`RaySphereIntersection(originWS, dir, _EarthRadius)`）。这里**故意不移植**：
    // 本仓两个消费者传进来的 sunDir 在太阳落到地平线下时已经被翻转成"月亮方向"
    // （volumetric_clouds_v5.comp: `lightDirection = sunElevation >= 0 ? sun : -sun`），
    // 于是 sunDir.y 恒 ≥ 0、该分支永不触发；照搬只会让预览器（不翻转）在夜间与游戏分叉。

    {
        // ── 锥形采样（自适应首步：步数=精度）──
        float coverDist = clamp(totalLightDistance, 0.0, HP_CONE_MAX_DISTANCE);
        int numSteps = clamp(cfg.lightSteps, 4, 12);
        float r = HP_CONE_RATIO;
        float w0 = coverDist * (r - 1.0) / max(pow(r, float(numSteps)) - 1.0, 1.0e-4);
        w0 = max(w0, HP_CONE_MIN_STEP);

        float extinctionSum = 0.0;
        float kappaODSum = 0.0;
        float T_cum = 1.0;
        float curWidth = w0;
        float cumDist = 0.0;

        // ── phi_fwd 源置信度（边界受光 + 云底衰减）──
        float slabH = hpSlab(cfg);
        // phi_fwd 关闭时没有任何消费者 ⇒ 这 1 次 2D 采样不必付（每光步都采一次）。
        float topProxy = 0.0;
        if(cfg.phiFwdIntensity > 0.0)
            topProxy = hpPhiFwdTopProxy(positionAlt.xz, cfg, weatherTex);
        // 边界受光置信度：4 邻域差分 = 每次光步调用 5 次额外天气采样——
        // 默认关闭（phiFwdBoundary=0），面板可开（0.5~1.0）。
        float boundaryConfidence = 1.0;
        if(cfg.phiFwdBoundary > 0.01)
        {
            float sampleStep = clamp(HP_WEATHER_TILE * 0.001, 25.0, 200.0);
            float hN[4];
            vec2 offs[4] = vec2[4](vec2(-sampleStep, 0.0), vec2(sampleStep, 0.0),
                vec2(0.0, -sampleStep), vec2(0.0, sampleStep));
            for(int k = 0; k < 4; ++k)
                hN[k] = hpPhiFwdTopProxy(positionAlt.xz + offs[k], cfg, weatherTex);
            float dHdx = (hN[1] - hN[0]) * slabH / max(2.0 * sampleStep, 1.0);
            float dHdz = (hN[3] - hN[2]) * slabH / max(2.0 * sampleStep, 1.0);
            vec3 topNormalWS = normalize(vec3(-dHdx, 1.0, -dHdz));
            float nDotL = dot(topNormalWS, cfg.sunDir);
            float boundaryLitWarp = hpSaturate((nDotL + 0.5) / 1.5);
            boundaryConfidence = mix(1.0, boundaryLitWarp,
                clamp(cfg.phiFwdBoundary, 0.0, 1.0));
        }

        float phiFwdBottomHeight = currentLocalHeight + cfg.phiFwdDepthBias;
        // 参考：_HP_BottomSmoothHeight * lerp(1,4,columnThickness)（原来写死 0.08，
        // 于是面板里调「云底平滑」对 phi_fwd 的云底衰减完全无效）。
        float phiFwdBottomSoftHeight = max(cfg.bottomSmoothHeight * mix(1.0, 4.0, topProxy), 0.001);
        // 参考：DepthPow<=0 表示**禁用**深度衰减（srcConfidence=1）；写成 max(.,1e-3)
        // 会把 exp(-x*1e-3)≈1 ⇒ srcBottomConfidence≈0 ⇒ 漫射场整条被掐掉。
        float srcBottomConfidence = (cfg.phiFwdDepthPow > 0.0)
            ? 1.0 - exp(-max(phiFwdBottomHeight, 0.0) / phiFwdBottomSoftHeight
                * cfg.phiFwdDepthPow)
            : 1.0;
        float srcConfidence = boundaryConfidence * srcBottomConfidence;

        for(int j = 0; j < 12; ++j)
        {
            if(j >= numSteps) break;
            float stepWidth = min(curWidth, coverDist - cumDist);
            if(stepWidth <= 0.0) break;

            float dist = cumDist + stepWidth * 0.5;
            vec3 samplePosWS = positionAlt + cfg.sunDir * dist;

            float lightDensity; float lightLocalHeight;
            // [HanPi] mipOffset = j/(N-1)*3：越远的光步用越粗的 base 噪声（走 mip 链）。
            float lightMip = float(j) / max(float(numSteps - 1), 1.0) * 3.0;
            hpEvaluateCloudProperties(samplePosWS, slabH, lightMip,
                cfg.lightErosion <= 0.5, cfg,
                weatherTex, baseTex, erosionTex, lightDensity, lightLocalHeight);

            float sigma_t = lightDensity;             // sigmaT ≡ 1
            float sigma_s = sigma_t * HP_PHIFWD_OMEGA0;
            float localOD = sigma_t * stepWidth;
            float qSrc = sigma_s * stepWidth;
            float invD = sigma_t;

            // hp_phi_fwd 1D 格林函数：φ += T_src·(Q/D)·exp(−∫κ ds)·(1/r)
            float kappaStep = localOD * HP_PHIFWD_KAPPA_SCALE;
            float kappaToCenter = kappaODSum + kappaStep * 0.5;
            float perSrcExp = exp(-kappaToCenter);
            float msBuild = 1.0 - exp(-(extinctionSum + localOD * 0.5) * max(cfg.phiFwdMSBuild, 0.0));
            float invR = 1.0 / max(dist, stepWidth * 0.5);
            phiFwd += T_cum * qSrc * invD * srcConfidence * msBuild * perSrcExp * invR;

            extinctionSum += localOD;
            kappaODSum += kappaStep;
            float T_step = exp(-localOD * (1.0 - HP_PHIFWD_OMEGA0));
            T_cum *= T_step;

            // OD 上限早退：MS octave 在 exp(-25·att²) 后贡献 <0.007——
            // 厚云芯的光步不必走完全程（性能）。
            if(extinctionSum > 25.0) break;

            cumDist += stepWidth;
            curWidth *= HP_CONE_RATIO;
        }
        lightExtinctionOD = extinctionSum;

        // ── Hillaire 三参数 MS octaves ──
        vec3 extinction = vec3(1.0) * extinctionSum;
        for(int o = 0; o < 3; ++o)
        {
            if(o >= cfg.msOctaves) break;
            float attFactor = pow(clamp(cfg.msAttenuation, 0.05, 1.0), float(o));
            float conFactor = pow(clamp(cfg.msContribution, 0.05, 1.0), float(o));
            vec3 transmittance = exp(-extinction * attFactor);
            luminance += transmittance * sunColor * phaseFunction[o] * conFactor;
        }
    }

    // 本仓专有的能量总倍率（游戏 UBO ueCloudA.w / 预览 --ue-lum）。
    // 参考里没有这一项（它由 HDRP 的曝光体系吸收），本仓用 CameraUBO 的太阳能量
    // 标定 ≈ 参考的 1/3.6，所以在这里补一次；**只能乘在太阳项上**，环境光走
    // ambientTop/ambientBottom 自己的倍率，否则两个量级会一起漂。
    // 2026-10-25 之前这个字段是"写进结构体、从不被读"的死 lane（预览 3.6 / 游戏 3.0）：
    // 于是太阳项只有标定值的 1/3.6，云只能靠环境光撑亮度 → 整片发平、没有受光面。
    return luminance * max(cfg.luminanceScale, 0.0);
}

// ---------------------------------------------------------------------------
// hpEvaluateCloudStep —— 单步散射积分（HanPi EvaluateCloud 的积分段，逐式移植）
//
// 与参考的唯一结构差异：**光步结果由调用方传入**，不再在这里内部求值。
// 理由（2026-10-28 性能轮，有账可算）：光步是云 pass 的绝对大头 ——
//   每命中步：lightSteps(8) × (weather 2D + base 3D + erosion 3D) ≈ 24 次体积采样
//   视线步：weather + base + erosion = 3 次
// 而相邻两个命中步只隔 stepSmall（≈ 9~30 格），太阳辐亮度/自阴影 OD 在这么短的
// 距离里几乎不变。trace 里按 cfg.lightReuse 每 N 步重算一次、中间复用，
// 公式本身（散射源 / phi_fwd / 环境光 / Beer-Lambert 积分）一个字都没改。
// ---------------------------------------------------------------------------
void hpEvaluateCloudStep(float density, float localHeight, float slabH, float h,
    vec3 positionAlt, float stepSize, float relativeRayDistance,
    vec3 totalLuminance, float lightExtinctionOD, float phiFwd,
    HPCloudConfig cfg, inout vec3 inScattering, inout float transmittance)
{
    // relativeRayDistance 在本仓没有被消费（参考用它插值 sunColor0/1，HDRP 里
    // 两者同值 ⇒ 空操作），保留形参是为了让"参考的 EvaluateCloud 形参表"留在签名上。
    float extinction = density;                 // sigmaT ≡ 1
    float stepTransmittance = exp(-extinction * stepSize);

    // ── 方向性散射源积分（低密度边缘由本步散射 OD 决定）──
    float scatterOD = extinction * HP_PHIFWD_OMEGA0 * stepSize;
    float scatterSourceAmount = 1.0 - exp(-scatterOD / max(cfg.scatterODScale, 0.001));
    scatterSourceAmount = pow(hpSaturate(scatterSourceAmount),
        max(cfg.scatterCurvePow, 0.01));

    vec3 integScatt = totalLuminance * scatterSourceAmount;

    // ── hp_phi_fwd 加性漫射注入（Intensity 0.6 / Compress 1.0）──
    // 参考是 `if (_HP_HpHpPhiFwd_Intensity > 0.0)` 才进；Intensity=0 时这里原来也会
    // 走一次 exp() 再乘 0（每命中步一次），白花钱。
    if(cfg.phiFwdIntensity > 0.0)
    {
        float phiFwdScalar = phiFwd * max(cfg.phiFwdIntensity, 0.0);
        float phiFwdMapped = (cfg.phiFwdCompress > 0.0)
            ? (1.0 - exp(-phiFwdScalar * cfg.phiFwdCompress)) / cfg.phiFwdCompress
            : phiFwdScalar;
        vec3 phiFwdLuminance = phiFwdMapped * cfg.sunColor;
        integScatt += phiFwdLuminance - phiFwdLuminance * stepTransmittance;
    }

    // ── 环境光（upwardAO = 光路 OD × sinElev 的竖直折算 + 底部几何代理）──
    float sinElev = max(cfg.sunDir.y, 0.05);
    float zenithOD = lightExtinctionOD * sinElev;
    float upwardAO = exp(-zenithOD * max(cfg.aoUpwardScale, 0.0));
    vec3 ambientTerm = cfg.ambientTop * upwardAO
        + cfg.ambientBottom * (1.0 - hpSaturate(h));
    integScatt += ambientTerm * scatterSourceAmount;

    inScattering += integScatt * transmittance;
    transmittance *= stepTransmittance;
}

// ---------------------------------------------------------------------------
// hpEvaluateCloud —— 参考 1:1 语义的入口（EvaluateCloud 内部自己算光步）。
// 主路径（hpTraceVolumetricRay）走的是 hpEvaluateCloudStep + 光步复用；
// 这个入口保留给"一步一算"的对照/诊断，公式与 step 版完全一致。
// ---------------------------------------------------------------------------
void hpEvaluateCloud(float density, float localHeight, float slabH, float h,
    vec3 positionAlt, float stepSize, float relativeRayDistance,
    HPCloudConfig cfg, float phaseFunction[3],
    sampler2D weatherTex, sampler3D baseTex, sampler3D erosionTex,
    inout vec3 inScattering, inout float transmittance)
{
    float lightExtinctionOD;
    float phiFwd;
    vec3 totalLuminance = hpEvaluateSunLuminance(positionAlt, localHeight, cfg,
        cfg.sunColor, phaseFunction, weatherTex, baseTex, erosionTex,
        lightExtinctionOD, phiFwd);
    hpEvaluateCloudStep(density, localHeight, slabH, h, positionAlt, stepSize,
        relativeRayDistance, totalLuminance, lightExtinctionOD, phiFwd, cfg,
        inScattering, transmittance);
}

// ---------------------------------------------------------------------------
// hpTraceVolumetricRay —— 球壳求交 + 两层自适应步进（HanPi 主 trace 逐式移植）
// ---------------------------------------------------------------------------
// 诊断通道（预览器 --hp-debug 读；游戏端从不读）。避免为了看区间/迭代数
// 再复制一份 trace 循环 —— 复制出来的那份必然与原版漂移。
//   x = marchStart  y = totalDist  z = iterCount  w = hit
vec4 gHpDebugInfo = vec4(0.0);

vec4 hpTraceVolumetricRay(vec3 rayOriginPlanet, vec3 rayDir, float maxDist,
    HPCloudConfig cfg, sampler2D weatherTex, sampler3D baseTex, sampler3D erosionTex,
    out float hitDistance)
{
    hitDistance = maxDist;

    // 相机（altitude 空间）在这里反解一次：距离淡出要它，而它只有射线原点知道。
    // 由 trace 统一填 ⇒ 视线步进与光步（经 hpEvaluateCloud 传值）共用同一套坐标，
    // 两个 comp（预览 mode 8 / 游戏 v5）都不必各自维护。
    cfg.cameraAlt = rayOriginPlanet - vec3(0.0, cfg.planetRadius, 0.0);

    // 云距上限：viewDistance 既是淡出的结束点，也是步进的硬上限。
    // 少了这一条，掠射射线会走进 100~300 km 的球壳弦长里 —— 那里密度虽然已被
    // 淡出清零，但每步仍要花 3 次 3D 采样，纯浪费（预览不加 --view-dist 时
    // maxDist 是 1e9，实测就是这么跑的）。
    if(cfg.viewDistance > 1.0) maxDist = min(maxDist, cfg.viewDistance);
    hitDistance = maxDist;

    const float slabThickness = hpSlab(cfg);
    const float originRadius = length(rayOriginPlanet);
    const float bottomRadius = cfg.planetRadius + cfg.bottomHeight;
    const float topRadius = cfg.planetRadius + cfg.topHeight;

    const float tBottom = hpIntersectShell(originRadius, rayDir.y, bottomRadius);
    const float tTop = hpIntersectShell(originRadius, rayDir.y, topRadius);

    float marchStart;
    float marchEnd;
    {
        bool insideShell = (tBottom <= 0.0 && tTop > 0.0);
        if(insideShell)
        {
            marchStart = 0.0;
            marchEnd = (rayDir.y >= 0.0) ? tTop : tBottom;
        }
        else
        {
            marchStart = max(min(tBottom, tTop), 0.0);
            marchEnd = max(tBottom, tTop);
        }
    }
    float totalDist = min(marchEnd - marchStart, maxDist - marchStart);
    gHpDebugInfo = vec4(marchStart, totalDist, 0.0, maxDist);
    if(totalDist <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);

    // ── 步长：距离自适应三层 cap（HanPi HP_STEP_VIEW_DIV = 8）──
    float stepLargeRaw = totalDist / max(float(cfg.viewSteps), 1.0);
    float stepLargeNearCap = slabThickness * 0.0625;   // slabH/16
    float stepLargeFarCap = slabThickness * 0.5;       // slabH/2
    float farDistRef = 50000.0;

    vec3 inScattering = vec3(0.0);
    float transmittance = 1.0;
    // 2026-10-26：起始抖动只能按**第一步的步长**算。
    // 原来乘的是 stepLargeNearCap（= slabH/16 = 131 格），而近处实际第一步
    // 只有 stepLargeRaw = totalDist/56 ≈ 37 格 ⇒ 抖动把整条采样阶梯一次挪了
    // 3~4 步，逐帧换相位时等值面跟着跳，云缘每帧位移上百格（10 km 处 ≈8 像素）——
    // 这就是"闪"的直接来源，和风偏移那个 bug 是两回事。
    float firstStep = min(stepLargeRaw, stepLargeNearCap);
    float dist = cfg.jitter * firstStep;
    float hit = maxDist;

    // ── 相函数预计算（per-octave 偏心率衰减）──
    float cosAngle = dot(rayDir, cfg.sunDir);
    float phaseFunction[3];
    for(int o = 0; o < 3; ++o)
    {
        float ecc = pow(clamp(cfg.msEccentricity, 0.1, 1.0), float(o));
        phaseFunction[o] = hpHG(cosAngle, cfg.forwardEcc * ecc)
            + hpHG(cosAngle, -cfg.backwardEcc * ecc);
    }

    int iterCount = 0;
    int maxIter = clamp(cfg.viewSteps, 8, 256) * 4;

    // ── 光步复用缓冲（cfg.lightReuse，见结构体注释里的成本账）──
    // lightStride = 1 ⇒ 每个命中步都重算（= 参考语义，逐位等价）；
    // lightStride = N ⇒ 每 N 步算一次，中间直接复用上一次的太阳项/自阴影 OD/phi_fwd。
    int lightStride = int(clamp(cfg.lightReuse, 1.0, 8.0));
    vec3 cachedLum = vec3(0.0);
    float cachedLightOD = 0.0;
    float cachedPhiFwd = 0.0;
    int lightTick = 0;

    while(dist < totalDist && iterCount < maxIter)
    {
        iterCount++;

        float distNorm = hpSaturate(dist / farDistRef);
        float absDist = marchStart + dist;
        float viewCap = absDist / 8.0;
        float slabCap = mix(stepLargeNearCap, stepLargeFarCap, distNorm * distNorm);
        float stepLarge = min(stepLargeRaw, min(viewCap, slabCap));
        float stepSmall = stepLarge * 0.25;

        vec3 pos = rayOriginPlanet + absDist * rayDir;
        vec3 posAlt = pos - vec3(0.0, cfg.planetRadius, 0.0);

        // ── 简单模式门控（无 erosion 快速探测）──
        float simpleDensity; float simpleLocalHeight;
        hpEvaluateCloudProperties(posAlt, slabThickness, 1.0, true, cfg,
            weatherTex, baseTex, erosionTex, simpleDensity, simpleLocalHeight);

        if(simpleDensity > 0.001)
        {
            float fullDensity; float fullLocalHeight;
            hpEvaluateCloudProperties(posAlt, slabThickness, 0.0, false, cfg,
                weatherTex, baseTex, erosionTex, fullDensity, fullLocalHeight);

            if(fullDensity > 0.001)
            {
                hit = min(hit, absDist);
                // 这里原来还有 `float stepT = exp(-sigmaT * stepSmall);` —— 算完从不使用，
                // 真正的步内透射率在 hpEvaluateCloud 里（stepTransmittance）。删掉省一个 exp。

                float relDist = dist / max(totalDist, 1.0);
                if(lightTick <= 0)
                {
                    cachedLum = hpEvaluateSunLuminance(posAlt, fullLocalHeight, cfg,
                        cfg.sunColor, phaseFunction, weatherTex, baseTex, erosionTex,
                        cachedLightOD, cachedPhiFwd);
                    lightTick = lightStride;
                }
                --lightTick;
                hpEvaluateCloudStep(fullDensity, fullLocalHeight,
                    slabThickness, hpNormalizedHeight(pos, cfg), posAlt,
                    stepSmall, relDist, cachedLum, cachedLightOD, cachedPhiFwd,
                    cfg, inScattering, transmittance);

                // 用 cfg.minTransmittance（参考 _HP_MinTransmittance），
                // 原来硬编码 0.003 ⇒ 那个 UBO 字段是个死 lane。
                if(transmittance < max(cfg.minTransmittance, 1.0e-4))
                {
                    transmittance = 0.0;
                    break;
                }
            }
            dist += stepSmall;
        }
        else
        {
            dist += stepLarge;
        }
    }

    // ── 远处淡出 ──
    // 2026-10-25：**已下移到逐采样**（见 hpEvaluateCloudProperties 末尾的
    // 「距离淡出」）。这里原来对整条射线乘一个 (1-smoothstep(fadeBegin, viewDist, hit))，
    // 是"用首个命中距离代表整条射线"的近似：掠射射线会整块保留/整块消失，
    // 且 fadeBegin 之外没有任何衰减 ⇒ 远场采样噪声原样留在画面上（扇状条纹）。
    // 现在淡出进了密度 ⇒ σt 本身就是距离的函数，透射率自然随之收敛，不需要补丁。

    hitDistance = hit;
    gHpDebugInfo.z = float(iterCount);
    gHpDebugInfo.w = hit;
    return vec4(inScattering, transmittance);
}

// ===========================================================================
// 噪声烘焙（V5_NOISE_BAKER）
//   weather 2D：R = LoCoverage（FBM）/ G = CloudType / B = ScMask（worley）
//   base   3D：R = PerlinWorley（baseShape 源）
//   detail 3D：RGBA = WispyLo / WispyHi / BillowyLo / BillowyHi
// 平铺铁律：mod 周期 == 频率、整数格胞；hash 带偏移（fract 型 hash 零向量退化）。
// ===========================================================================
#ifdef V5_NOISE_BAKER

vec2 hpHash22(vec2 p)
{
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}
float hpHash13(vec3 p3in)
{
    vec3 p3 = fract(p3in * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

float hpWorley2D(vec2 p, float tile)
{
    vec2 cell = floor(p);
    vec2 f = fract(p);
    float best = 8.0;
    for(int y = -1; y <= 1; ++y)
    for(int x = -1; x <= 1; ++x)
    {
        vec2 o = vec2(float(x), float(y));
        vec2 jitter = hpHash22(mod(cell + o, vec2(tile)) + vec2(19.19, 7.7));
        vec2 d = o + jitter - f;
        best = min(best, dot(d, d));
    }
    return 1.0 - sqrt(best);
}

float hpValue2D(vec2 p, float tile)
{
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hpHash13(vec3(mod(cell, vec2(tile)) + 19.19, 7.7));
    float b = hpHash13(vec3(mod(cell + vec2(1, 0), vec2(tile)) + 19.19, 7.7));
    float c = hpHash13(vec3(mod(cell + vec2(0, 1), vec2(tile)) + 19.19, 7.7));
    float d = hpHash13(vec3(mod(cell + vec2(1, 1), vec2(tile)) + 19.19, 7.7));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// hpHash33 在文件后面定义，这里先声明（GLSL 要求先声明后调用）。
vec3 hpHash33(vec3 p);

float hpWorley3D(vec3 p, vec3 tile)
{
    vec3 cell = floor(p);
    vec3 f = fract(p);
    float best = 8.0;
    for(int z = -1; z <= 1; ++z)
    for(int y = -1; y <= 1; ++y)
    for(int x = -1; x <= 1; ++x)
    {
        vec3 o = vec3(float(x), float(y), float(z));
        vec3 c = mod(cell + o, tile) + 19.19;
        // ⚠⚠ 2026-10-26 **"矩形方块云"的真正根因**：
        // 原来抖动是 `vec3(hash13(c), hash13(c+31.7), hash13(c+61.3))` —— 三个分量
        // 都是**同一个标量哈希**的平移版（+31.7 乘 0.1031 后只是三个轴同时平移 3.27），
        // 于是三个分量高度相关，格点被压在格的**对角线**附近 ⇒ Voronoi 胞退化成
        // 轴对齐的方块 ⇒ 阈值一切就是边长 = 格胞边长（≈1111 格）的方砖。
        // 这就是 --model v5top 俯视密度图上一块块方砖的来源，也是"云挤成矩形"的来源。
        // 修法：用真正的三维哈希 hpHash33（三个轴用不同的乘子 0.1031/0.1030/0.0973，
        // 这也是 Perlin 一直在用的那个），映射到 [0,1] 作为格点抖动。
        vec3 jitter = hpHash33(c) * 0.5 + 0.5;
        vec3 d = o + jitter - f;
        best = min(best, dot(d, d));
    }
    return 1.0 - sqrt(best);
}

vec3 hpHash33(vec3 p)
{
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx) * 2.0 - 1.0;
}
float hpPerlin3D(vec3 p, vec3 tile)
{
    vec3 cell = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n000 = dot(hpHash33(mod(cell + vec3(0, 0, 0), tile) + 19.19), f - vec3(0, 0, 0));
    float n100 = dot(hpHash33(mod(cell + vec3(1, 0, 0), tile) + 19.19), f - vec3(1, 0, 0));
    float n010 = dot(hpHash33(mod(cell + vec3(0, 1, 0), tile) + 19.19), f - vec3(0, 1, 0));
    float n110 = dot(hpHash33(mod(cell + vec3(1, 1, 0), tile) + 19.19), f - vec3(1, 1, 0));
    float n001 = dot(hpHash33(mod(cell + vec3(0, 0, 1), tile) + 19.19), f - vec3(0, 0, 1));
    float n101 = dot(hpHash33(mod(cell + vec3(1, 0, 1), tile) + 19.19), f - vec3(1, 0, 1));
    float n011 = dot(hpHash33(mod(cell + vec3(0, 1, 1), tile) + 19.19), f - vec3(0, 1, 1));
    float n111 = dot(hpHash33(mod(cell + vec3(1, 1, 1), tile) + 19.19), f - vec3(1, 1, 1));
    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
               mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}

// 2D **梯度**噪声（Perlin）。天气图专用。
// 为什么不能用 hpValue2D：value noise 的格胞内部是"平顶平台"，被 coverage 阈值
// 一切出来，等值线就是**轴对齐的方块** —— 云于是挤在一格一格的矩形区域里，
// 而且怎么调参数都改不掉形状（方块是晶格决定的，不是参数决定的）。
// 梯度噪声每个格胞内是线性斜坡、格点处为 0，等值线是曲线的，阈值化后没有平台。
// 平铺仍然成立：格点索引用 mod(cell, tile)。
float hpGradient2D(vec2 p, float tile)
{
    vec2 cell = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    vec2 g00 = hpHash22(mod(cell + vec2(0.0, 0.0), vec2(tile)) + 19.19) * 2.0 - 1.0;
    vec2 g10 = hpHash22(mod(cell + vec2(1.0, 0.0), vec2(tile)) + 19.19) * 2.0 - 1.0;
    vec2 g01 = hpHash22(mod(cell + vec2(0.0, 1.0), vec2(tile)) + 19.19) * 2.0 - 1.0;
    vec2 g11 = hpHash22(mod(cell + vec2(1.0, 1.0), vec2(tile)) + 19.19) * 2.0 - 1.0;
    float n00 = dot(g00, f - vec2(0.0, 0.0));
    float n10 = dot(g10, f - vec2(1.0, 0.0));
    float n01 = dot(g01, f - vec2(0.0, 1.0));
    float n11 = dot(g11, f - vec2(1.0, 1.0));
    return mix(mix(n00, n10, u.x), mix(n01, n11, u.x), u.y);
}

// ---- mode 0：weather 2D ----
vec4 hpRenderWeather(vec2 uv)
{
    const float tile = 8.0;
    vec2 p = uv * tile;
    // **domain warp**（打散 value-noise 晶格轴对齐——用户报"条状云"的主因）：
    // 用一层低频 FBM 把采样坐标揉乱，云团轮廓变成有机的羽状。
    // ⚠ warp 幅度必须远小于它自己的格胞，否则不是"打散"而是**折叠**：
    // 这里 warp 的格胞 = p 的 2 个单位（≈6 km），幅度 1.6 意味着位移半宽
    // ±0.8 个单位（±2.4 km）—— 位移梯度逼近 1，空间被折成**细丝**，
    // 阈值一切就是"长条/片状"（俯视密度图实测：coverage 通道里是成片的
    // 1D 条纹，而 base 噪声是圆的）。1.6 → 0.4（±0.2 单位 ≈ ±600 m），
    // 既保留打散晶格对齐的作用，又不折叠。
    vec2 warp = vec2(
        hpValue2D(p * 2.0 + 71.3, 16.0) - 0.5,
        hpValue2D(p * 2.0 + 137.9, 16.0) - 0.5) * 1.6;
    vec2 q = p + warp;
    // R = LoCoverage：三档 FBM。⚠ **禁止旋转采样坐标**——旋转一个可平铺场
    // 会在平铺边界产生不连续（= 每 24km 一道接缝，天空上就是"条柱状"，
    // 用户实测）。轴对齐的打散交给 domain warp（warp 场自身可平铺，
    // warp/offset 都不破坏平铺；旋转和非整数倍频才会破坏）。
    //
    // ⚠ 2026-10-25 **最低倍频必须有足够多的格胞**：原来第一档是
    // `q*0.25, tile 2` ⇒ 整张 24km 天气图上只有 **2×2 = 4 个格点值**，
    // 双线性插值出来的等值线是双曲线臂，被阈值一切就变成"沿世界 X 轴的长条"。
    // 量化证据（新增 `--model v5top` 俯视密度切片）：密度场自相关半宽
    //   沿 X 126 px : 沿 Z 26 px = **4.85 : 1**（18.8 m/px ⇒ 2.4 km vs 0.5 km）。
    // 改成 4/8/16 格胞（6/3/1.5 km）后各向同性。平铺铁律不变：freq × 8(uv 跨度) == tile。
    // ⚠ 2026-10-26：三档全部换成**梯度噪声**（见 hpGradient2D 的说明）。
    // value noise 的平台在阈值化后就是方块，这是"云挤在矩形区域里"的根因，
    // 和 coverage/covMapScale/云量都无关 —— 所以怎么调参数形状都不变。
    // 权重保持 4/8/16 三档（6/3/1.5 km），相位各自错开。
    // ⚠ 2026-10-27 第二次修：权重从 0.45/0.30/0.25 改成 0.26/0.38/0.36。
    // 原因（--model v5top 俯视密度图实测）：最低倍频 4 格胞 = 6 km，权重 0.45
    // 时**一个格胞长出一个云斑**，整张图就是 6 km 见方的网格阵列 —— 在天上
    // 就是"云挤在一条条区域里"。把能量移到 3 km / 1.5 km 两档，网格周期降到
    // 3 km 且被 1.5 km 档打碎，人眼就看不到规则排列了。
    // ⚠ 2026-10-27 第三次修（俯视密度图定案）：三档带限噪声不够。
    // 现象：密度图上云是**按 6 km 网格排列的斑块阵列**，成行成列、中间是空档，
    // 在天上就是"云挤成一条一条的区域"。
    // 原因：只有 3 个倍频时，和的**峰值几乎都落在格点附近**（相位一致），
    // 阈值一卡就只剩"每格一个斑"。真实天气图有 5~8 个倍频，峰值位置相互错开，
    // 边界才有分形感。这里加到 **5 档**（6 / 3 / 1.5 / 0.75 / 0.375 km），
    // 权重按 1/f^0.35 缓降（比 1/f 更"平"，让细节层有足够能量打断规则排列）。
    // 平铺：每档 tile = 4×f，正好都是 8 个 p 单位一个周期（24 km），与以前一致。
    // 频率随 HP_WEATHER_TILE 同步 ×4：周期 = tile/f 个 p 单位，两者一起放大
    // ⇒ 周期仍是"整张图 = 96 km"，而格胞尺寸保持 6/3/1.5/0.75/0.375 km。
    // ⚠⚠ 2026-10-27 **第四修：最高两档在 aliasing**（这就是"一小块还是方"）。
    // 天气图是 1024² 覆盖 8 个 p 单位 ⇒ 128 texel/单位；第 k 档倍频 f 的格胞
    // 只有 128/f 个 texel：
    //   f=2 → 64 texel/格胞（6 km）   ✓
    //   f=4 → 32（3 km）              ✓
    //   f=8 → 16（1.5 km）            ✓
    //   f=16→ 8（750 m）              ← 勉强
    //   f=32→ 4（375 m）              ✗ **欠采样**：4 个 texel 画一个格胞，
    //                                    烘焙出来就是 4×4 texel 的方块阵列，
    //                                    在游戏里就是"一小块一小块的方形"。
    // 所以砍掉 f=32，并把 f=16 的权重压低；能量回到 Nyquist 安全的三档。
    // 想要更细的结构必须先把天气图分辨率提到 2048²（texel 47 m）。
    // ⚠⚠ 2026-10-27 **第五修：周期算错了**。
    //   period(p 单位) = tile / f
    // 上一版是 tile=4/8/16/32 配 f=2/4/8/16 ⇒ period = 只有 **2 个 p 单位**
    // （HP_WEATHER_TILE 96 km / 8 单位 × 2 = **24 km**），也就是说我把"格胞"
    // 放大了 4 倍，却忘了把 tile 一起放大 —— 云图仍然每 24 km 原样重复一遍。
    // 视野 44 km 里能看到近 2 个周期，所以天空"一半密、一半疏"，而且那个疏密
    // 分界会随你走动整体平移。这就是用户看到的"一边密一边疏"。
    // 正确写法：tile = 8·f ⇒ period = 8 个 p 单位 = **96 km**（视野内不重复），
    // 格胞仍是 1/f = 6 / 3 / 1.5 / 0.75 km。
    // ⚠⚠ 2026-10-28 **天气图 1024² → 512²**，同时砍掉最细一档倍频。
    // 512² 覆盖 8 个 p 单位 ⇒ **64 texel/单位**：
    //   f=2 → 32 texel/格胞（6 km）    ✓
    //   f=4 → 16（3 km）                ✓
    //   f=8 → 8（1.5 km）               ✓ 贴 Nyquist
    //   f=16→ 4（750 m）                ✗ 必然烘出 4×4 的方块阵列（第 4 修的历史结论）
    // 所以去掉 f=16，把它的 0.20 权重按比例分给前三档（0.22/0.28/0.30/0.20 →
    // 0.275/0.35/0.375）。丢掉的是 750 m 那一档，而 v5 的云缘细节本来就来自 3D
    // base 噪声（1.2 km 格胞）、天气图只负责"哪片区域有云"，所以观感无损失。
    float cov = 0.275 * (hpGradient2D(q * 2.0 + 3.1, 16.0) + 0.5)
              + 0.350 * (hpGradient2D(q * 4.0 + 17.9, 32.0) + 0.5)
              + 0.375 * (hpGradient2D(q * 8.0 + 41.3, 64.0) + 0.5);
    // 增益重标定：梯度噪声的幅度只有 value noise 的约 0.7 倍（±0.35 vs ±0.5），
    // 沿用 value noise 的 `*1.6-0.18` 会让覆盖率整体掉一截（云变稀）。
    // 用 (x-0.5)*2.4+0.66 把它拉回原来的分布：均值 0.66、约 40% 区域饱和到 1。
    cov = clamp((cov - 0.5) * 2.4 + 0.66, 0.0, 1.0);
    // G = CloudType：0=Cu → 1=Cb 的低频渐变（区域天气）。
    // 原来 `p*0.125, tile 1` ⇒ 整张图上只有 **1 个格点 = 常数**，云型永远不变
    // （这也是"云型单一"的直接原因）。2 格胞 = 12 km 的区域渐变，符合它的定位。
    float type = clamp(0.5 + 0.9 * (hpValue2D(p * 0.25 + 91.1, 2.0) - 0.5), 0.0, 1.0);
    // B = ScMask：worley 团块收紧（局部小块，不再连片成"长条平板"）
    float blob = 0.62 * hpWorley2D(p * 0.75, 6.0)
               + 0.38 * hpWorley2D(p * 1.5 + 7.31, 12.0);
    float scMask = smoothstep(0.55, 0.85, blob);
    return vec4(cov, type, scMask, 0.5);
}

// ---- mode 1：base 3D（R = PerlinWorley）----
vec4 hpRenderBase(vec3 coord)
{
    const vec3 tile = vec3(12.0, 12.0, 12.0);
    vec3 p = coord * tile;
    // Perlin-Worley：perlin 做底、worley 顶出鼓包（HZD 经典配方，
    // HanPi 的 Worley128RGBA.r 即此）
    // ⚠⚠ 2026-10-26 **"矩形方块云"的直接原因**：
    // Perlin 噪声的一个格胞就是"一个鼓包"，而这里第一档（12 格胞 = 1 个格胞
    // ≈ 1111 格 ≈ 整朵云的大小）权重高达 0.54 ⇒ **一朵云 = 一个格子**。
    // 格子边界被 8 角点三线性插值抹成轴对齐的圆角矩形，阈值一切出来就是
    // 一坨坨方形砖块（--model v5top 俯视密度图上一眼可见，砖块边长 = 格胞边长）。
    // 真正的云是"很多格胞拼出来的"，所以必须把能量从最低档移走：
    //   0.54/0.29/0.17 → 0.36/0.33/0.31（接近等比，保留一点低频主导）
    //   worley 也从 0.625/0.375 → 0.55/0.45（它负责鼓包，别让它压过 perlin 的有机形状）
    float perlin = 0.360 * hpPerlin3D(p, tile)
                 + 0.330 * hpPerlin3D((p * 2.0 + 11.7).zyx, tile * 2.0)
                 + 0.310 * hpPerlin3D((p * 4.0 + 23.1).yxz, tile * 4.0);
    perlin = perlin * 0.5 + 0.5;
    // 同上：第二档 worley 换轴排列，避免两档格胞层在 y 方向重合叠成片层
    float wf = 0.55 * hpWorley3D(p, tile)
             + 0.45 * hpWorley3D((p * 2.0 + 5.7).zyx, tile * 2.0);
    float pw = wf + perlin * (1.0 - wf);
    // 归一化窗口（2026-10-25 标定）：原来的 (pw-0.30)/0.60 烘出来的体积
    // **均值 0.733 / 最小 0.14**（预览 --probe: base3d），也就是"处处都很满"。
    // 覆盖度阈值 `1-coverage+0.1` 于是几乎切不出东西 ⇒ 整层变成一张连续的
    // 雪原（俯视截图就是一整片白）。按同一份 probe 统计把均值压到 ~0.5：
    //   pre-remap 均值 = 0.733*0.60+0.30 = 0.74 ⇒ (pw-0.40)/0.68 → 0.50
    pw = clamp((pw - 0.40) / 0.68, 0.0, 1.0);
    return vec4(pw, 0.0, 0.0, 1.0);
}

// ---- mode 2：erosion 3D（RGBA = WispyLo / WispyHi / BillowyLo / BillowyHi）----
// ⚠ 倍频必须是**整数**：hash 的平铺靠 mod(cell, tile)，tile 一旦是 4.5 / 12.6
// 这种非整数，"最后一个格胞"和"第一个格胞"就不是同一个 hash ⇒ 体积里出现
// 一整块**硬边接缝平面**。2026-10-25 实测：原来的 2.1 / 4.1 / 8.3 / 0.75 / 3.1 / 6.3
// 在云体内部切出 3~4 道水平硬边（远看就是"千层饼"横条），先被误判成步进量化，
// 二分到 --erosion 0 才定位到 detail 噪声。整数倍频既有同样频谱，又保持可平铺。
vec4 hpRenderDetail(vec3 coord)
{
    const vec3 tile = vec3(6.0, 6.0, 6.0);
    vec3 p = coord * tile;
    // ⚠ 每个 octave 必须用**不同的轴排列**（.zyx / .yxz）。
    // 原因（2026-10-25 定位）：格胞噪声的格胞是轴对齐的，而所有 octave 都直接拿
    // 世界 y 当第三维 ⇒ 每个 octave 的"格胞层"都平行于世界水平面、且互相重合，
    // 叠加之后**在 y 方向形成周期 ≈ 格胞尺寸的片层**。detail 周期 1400 格
    // ⇒ 格胞 233 格 ⇒ 层厚 2100 格里正好 9 层，就是用户看到的"一叠横条"。
    // 轴排列是**保格点、保平铺**的（tile 各轴都是 6，置换后 mod 周期不变），
    // 所以既能打散片层，又不会像"旋转采样坐标"那样破坏可平铺性。
    // Wispy：平滑 perlin FBM（丝絮状侵蚀）
    float wispyLo = 0.65 * hpPerlin3D(p, tile)
                  + 0.35 * hpPerlin3D((p * 2.0 + 7.3).zyx, tile * 2.0);
    float wispyHi = 0.60 * hpPerlin3D((p * 4.0 + 17.9).yxz, tile * 4.0)
                  + 0.40 * hpPerlin3D((p * 8.0 + 23.7).zyx, tile * 8.0);
    // Billowy：worley FBM（团块状侵蚀）
    float billowyLo = 0.625 * hpWorley3D(p, tile)
                    + 0.375 * hpWorley3D((p * 2.0 + 9.1).zyx, tile * 2.0);
    float billowyHi = 0.625 * hpWorley3D((p * 3.0 + 13.9).yxz, tile * 3.0)
                    + 0.375 * hpWorley3D((p * 6.0 + 27.3).zyx, tile * 6.0);
    return vec4(clamp(wispyLo * 0.5 + 0.5, 0.0, 1.0),
        clamp(wispyHi * 0.5 + 0.5, 0.0, 1.0),
        clamp(billowyLo, 0.0, 1.0),
        clamp(billowyHi, 0.0, 1.0));
}
#endif // V5_NOISE_BAKER

#endif // CLOUD_HP_CORE_GLSL
