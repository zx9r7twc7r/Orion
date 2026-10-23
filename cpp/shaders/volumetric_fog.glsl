// =====================================================================
// 体积雾（volumetric fog）—— 密度场、ray march、太阳方向自吸收
//
// 契约（改之前先读这一段）：
//   · 与体积光**共用同一份介质**：常量与 fogDensityAt() 都在
//     fog_density_common.glsl。两侧必须逐项同源，否则雾与光柱的密度剖面会对不上。
//   · 输出 FogResult{scattering, transmittance}，**不做合成**：调用方
//     （volumetric_clouds*.comp）自己算 `scene * T + scattering`。
//   · include 顺序有依赖：fog_density_common.glsl → local_light_grid_common.glsl；
//     后者读 cam.*，调用方必须先在文件顶部声明 `cam`。
//
// 参数车道（CameraUBO，偏移不变）：
//   cloudParams7 = (enabled, VFOG_DENSITY_BASE, VFOG_HEIGHT, VFOG_HEIGHT_2)
//   cloudParams8 = (anisotropyG, VFOG_SUNLIGHT_DENSITY, VFOG_FOG_DENSITY, maxDistance)
//   cloudParams9 = (startDistance, VFOG_SUNLIGHT_STEPS, VFOG_RAIN_DENSITY_MUL, reserved)
//   fogExtrasParams = (动态光源强度, 地面锚定强度, 天空环境项强度, 时域抖动强度)
//
// 与参考实现的平台差异（每条都会改变像素，不能随手改回"更简单"的写法）：
//   1) 合成交给调用方；这里只返回散射/透射率；
//   2) 阴影查询用 sunShadowVisibility()（光追阴影图，近图+远图），不是 shadowtex1；
//   3) 抖动 = 固定 4×4 Bayer + 逐帧黄金比相位，不是 BlueNoiseTemporal；
//   4) 云影由调用方经 c3CloudSunTransmittance 携带，这里不再乘 globalCloudShadow；
//   5) 室内门控在调用方，本文件不判 eyeBrightness。
//
// 编译期常量 = 出厂标定：
//   VFOG_DENSITY 0.01 / VFOG_FALLOFF 40 / VFOG_QUALITY 6 /
//   VFOG_SUNLIGHT_ABSORPTION 1.0 / VFOG_SUNLIGHT_STEPLENGTH 5.0
//
// 历史、被排除过的方案、四项扩展的动机，见 docs/decisions/volumetric-fog.md
// =====================================================================
#ifndef VOLUMETRIC_FOG_GLSL
#define VOLUMETRIC_FOG_GLSL

// 参考的 VFOG_DENSITY / VFOG_FALLOFF / VFOG_SUNLIGHT_ABSORPTION /
// VFOG_SUNLIGHT_STEPLENGTH 与 fogDensityAt() 现在是**共享头**：体积雾与
// volumetric_light.comp 的 A/B 空气路径用同一份介质（默认空气光柱已退役，
// 光柱不再单独积分，见 volumetric_light.comp 顶部）。
#include "fog_density_common.glsl"

// 2026-10-23：动态光源（binding 12/13 = 与 rgen 同一份本地光表/空间网格）。
// 必须在本文件之后 include —— 它的 vptLocalLightRange()/localLightIntensity()
// 读 cam（CameraUBO），而调用方在文件顶部已声明 `cam`。
#include "local_light_grid_common.glsl"

// 参考 VFOG_QUALITY（视 march 步数）出厂 6。
const float VFOG_QUALITY = 6.0;

// 2026-10-21：环境项（fogColor）被遮挡时保留的比例。
//
// 参考 VolumetricFog.glsl:218 的 fogColor 是**环境天光**，与太阳方向无关，
// 所以原样移植过来时它完全不吃遮挡 —— 室内/墙后那层空气照样被天光照亮，
// 和"光柱穿墙"是同一个观感的另一半（雾从墙里渗出来）。
//
// 但天光是半球积分，单方向太阳影只是它的粗糙代理：整块压黑会在"有窗但太阳
// 被挡"的房间里错得离谱。所以留一个地板，只压掉被挡掉的那部分。
//   · 1.0 = 关（逐位回到参考移植版）
//   · 0.35 = 出厂（2026-09-25 那一版验证过的档位）
const float FOG_AMBIENT_SHADOW_FLOOR = 0.35;

// 参考 VFOG_SUNLIGHT_STEPS 出厂 4；循环必须有编译期界，实际步数由 UBO 钳制。
const int VFOG_SUNLIGHT_STEPS_MAX = 8;

struct FogResult
{
    vec3 scattering;      // 已积分的入散射（线性空间辐亮度）
    float transmittance;  // 整段透过率
    float firstDistance;  // 雾从多远开始（= 近处留白）
    float opticalDepth;   // 调试用（本版 = -log(T)）
    // 2026-10-23：透射率加权平均距离（与云层同口径，见 volumetric_clouds.comp
    // 末尾的 meanDistAccum/meanDistWeight）。**时域重投影必须用它**：
    // firstDistance 在 startDistance=0（出厂值）时恒为 0，拿它重投影等于
    // "按相机原点重投影"= 不做视差 ⇒ 逐帧抖动会被拖成条。
    float meanDistance;
};

// 参考 VolumetricFog.glsl:75-79 的抖动。
// 参考在 TAA 分支用 BlueNoiseTemporal().y，非 TAA 分支用 bayer64()。本工程
// 的 4 个 clouds 变体并不都带 temporalJitter()，所以这里用参考**非 TAA 分支**
// 的固定 4x4 Bayer（bayer64 的左上 4x4 子块）：同一个像素永远拿同一个相位，
// 不会引入时间性闪烁；雾本身没有时域累积，逐帧抖动只会变成噪点。
// 2026-10-23：逐帧低差异相位（自创）。与 volumetric_clouds.comp 的
// temporalPhase() 同一招：黄金比序列在任意连续窗口内近似均匀铺满 [0,1)，
// 于是"沿视线的步进相位"在 3~5 帧内被既有 resolve 收敛掉，而不是靠白噪声。
// 只取低 10 位（同 v1 的理由：fp32 精度，几小时后仍精确）。
float fogTemporalPhase(float strength)
{
    if(strength <= 0.0)
    {
        return 0.0;
    }
    return strength * float(cam.frameCount & 0x3ffu) * 0.6180339887498949;
}

float fogDitherPhase(ivec2 pixel, float temporalPhase)
{
    const int bayer4[16] = int[16](
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5);

    const float base = float(bayer4[(pixel.y & 3) * 4 + (pixel.x & 3)]) / 16.0;
    // temporalPhase == 0 时 fract(base) == base ⇒ 与旧档逐位相同。
    return fract(base + temporalPhase);
}

// 2026-10-23 地面锚定（自创，见文件头）。
//
// 语义：把参考的绝对世界 Y 密度带**整体跟着地形平移**，而不是把 VFOG_HEIGHT /
// VFOG_HEIGHT_2 当成"离地高度"（那样 50..65 会变成"离地 50-65 格"，雾飘到半空）。
// 平移量 = (该列地面 Y − 参考海平面) × 强度：
//   · 海平面附近（地面 ≈ 63）⇒ 平移 0 ⇒ 逐位等于参考的绝对带；
//   · 山谷（地面 40）⇒ 带整体降到 27..42；山脊（地面 90）⇒ 抬到 77..92。
// 带厚与剖面形状完全由参考公式决定，本函数只改输入的 Y。
// 强度 0 / 虚空 / 高度场盒外 ⇒ 0（= 旧档）。
const float FOG_ANCHOR_REFERENCE_Y = 63.0;
// 平移量上限（格）：防止极端地形（或高度场坏值）把雾带甩到天上/地下。
const float FOG_ANCHOR_MAX_SHIFT = 128.0;

// 一次高度场取样，同时服务两件事：密度带的 Y 平移、雾浪的贴地剖面。
struct FogGround
{
    float height;   // 该列地面 Y；无效（虚空/盒外）时回落参考海平面
    float weight;   // 0..1：1 = 高度场有效；出盒后平滑降到 0
};

FogGround fogGroundAt(vec3 position)
{
    FogGround g;
    g.height = FOG_ANCHOR_REFERENCE_Y;
    g.weight = 0.0;

    float outsideDistance = 0.0;
    const float ground = terrainGroundHeight(position.xz, outsideDistance);
    if(ground < TERRAIN_HEIGHT_EMPTY * 0.5)
    {
        // TERRAIN_HEIGHT_EMPTY = -1e30：虚空或该列射线什么都没打到。
        return g;
    }

    g.height = ground;
    // 出盒后平滑压到 0（硬切会在相机周围切出一圈"雾消失"的环）。
    g.weight = 1.0 - smoothstep(
        0.0, TERRAIN_HEIGHT_OUTSIDE_FADE_BLOCKS, outsideDistance);
    return g;
}

float fogGroundAnchorOffset(FogGround g, float strength)
{
    const float shift = clamp(
        g.height - FOG_ANCHOR_REFERENCE_Y,
        -FOG_ANCHOR_MAX_SHIFT,
        FOG_ANCHOR_MAX_SHIFT);
    return shift * clamp(strength * g.weight, 0.0, 1.0);
}

// ===========================================================================
// 2026-10-23 雨天地面雾浪（ground mist wisps）—— **自创**，逐项标注
// 任何噪声层（参考的 FogDensity() 只依赖 Y，世界里静止）。
//
// 观感目标：低空一层横向拉长的雾，被风吹着贴地爬，边缘卷起来像浪。
// 四个必要成分，逐项对应下面的常量：
//   1) 噪声源  = cloudShapeNoise.R（128³，烘焙好的 3 倍频可平铺 FBM；
//                一次 textureLod 就拿整个倍频栈，零新增纹理/描述符）；
//   2) 各向异性 = XZ 按波长、Y 压扁 FOG_WISP_VERTICAL_STRETCH 倍 ⇒ 薄片状，
//                这是"浪"的关键（各向同性只能得到一团团雾球）；
//   3) 域扭曲   = 再取一次低频噪声当位移，边缘卷曲而不是"整张图在滑"；
//   4) 平流     = 与云同一个风向（tyndallWind 的固定方向）、速度独立可调。
//
// 只在雨天出现（乘 smoothstep(0, 0.35, wetness)）：晴天 strength 再大也是 0。
// 成本：每 march 步 2 次 3D 采样 + 1 次高度场采样；`profile > 0.01` 的早退让
// 抬头上看的样本一次都不采样（雾浪只贴在层内）。
// ===========================================================================
// 每格消光（强度 1、满雨、噪声峰值）。参考密度满雨带内约 3.9e-3/格 ⇒ 这里的峰值
// 取它的 2.3 倍，让"浪芯"明显比周围雾更浓（0.0030 只有 0.8 倍、被噪声阈值一削就
// 看不见了 —— 用户第一版反馈"似乎没有"）。
const float FOG_WISP_EXTINCTION = 0.0090;
const float FOG_WISP_VERTICAL_STRETCH = 12.0;
const float FOG_WISP_WARP = 0.32;
// 阈值化区间：低于 LO 全透明、高于 HI 满密度 ⇒ 断续成团，不是一层灰纱。
// 第一版是 0.50/0.74：cloudShapeNoise.R 的分布是 mean 0.50 / std 0.11，0.74 在
// 约 p98 ⇒ 只有 2% 体积能到满强度，平均下来几乎不可见。现在 0.40/0.62（≈p82）
// 覆盖率约 18%，浪头连成条而不是零星斑点。
const float FOG_WISP_THRESHOLD_LO = 0.40;
const float FOG_WISP_THRESHOLD_HI = 0.62;
// 与 tyndallWind() 同一个风向（云的同一阵风），速度由滑杆独立控制。
const vec3 FOG_WISP_WIND_DIRECTION = vec3(0.81915204429, 0.0, 0.57357643635);

float fogWispShape(
    vec3 position,
    float wavelength,
    float advectSpeed,
    float timeSeconds)
{
    const vec3 advected = position +
        FOG_WISP_WIND_DIRECTION * (advectSpeed * timeSeconds);
    const float invWavelength = 1.0 / max(wavelength, 8.0);
    const vec3 q = vec3(
        advected.x * invWavelength,
        advected.y * invWavelength * FOG_WISP_VERTICAL_STRETCH,
        advected.z * invWavelength);

    const float warp = textureLod(
        cloudShapeNoise, q * 0.37 + vec3(0.11, 0.23, 0.47), 0.0).r - 0.5;
    const float shape = textureLod(
        cloudShapeNoise, q + warp * FOG_WISP_WARP, 0.0).r;

    return smoothstep(FOG_WISP_THRESHOLD_LO, FOG_WISP_THRESHOLD_HI, shape);
}

// 2026-10-23 动态光的相位（自创）：介质里没有表面法线，用"入射光方向与视线
// 的夹角"做 HG。系数使前向 ≈ 0.42、背向 ≈ 0.02（各向同性地板 0.02）。
const float FOG_LIGHT_PHASE_SCALE = 0.62;
const float FOG_LIGHT_PHASE_FLOOR = 0.02;
const float FOG_LIGHT_PHASE_G = 0.40;

float fogLightPhase(vec3 toLight, vec3 viewDirection)
{
    const float distanceToLight = max(length(toLight), 1.0e-4);
    const float cosine = dot(-toLight / distanceToLight, viewDirection);
    return FOG_LIGHT_PHASE_FLOOR + FOG_LIGHT_PHASE_SCALE * hgPhase(cosine, FOG_LIGHT_PHASE_G);
}

// 主入口。前 17 个参数与 2026-10-19 B 方案一致；末尾三个是 2026-10-23 追加的
// 自创项（地面锚定 / 时域抖动 / 动态光源），全部"0 = 逐位旧档"。
// ===========================================================================
// 2026-10-23 修正：环境项**不能**取"视线方向的晴空辐亮度"
//
// 症状（用户报告）："有时候可以隔墙看到太阳所在位置的白光"。
// 根因：clearSkyInscatter() 里含 Mie 前向瓣，正对太阳时比天顶亮一到两个数量级。
// 我先前直接把它当环境入散射用，于是：
//   1) 雾变成有方向的 —— 朝太阳看时整层雾发白，白斑钉在太阳的屏幕位置上；
//   2) 环境项只吃 sunShadow 的"环境压暗地板"（FOG_AMBIENT_SHADOW_FLOOR = 0.35），
//      所以躲在墙后 / 洞里照样按 35% 发光 ⇒ "隔墙可见"。
// 物理上环境项是**半球积分**，不能用单方向辐亮度。这里改成对三个固定方向
// （天顶 / 太阳侧水平 0.2 仰角 / 反太阳侧水平 0.2 仰角）取 HG 加权平均 ——
// 与 v2/v4 的天光（D6）同一套口径，**不包含指向太阳的那一瓣**，
// 因此不会在太阳位置形成亮斑；同时保留"日出日落雾自己泛色"的效果。
// ===========================================================================
vec3 fogSkyAmbient(
    vec3 viewDirection,
    float sunElevation,
    vec3 lightDirection,
    vec3 directLightColor,
    float radianceScale,
    float weatherFactor)
{
    const vec3 sunSideDir = normalize(vec3(lightDirection.x, 0.20, lightDirection.z));
    const vec3 antiSunDir = normalize(vec3(-lightDirection.x, 0.20, -lightDirection.z));
    const vec3 radianceZenith = clearSkyInscatter(
        vec3(0.0, 1.0, 0.0), sunElevation, lightDirection,
        directLightColor, radianceScale, weatherFactor, vec3(1.0));
    const vec3 radianceSunSide = clearSkyInscatter(
        sunSideDir, sunElevation, lightDirection,
        directLightColor, radianceScale, weatherFactor, vec3(1.0));
    const vec3 radianceAntiSun = clearSkyInscatter(
        antiSunDir, sunElevation, lightDirection,
        directLightColor, radianceScale, weatherFactor, vec3(1.0));
    const float weightZenith = hgPhase(dot(viewDirection, vec3(0.0, 1.0, 0.0)), 0.25);
    const float weightSunSide = hgPhase(dot(viewDirection, sunSideDir), 0.25);
    const float weightAntiSun = hgPhase(dot(viewDirection, antiSunDir), 0.25);
    return (radianceZenith * weightZenith +
        radianceSunSide * weightSunSide +
        radianceAntiSun * weightAntiSun) /
        max(weightZenith + weightSunSide + weightAntiSun, 1.0e-4);
}
// 函数名 evaluateAnalyticFog 是旧名，当前内部已是参考的 march，不是解析闭式雾。
// fogDensityAt() 来自共享头 fog_density_common.glsl。
FogResult evaluateAnalyticFog(
    vec3 worldOrigin,
    vec3 worldDirection,
    float sceneDistance,
    vec3 sunRadiance,
    vec3 sunDirection,
    vec3 ambientRadiance,
    float rain,
    float enabled,
    float baseDensityParam,
    float heightA,
    float heightB,
    float anisotropyG,
    float sunStrength,
    float ambientStrength,
    float maxDistance,
    float startDistance,
    float sunStepsRaw,
    float rainBoost,
    float groundAnchor,
    float temporalJitter,
    float dynamicLightStrength,
    float wispEnabled,
    float wispStrength,
    float wispWavelength,
    float wispSpeed)
{
    FogResult result;
    result.scattering = vec3(0.0);
    result.transmittance = 1.0;
    result.firstDistance = 0.0;
    result.opticalDepth = 0.0;
    result.meanDistance = 0.0;

    if(enabled < 0.5)
    {
        return result;
    }

    const float wetness = clamp(rain, 0.0, 1.0);

    // 参考 VolumetricFog.glsl:104-107：baseDensity 按远裁距离归一化。
    // 参考用 far；本工程 far 在 cam.cameraParams.y。
    const float baseDensity =
        max(baseDensityParam, 0.0) *
        (wetness * 0.15 + 0.08) /
        max(cam.cameraParams.y, 100.0);

    // 参考 Volumetric_FS.glsl:173-186：fogTimeFactor 的雨天倍率
    //（VFOG_IGNORE_WORLDTIME 分支，本工程没有世界时间曲线）。
    const float fogTimeFactor =
        mix(1.0, max(rainBoost, 0.0), wetness);

    if(fogTimeFactor <= 0.01)
    {
        return result;
    }

    // 参考 VolumetricFog.glsl:6-7：双高度带（VFOG_HEIGHT / VFOG_HEIGHT_2）。
    const float heightMax = max(heightA, heightB);
    const float heightMin = min(heightA, heightB);

    // 分段范围：起点留白 + 最远作用距离。
    const float fogFar = min(max(sceneDistance, 0.0), max(maxDistance, 1.0));
    const float fogNear = clamp(startDistance, 0.0, fogFar);

    result.firstDistance = fogNear;

    if(fogFar - fogNear <= 0.01)
    {
        return result;
    }

    const vec3 start = worldOrigin + worldDirection * fogNear;
    const vec3 end = worldOrigin + worldDirection * fogFar;
    const vec3 rayVector = end - start;

    // 参考 VolumetricFog.glsl:88-94 的指数步长归一化。
    const float rSteps = 1.0 / VFOG_QUALITY;
    const float rayLength = length(rayVector) * rSteps * 0.23979;

    // 参考 VolumetricFog.glsl:75-79：抖动。平台适配见文件头第 3 条。
    // 2026-10-23：叠加逐帧低差异相位（temporalJitter = 0 ⇒ 与旧档逐位相同）。
    const float noise = fogDitherPhase(
        ivec2(gl_GlobalInvocationID.xy), fogTemporalPhase(temporalJitter));

    // 参考 VolumetricFog.glsl:112：相位。
    const float VdotL = dot(worldDirection, sunDirection);
    const float phases = hgPhase(VdotL, anisotropyG) * 0.7 + 0.035;

    const int sunSteps = clamp(int(sunStepsRaw + 0.5), 1, VFOG_SUNLIGHT_STEPS_MAX);

    // 2026-10-23 动态光源（自创，见文件头）：在视线前方 ~24 格处一次性收集邻域
    // 里的灯，供每个 march 步复用 —— rgen 是按接收点逐步查表，雾这里逐步查表会
    // 把 8 个 cell 的 hash 走访乘上步数。收集点取"视线前方"而不是相机原点：
    // 光源范围 30 格，于是视野内 0~54 格的灯都能进雾。
    vec4 fogLightPosEmission[FOG_LIGHT_MAX];
    uint fogLightType[FOG_LIGHT_MAX];
    int fogLightCount = 0;
    float fogLightEnergyScale = 0.0;
    if(dynamicLightStrength > 0.0)
    {
        const vec3 gatherPoint = worldOrigin + worldDirection * min(fogFar, 24.0);
        fogLightCount = fogGatherLocalLights(
            gatherPoint, fogLightPosEmission, fogLightType);
        // 与 rgen:3002 的 scale 逐项同构，只是不乘表面 N·L（介质里没有法线，
        // 相位见 fogLightPhase()）。
        fogLightEnergyScale = 3.2 * LOCAL_LIGHT_ENERGY_SCALE *
            localLightIntensity() * max(dynamicLightStrength, 0.0);
    }
    vec3 lightDensityAccum = vec3(0.0);

    // 2026-10-23 雾浪：雨强门（雨天才有）+ 时间（与云/水同一只钟，cam.padding）。
    const float wispRain = smoothstep(0.0, 0.35, wetness);
    const bool wispActive =
        wispEnabled > 0.5 && wispStrength > 0.0 && wispRain > 0.0;
    const float wispTime = uintBitsToFloat(cam.padding);
    // 只要锚定或雾浪有一个要地面，就按列取一次高度场（两个功能共用这一次取样）。
    const bool needGroundSample = groundAnchor > 0.0 || wispActive;

    float transmittance = 1.0;
    float fogDensityAccum = 0.0;
    float rayDensityAccum = 0.0;
    // 2026-10-23：透射率加权平均距离（时域重投影用，见 FogResult::meanDistance）。
    float fogMeanDistanceAccum = 0.0;
    float fogMeanWeightAccum = 0.0;
    // 2026-10-21：受光部分的光学厚度 Σ integral×shadow，供环境项遮蔽用。
    // 全受光时 == fogDensityAccum ⇒ 下面 ambientVisibility 恒 1 ⇒ 逐位不变。
    float fogLitAccum = 0.0;

    for(int i = 0; i < int(VFOG_QUALITY); ++i)
    {
        // 参考 VolumetricFog.glsl:128-132。
        const float exponential = pow(11.0, (float(i) + noise) * rSteps);
        const float stepLength = exponential * 0.1 - 0.1;
        const float stepDensity = exponential * rayLength * 1.442695;

        const vec3 fogPos = start + stepLength * rayVector;

        // 2026-10-23 地面锚定（自创，见文件头）：只换喂进参考密度函数的 Y，
        // 参考的剖面公式不改。groundAnchor = 0 ⇒ offset 恒 0 ⇒ 逐位旧档。
        FogGround ground;
        ground.height = FOG_ANCHOR_REFERENCE_Y;
        ground.weight = 0.0;
        if(needGroundSample)
        {
            ground = fogGroundAt(fogPos);
        }
        const float groundOffset = fogGroundAnchorOffset(ground, groundAnchor);

        float density = fogDensityAt(
            vec3(fogPos.x, fogPos.y - groundOffset, fogPos.z),
            baseDensity,
            fogTimeFactor,
            wetness,
            heightMax,
            heightMin);

        // 2026-10-23 雨天地面雾浪（自创，见文件头常量段）。贴地剖面用与锚定同
        // 一次高度场取样；层厚跟波长成比例（波长越大，浪越大也越厚）。
        if(wispActive)
        {
            const float groundY = mix(
                FOG_ANCHOR_REFERENCE_Y, ground.height, ground.weight);
            // 层厚 = 波长 × 0.05（180 格 ⇒ 9 格高）。第一版 0.035 只有 6.3 格，
            // 而 march 只有 6 步、第一步就落在相机上 ⇒ 近场的薄层太容易被跳过。
            const float layerHeight = clamp(wispWavelength * 0.05, 2.0, 24.0);
            const float profile =
                exp(-max(fogPos.y - groundY, 0.0) / layerHeight);
            // 早退：离地太高的样本一次都不采样（抬头上看的视线因此零成本）。
            if(profile > 0.01)
            {
                density += FOG_WISP_EXTINCTION * wispStrength * wispRain * profile *
                    fogWispShape(fogPos, wispWavelength, wispSpeed, wispTime);
            }
        }

        const float absorption = exp2(-density * stepDensity);

        // 参考 VolumetricFog.glsl:144-158：沿太阳方向的密度自吸收 march。
        float lightingDensity = 0.0;
        float lightingStepLength = VFOG_SUNLIGHT_STEPLENGTH;
        vec3 lightPos = fogPos;

        for(int j = 0; j < VFOG_SUNLIGHT_STEPS_MAX; ++j)
        {
            if(j >= sunSteps)
            {
                break;
            }

            // 地面锚定在这里**复用视线采样点那一次的 offset**（不为太阳方向的
            // 每一步再查一次高度场：那会把每步的纹理取样乘上 sunSteps）。太阳
            // marches 只走 5~40 格，仍在同一片地形的高度尺度内。
            lightingDensity += fogDensityAt(
                vec3(lightPos.x, lightPos.y - groundOffset, lightPos.z),
                baseDensity,
                fogTimeFactor,
                wetness,
                heightMax,
                heightMin) * lightingStepLength;

            lightPos += sunDirection * lightingStepLength;
            lightingStepLength *= 1.5;
        }

        float stepRayDensity =
            exp2(-lightingDensity * VFOG_SUNLIGHT_ABSORPTION) * phases;

        // 参考 VolumetricFog.glsl:163-180：地形/构筑物可见性。
        // 平台适配见文件头第 2 条（shadowtex1 → sunShadowVisibility）。
        // 2026-10-21：改用 sunShadowFogVisibility —— 近图 ∪ 远图取更暗者。
        // 单查近图时，160 格之外的遮挡体不在它的柱子里 ⇒ 背光面判受光（穿墙）。
        // 详见 sun_shadow.glsl 里该函数的注释。
        const float shadow = sunShadowFogVisibility(fogPos, sunDirection, i);

        // 参考 VolumetricFog.glsl:192-208：能量合成。
        const float integral = transmittance - absorption * transmittance;
        stepRayDensity *= integral;

        fogDensityAccum += integral;
        fogLitAccum += integral * shadow;
        rayDensityAccum += stepRayDensity * shadow;

        // 2026-10-23：透射率加权平均距离（与云层 meanDistAccum 同口径）。
        {
            const float sampleDistance = fogNear + stepLength * length(rayVector);
            fogMeanDistanceAccum += integral * sampleDistance;
            fogMeanWeightAccum += integral;
        }

        // 2026-10-23 动态光源对该段的入散射（自创）。段权 integral 与太阳项
        // 完全同一条链，所以"雾越浓、灯照出的雾越亮"是自动成立的。
        for(int li = 0; li < FOG_LIGHT_MAX; ++li)
        {
            if(li >= fogLightCount)
            {
                break;
            }

            const vec3 toLight = fogLightPosEmission[li].xyz - fogPos;
            const float emissionLevel = clamp(fogLightPosEmission[li].w, 1.0, 15.0);
            const float distanceToLight = length(toLight);
            if(distanceToLight > vptLocalLightRange(emissionLevel))
            {
                continue;
            }

            const float lightScale = fogLightEnergyScale * (emissionLevel / 15.0) *
                vptLocalLightDistanceAttenuation(distanceToLight) *
                vptLocalLightRangeFade(distanceToLight, emissionLevel);

            lightDensityAccum += decodeLocalLightColor(fogLightType[li]) * lightScale *
                (fogLightPhase(toLight, worldDirection) * integral);
        }

        transmittance *= absorption;

        if(transmittance < 1.0e-4)
        {
            break;
        }
    }

    // 2026-10-23：没有雾的像素回落到 fogFar（= min(场景距离, 视距)），这样
    // 时域重投影不会退化成"按相机原点投影"（见 FogResult::meanDistance 注释）。
    result.meanDistance = fogMeanWeightAccum > 1.0e-6
        ? fogMeanDistanceAccum / fogMeanWeightAccum
        : fogFar;

    // 参考 VolumetricFog.glsl:218-225：fogColor / rayColor 及**参考标定系数**。
    // 2026-10-21：环境项按"受光比例"压暗（地板 FOG_AMBIENT_SHADOW_FLOOR）。
    // 全受光时 litFraction = 1 ⇒ ambientVisibility = 1 ⇒ 与参考移植版逐位相同。
    const float litFraction = fogDensityAccum > 1.0e-6
        ? clamp(fogLitAccum / fogDensityAccum, 0.0, 1.0)
        : 1.0;
    const float ambientVisibility =
        mix(FOG_AMBIENT_SHADOW_FLOOR, 1.0, litFraction);

    const vec3 fogColor =
        ambientRadiance * fogDensityAccum *
        (0.3 * max(ambientStrength, 0.0)) * ambientVisibility;

    const vec3 rayColor =
        sunRadiance * rayDensityAccum *
        (1.2 * max(sunStrength, 0.0));

    // 2026-10-23：动态光源项直接加在散射上（已含段权），不受
    // ambientVisibility 影响 —— 那是"天光被挡"的代理，与方块光无关。
    result.scattering = fogColor + rayColor + lightDensityAccum;
    result.transmittance = transmittance;
    result.opticalDepth = -log(max(transmittance, 1.0e-6));

    return result;
}

#endif // VOLUMETRIC_FOG_GLSL
