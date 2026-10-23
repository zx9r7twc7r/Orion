#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_GOOGLE_include_directive : require

// 2026-10-20: 结构体单源化，见 hit_payload.glsl（六个 shader 共用同一份布局）。
#include "hit_payload.glsl"

layout(location = 0) rayPayloadInEXT HitPayload payload;

layout(binding = 2, set = 0) uniform CameraProperties {
    #include "camera_ubo.glsl"
} cam;

// The Tyndall cloud field needs the same two 3D noise samplers the raygen
// already binds in this set (38/39).  The include is the single source of
// cloud density shared by the primary compute pass, reflections and shadows.
layout(binding = 38, set = 0) uniform sampler3D cloudShapeNoise;
layout(binding = 39, set = 0) uniform sampler3D cloudCurlNoise;

#include "tyndall_cloud_field.glsl"
// 高层平面云（2026-10-02 C 档）。必须在 tyndall_cloud_field.glsl 之后 ——
// 它复用那里的 tyndallTime / tyndallSphereRoots / tyndallNoiseLod / TYNDALL_RADIUS。
#include "high_cloud_field.glsl"

// Physical sky scattering and sun transmittance are precomputed by
// sky_atmosphere.comp in an equirectangular projection before the RT pass.
layout(binding = 42, set = 0) uniform sampler2D skyScatteringTex;
layout(binding = 43, set = 0) uniform sampler2D skyTransmittanceTex;

// Authored 2:1 equirectangular night sky (rt_star_map.png), replacing the
// procedural star field once the sun is down.  Miss-only, like the two sky
// textures above: stars must never leak into shadow, AO, GI or reflection rays.
layout(binding = 44, set = 0) uniform sampler2D starMap;

// 2026-10-05 末地天空图集（rt_end_atlas.png，4096x3072）：
//   上 2/3 = 银河全景（等距圆柱 2:1）
//   左下   = 地球昼面，右下 = 地球夜面（城市灯）
// 与上面两张天空纹理同为 miss-only：末地天空绝不能泄漏进阴影/AO/GI/反射射线。
layout(binding = 60, set = 0) uniform sampler2D endSkyAtlas;


// Primary camera misses are tagged with this purpose by the raygen; the
// volumetric-cloud compute composite already covers those pixels.
const uint RT_PURPOSE_PRIMARY_SKY = 7u;

// =====================================================
// 2026-10-22 MIS-A：GI 射线标记（bit 14）—— 与 rt_test.rgen 的
// RT_STATE_NO_SUN_DISK_BIT **必须同值**（rmiss 是独立 TU，宏/常量不跨文件）。
// 置位者是 rt_test.rgen 的 GI 回路 trace 点；置位时本 shader 不回传日盘，
// 因为太阳直射已由该顶点的 NEE 以 delta 近似估计，两份并存 = 重复计入 + 萤火虫。
// 只压日盘，日冕瓣（pow64，±8°）保留：它不是 delta，NEE 覆盖不到。
// =====================================================
#ifndef GI_SKIP_SUN_DISK
#define GI_SKIP_SUN_DISK 0
#endif
const uint RT_STATE_NO_SUN_DISK_BIT = 1u << 14u;

// =====================================================
// 2026-10-22 水面镜面反射的太阳遮挡（bit 15）—— 与 rt_test.rgen 的
// WATER_SKY_SUN_OCCLUSION / RT_STATE_NO_SUN_GLOW_BIT **必须同值**。
// 水面点在阴影里时，rgen 给反射射线同时置 bit 14（无日盘）与 bit 15（无日冕）。
// 日冕瓣 pow64 的半角约 ±8°，反射线可以从遮挡物边缘上方擦过去；它不带昼夜
// 开关之外的遮挡判定，所以必须在合成前压掉，否则阴影水面会出现太阳色白斑。
// 直射阳光下的反射不置位 ⇒ 镜面里的太阳逐位保留。
// A/B 回退：WATER_SKY_SUN_OCCLUSION=0（两处必须同值）。
// =====================================================
#ifndef WATER_SKY_SUN_OCCLUSION
#define WATER_SKY_SUN_OCCLUSION 1
#endif
const uint RT_STATE_NO_SUN_GLOW_BIT = 1u << 15u;

// 球形壳体求交算法
bool RayIntersectSphereSolution(vec3 RayOrigin, vec3 RayDirection, vec4 Sphere, inout vec2 Solutions) {
    vec3 LocalPosition = RayOrigin - Sphere.xyz;
    float LocalPositionSqr = dot(LocalPosition, LocalPosition);

    vec3 QuadraticCoef;
    QuadraticCoef.x = dot(RayDirection, RayDirection);
    QuadraticCoef.y = 2.0 * dot(RayDirection, LocalPosition);
    QuadraticCoef.z = LocalPositionSqr - Sphere.w * Sphere.w;

    float Discriminant = QuadraticCoef.y * QuadraticCoef.y - 4.0 * QuadraticCoef.x * QuadraticCoef.z;

    if (Discriminant >= 0.0) {
        float SqrtDiscriminant = sqrt(Discriminant);
        Solutions = (-QuadraticCoef.y + vec2(-1.0, 1.0) * SqrtDiscriminant) / (2.0 * QuadraticCoef.x);
        return true;
    }
    return false;
}

// 3D 分形噪声
float hash3D(vec3 p) {
    p = fract(p * vec3(123.34, 456.21, 789.12));
    p += dot(p, p + 34.45);
    return fract(p.x * p.y * p.z);
}

float noise3D(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n = dot(i, vec3(1.0, 57.0, 113.0));
    return mix(
        mix(mix(hash3D(i + vec3(0,0,0)), hash3D(i + vec3(1,0,0)), f.x),
            mix(hash3D(i + vec3(0,1,0)), hash3D(i + vec3(1,1,0)), f.x), f.y),
        mix(mix(hash3D(i + vec3(0,0,1)), hash3D(i + vec3(1,0,1)), f.x),
            mix(hash3D(i + vec3(0,1,1)), hash3D(i + vec3(1,1,1)), f.x), f.y), f.z);
}

float fbm3D(vec3 p) {
    float f = 0.0;
    float w = 0.5;
    for (int i = 0; i < 3; i++) {
        f += w * noise3D(p);
        p *= 2.0;
        w *= 0.5;
    }
    return f;
}

// Henyey-Greenstein 相函数
float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * 3.14159265 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

// 云的双瓣 HG 常量，必须与 volumetric_clouds.comp 的 CLOUD_PHASE_* 逐位一致，
// 否则反射里的云与主视野的云会有一道亮度接缝。
const float CLOUD_PHASE_FORWARD_G = 0.80;
const float CLOUD_PHASE_BACK_G = -0.35;
const float CLOUD_PHASE_BACK_MIX = 0.30;

// 次级光线（反射/折射）的体积云：与主视野 compute 管线共用同一套 Tyndall 云场
// （tyndall_cloud_field.glsl），云的形状/位置/天气响应与天空逐位一致。
// 光照为主管线的简化版：不做逐步光线行军，用「密度 × 云厚 × 高度」的解析
// 自阴影近似（exp(-lightOD*1.35)），视觉上保住亮顶暗底，省 12x N 次密度采样。
#if 0
// 旧 fbm 行军（与 Tyndall 场完全不同源，形状对不上且能量标定失效，
// 反射里基本不可见）——按死代码保留。
vec4 sampleVolumetricClouds(vec3 rayOriginKm, vec3 rayDir, vec3 sunDir) {
    vec3 cloudLayerCenter = vec3(0.0, -6360.0, 0.0);
    float bottomRadius = 6360.0 + 2.5; // 降低云层底高，使其更容易被看到
    float topRadius = 6360.0 + 6.0;

    vec2 tTop = vec2(-1.0);
    vec2 tBottom = vec2(-1.0);

    float TMin = 0.0;
    float TMax = 0.0;

    if (RayIntersectSphereSolution(rayOriginKm, rayDir, vec4(cloudLayerCenter, topRadius), tTop)) {
        if (RayIntersectSphereSolution(rayOriginKm, rayDir, vec4(cloudLayerCenter, bottomRadius), tBottom)) {
            bool topValid = all(greaterThan(tTop, vec2(0.0)));
            bool bottomValid = all(greaterThan(tBottom, vec2(0.0)));

            float tempTop = topValid ? min(tTop.x, tTop.y) : max(tTop.x, tTop.y);
            float tempBottom = bottomValid ? min(tBottom.x, tBottom.y) : max(tBottom.x, tBottom.y);
            TMin = min(tempBottom, tempTop);
            TMax = max(tempBottom, tempTop);
        } else {
            TMin = tTop.x;
            TMax = tTop.y;
        }
    } else {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }

    TMin = max(0.0, TMin) * 100000.0;
    TMax = max(0.0, TMax) * 100000.0;

    // 放宽地平线限制，允许在平视时也能看到云层延伸到天边
    if (TMax <= TMin || rayDir.y < -0.05) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }

    int steps = 24; // 增加采样步数，保证云层细腻
    // 2026-09-30 远处云马赛克修复（与 volumetric_clouds.comp 同步）：
    // 掠射弦长钳到 48km，避免固定 24 步摊在几十公里上出块状马赛克。
    endDistance = min(endDistance, startDistance + 48000.0);
    float dt = (TMax - TMin) / float(steps);
    float transmittance = 1.0;
    vec3 accumColor = vec3(0.0);
    vec3 wind = vec3(float(cam.frameCount) * 15.0, 0.0, float(cam.frameCount) * 8.0);

    for (int i = 0; i < steps; ++i) {
        float t = TMin + (float(i) + 0.5) * dt;
        vec3 worldPos = rayDir * t + wind;

        // 调整噪声频率与密度阈值，让云层清晰可见且浓密
        float baseNoise = fbm3D(worldPos * 0.00003);
        float heightGradient = smoothstep(0.0, 0.2, fract(t / (TMax - TMin + 1e-5))) *
                               (1.0 - smoothstep(0.8, 1.0, fract(t / (TMax - TMin + 1e-5))));
        float density = max(0.0, baseNoise - 0.22) * heightGradient * 2.2;

        if (density > 0.005) {
            float sunAttenuation = exp(-density * 0.3);
            float cosTheta = dot(rayDir, sunDir);
            float phase = henyeyGreenstein(cosTheta, 0.4);

            // The caller already swapped in the moon direction after sunset, so
            // the light *direction* is correct here, but this fallback march
            // still lit the deck with full daytime energy and ambient.  Use the
            // same 2.5% moonlight ratio as the primary cloud pass.
            float cycleElevation = sin(fract(cam.historyParams.w) * 6.28318530718) *
                0.81915204429;
            float dayAmount = smoothstep(-0.03, 0.03, cycleElevation);
            vec3 sunColor = vec3(1.0, 0.96, 0.9) *
                mix(4.5 * (0.03 / 1.22077458876), 4.5, dayAmount);
            vec3 ambientColor = vec3(0.35, 0.45, 0.65) * mix(0.03, 1.0, dayAmount);

            vec3 stepColor = (sunColor * sunAttenuation * phase + ambientColor) * density * (dt * 0.0001);

            accumColor += transmittance * stepColor;
            transmittance *= exp(-density * (dt * 0.0001) * 0.15);

            if (transmittance < 0.05) break;
        }
    }

    return vec4(accumColor, transmittance);
}
#endif

// Fixed northern-latitude equinox path, identical to the primary raygen.
// 0.0 = due-east sunrise, 0.25 = due-south noon, 0.5 = due-west sunset.
const float SOLAR_LATITUDE_35_SIN = 0.57357643635;
const float SOLAR_LATITUDE_35_COS = 0.81915204429;

// 星图采样（directionToEquirect / starSphereSampleDirectionRate / evaluateStarMap*）。
// 2026-10-06 抽成共享头：sky_atmosphere.comp 也要把星图算进天空 LUT，
// 两处必须逐位一致。自定义 sampler `starMap` 已在上面 declared（binding 44）。
#include "star_map_common.glsl"

// 2026-10-07 末地光源球开关（定义见下方 include 的 sky_model_common.glsl，
// 本函数在其之前，提前兜底；重复定义同值合法）。
#ifndef END_ORB_ENABLE
#define END_ORB_ENABLE 1
#endif

vec3 getTimeDrivenSunDirection()
{
    const float TWO_PI = 6.28318530718;
    float dayAngle =
        fract(cam.historyParams.w) * TWO_PI;

#if END_ORB_ENABLE
    // 2026-10-18 末地光源球：固定天顶（只自转、不公转）（endOrbDirection，
    // 见 sky_model_common.glsl；字面量内联原因同 rgen）。
    // ⚠ 六个方向副本必须同一向量，否则可见球与直射光方向分家。
    if(abs(cam.weatherParams.z - 3.0) < 0.25)
    {
        // 2026-10-18：光源球固定在末地天顶（只自转、不公转）。
        return vec3(0.0, 1.0, 0.0);
    }
#endif

    return vec3(
        cos(dayAngle),
        SOLAR_LATITUDE_35_COS * sin(dayAngle),
        SOLAR_LATITUDE_35_SIN * sin(dayAngle)
    );
}

float getTimeDrivenSunElevation()
{
    const float TWO_PI = 6.28318530718;
#if END_ORB_ENABLE
    // 末地恒"白天"（光源球固定天顶），昼夜翻转不再参与末地。
    if(abs(cam.weatherParams.z - 3.0) < 0.25)
    {
        return 1.0;
    }
#endif
    return SOLAR_LATITUDE_35_COS *
        sin(fract(cam.historyParams.w) * TWO_PI);
}


#include "sky_model_common.glsl"
// 2026-10-09 末地天空（图集银河 UV + 程序化银河）的单一真源。必须在
// sky_model_common.glsl 之后（要 END_SPACE_STRENGTH / END_SKY_SPIN_RATE）；
// 自定义 sampler `endSkyAtlas` 已在上面声明（binding 60）。
#include "end_sky_common.glsl"

// 2026-09-30 P2：实现移入 sky_model_common.glsl（单一共享版本）。
// goldenStrength=1.0
vec3 tyndallReflectionSunColor(vec3 lightDirection)
{
    return skyModelSunColor(lightDirection, 1.0);
}

// 火烧云强度，与 volumetric_clouds.comp 逐字一致。
const float CLOUD_FIRE_RIM_STRENGTH = 3.0;
// BASE 只服务于云底泛光分支，该分支已用 #if 0 屏蔽，当前无调用点。
const float CLOUD_FIRE_BASE_STRENGTH = 1.4;

// 拂晓时星场保留的比例（1.0 = 不减弱）。拂晓天幕已在发光，星要压下去。
const float PREDAWN_STAR_FADE = 0.65;

#include "sky_sun_common.glsl"
// 2026-10-06 极光（雪地 / 云杉群系）。必须在相机块之后 —— 它读 cam.auroraParams
// （CameraUBO 尾部新增字段），而其它消费者没有声明那一格，所以是独立头文件。
#include "aurora_common.glsl"


vec4 sampleVolumetricClouds(vec3 rayOrigin, vec3 rayDir, vec3 sunDir) {
    // 2026-10-02 修：高层平面云与体积云层是两套独立开关。旧实现把整个函数挂在
    // 体积云层开关（cloudParams1.w）上，且在 tyndallInterval 失败时也直接 return
    // —— 体积云一关（或视线碰不到云层壳），反射里的高层云跟着整条消失。
    if(abs(cam.weatherParams.z - 1.0) >= 0.25)
        return vec4(0.0, 0.0, 0.0, 1.0);

    float transmittance = 1.0;
    vec3 scattering = vec3(0.0);

    float startDistance = 0.0;
    float endDistance = 0.0;
    if(cam.cloudParams1.w >= 0.5 &&
       tyndallIntervalFar(rayOrigin, rayDir, startDistance, endDistance))
    {
    // 光照参数逐条对齐 volumetric_clouds.comp 主行军（除光线行军外）。
    float trueSunElevation = getTimeDrivenSunElevation();
    float dayAmount = smoothstep(-0.03, 0.03, trueSunElevation);
    // 月光相位地板，与主管线 CLOUD_MOON_PHASE_FLOOR(0.55) 同值：夜里直射 =
    // 月光 × phase，而 phase 是前向瓣，背月面塌到 ~0.05 ⇒ 反射里的夜云只剩
    // 正对月亮那一片亮、其余全黑。地板把受光面拉平到「整层都能照到月光」。
    float moonPhaseFloor = (1.0 - dayAmount) * 0.55;
    float elevationMagnitude = abs(trueSunElevation) / 0.81915204429;
    // sunDir 由 main() 传入，夜间已换成月亮方向，与主管线一致。
    float viewLightCosine = dot(rayDir, sunDir);
    float phase = mix(henyeyGreenstein(viewLightCosine, CLOUD_PHASE_FORWARD_G),
        henyeyGreenstein(viewLightCosine, CLOUD_PHASE_BACK_G), CLOUD_PHASE_BACK_MIX);
    vec3 directLightColor = mix(vec3(0.46, 0.56, 1.00),
        tyndallReflectionSunColor(sunDir), dayAmount);
    // 2026-10-01 夜间档与主管线（volumetric_clouds.comp）对齐：月光直射抬到
    // ≈4% 正午（原 2.5%，与 CLOUD_MOON_LIGHT_SCALE 同值），环境地板
    // 0.03 → 0.08（与 CLOUD_NIGHT_AMBIENT 同值）。不同步的话，反射里的夜云
    // 比正片暗一档。
    float directLightEnergy = mix(6.0 * (0.05 / 1.22077458876), 6.0, dayAmount) *
        clamp(cam.cloudParams5.z, 0.0, 3.0);
    vec3 directLight = directLightColor * directLightEnergy;
    float daylight = mix(0.08, 1.0, smoothstep(-0.10, 0.15, trueSunElevation));
    float rain = clamp(cam.weatherParams.x, 0.0, 1.0);

#ifndef CLOUD_REFLECT_STEPS
// 2026-10-02 Route A：12 → 24。反射 march 与主视图一起抬射程（tyndallIntervalFar），
// 弦长从 ≤42 km 涨到 ≤277 km，12 步摊上去每步 16 km ⇒ 反射云会被采样糊没。
#define CLOUD_REFLECT_STEPS 24
#endif
    const int STEPS = CLOUD_REFLECT_STEPS;
    float stepLength = (endDistance - startDistance) / float(STEPS);
    float distance = startDistance + stepLength * 0.5;
    for(int step = 0; step < STEPS; ++step)
    {
        if(transmittance < 0.05) break;
        vec3 position = rayOrigin + rayDir * distance;
        // =====================================================
        // 2026-10-05 修"透过玻璃等半透明物看的云一片一片叠起来"：
        //
        // 此前这里全程 cheap=true（tyndallDensity 的 cheap 分支跳过 detail
        // 侵蚀）⇒ 次级光线（玻璃透射 / 反射 / 太阳可见性 miss）里的云是
        // **未侵蚀的底形板**：又密又平，与主视图（volumetric_clouds.comp 走
        // 完整侵蚀——用户档 cloudDetailStrength=0.5 / contrast=3.0 / scale=3.0
        // 把云雕成稀疏絮状）完全接不上。透过玻璃看天空时，成片的平板底形
        // 盖住了本应稀疏的云，就是报告里"一片一片叠起来的云"。
        //
        // 现在与主管线的两级步进同构：cheap 探测，命中后**全量重采样**。
        // 侵蚀只会减密度（(d−e)/(1−e) ≤ d）⇒ 探测未命中 ⇒ 全量必未命中，
        // 无假阴性；只有探测命中的步多付 2 次纹理读，晴空零开销，厚云里
        // 透射率早退不变。footprint 由 0 改传步长（与主管线的 segment 同义），
        // 远处样本自动落到噪声 mip 预过滤，与主视图的远档一致。
        // =====================================================
        float density = tyndallDensity(position, true, stepLength);
        if(density > 0.004)
        {
            density = tyndallDensity(position, false, stepLength);
        }
        if(density > 0.004)
        {
            float height = clamp(tyndallHeight(position), 0.0, 1.0);
            // 解析自阴影：光深度 ≈ 采样密度 × 0.6 × 采样点上方云厚 /
            // 光线天顶角修正，再乘主管线同款 1.35。
            float lightOpticalDepth = density * 0.6 *
                ((1.0 - height) * max(cam.cloudParams0.y, 1.0)) /
                max(sunDir.y, 0.15) * cam.cloudParams2.z;
            float sunlight = exp(-lightOpticalDepth * 1.35);
            vec3 bottomAmbient = vec3(0.22, 0.28, 0.40) *
                clamp(cam.cloudParams5.y, 0.0, 3.0);
            vec3 topAmbient = vec3(0.72, 0.80, 0.96) *
                clamp(cam.cloudParams5.x, 0.0, 3.0);
            vec3 ambient = mix(bottomAmbient, topAmbient, height) * 0.90 * daylight;
            ambient = mix(ambient, ambient * vec3(0.72, 0.80, 0.92), rain * 0.65);
            float heightShape = smoothstep(0.08, 0.92, height);
            // 下界从 -0.10 放到 -0.20：拂晓时太阳还在地平线下，云底已经
            // 先被染亮了，窗口上界不动，所以夜里依旧不会整片泛红。
            float sunsetGlow = (1.0 - smoothstep(0.04, 0.24, trueSunElevation)) *
                smoothstep(-0.20, 0.02, trueSunElevation);
            float rim = smoothstep(0.15, 0.85, viewLightCosine) *
                smoothstep(0.20, 0.90, height);
            // 火烧云只留顶缘（rim）。曾把整片朝阳侧云底也一起点亮，实拍像
            // "整层云被泡在橘色染料里"，不像火烧云 —— "半边天橘色"指的是天空，
            // 不是云。屏蔽保留为选项，与 volumetric_clouds.comp 逐字一致。
#if 0
            float baseGlow = smoothstep(-0.30, 0.80, viewLightCosine) * (1.0 - heightShape);
#endif
            // 云边火光跟随曙暮光色相：拂晓冷紫、清晨玫瑰金、傍晚橘。
            vec3 fireTint = twilightPalette(trueSunElevation) * sunsetGlow *
                rim * CLOUD_FIRE_RIM_STRENGTH;
            vec3 radiance = directLight * sunlight * max(phase, moonPhaseFloor) *
                (0.68 + 0.38 * heightShape) + ambient + fireTint;
            float sampleTransmittance =
                exp(-density * cam.cloudParams2.z * stepLength);
            scattering += radiance * (1.0 - sampleTransmittance) * transmittance;
            transmittance *= sampleTransmittance;
        }
        distance += stepLength;
    }
    }

    // 高层平面云（2026-10-02 C 档，双层球壳薄板）：与主管线同源。反射路径传
    // pixelAngle=0（不做噪声 mip 预过滤，沿用旧档观感），sunDir 已是月亮/太阳
    // 的受光方向，可直接作 lightDir。
    float highDistance;
    vec4 highCloud = sampleHighCloud(rayOrigin, rayDir, sunDir, 0.0, highDistance);
    scattering += transmittance * highCloud.rgb;
    transmittance *= highCloud.a;

    return vec4(scattering, transmittance);
}

// directionToEquirect 见 star_map_common.glsl（与 sky_atmosphere.comp 的
// directionForPixel 严格互逆，是天空 LUT / 星图的共享映射）。

// 程序化哈希星空：
// 按世界方向划分网格单元，每个单元至多一颗星，亮度/位置/闪烁由哈希决定。
float hash13(vec3 p)
{
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

#if END_ORB_ENABLE
// 2026-10-09 黑球修复旋钮（刻意只住在本文件：不动共享头，免得把别的 shader 的
// 常量表一起改掉、逼着它们全部重编）：
//   END_ORB_SKY_FLOOR   球内"透过的天空"的**保底下限**。健康档透射率 = absorb
//                       ≈ 0.82~0.92（弦长 0~2、σ=(0.10,0.04,0.05)），再乘 (1-F)
//                       ≥ 0.04（正入射 0.96）⇒ 恒 ≥ 0.79×天空；0.5 这条下限
//                       永不触发 ⇒ 对健康档逐位无影响。只有透射项塌掉（吸收吃满 /
//                       透射率归零）或算出非有限值时，才由它把天空救回来。
//   END_ORB_NAN_MAGENTA 1 = 回到 2026-10-08 的品红 NaN 探针（诊断用，默认关）。
#ifndef END_ORB_SKY_FLOOR
#define END_ORB_SKY_FLOOR 0.5
#endif
#ifndef END_ORB_NAN_MAGENTA
#define END_ORB_NAN_MAGENTA 0
#endif
//   END_ORB_ENV_REFLECTION 1 = 补回 v15 移植时省掉的**环境反射**（见下面 hitS 里
//                       那一段）。0 = 回到"只有透射"的旧档。
#ifndef END_ORB_ENV_REFLECTION
#define END_ORB_ENV_REFLECTION 1
#endif

// ---- 2026-10-07 末地光源球 v3：Shadertoy "AI Slop v15" Buffer A 的 miss 适配。
// 球是单位球（本地坐标），rot 用 mat2(c,-s,s,c) 与原版逐位同构；noise 统一用
// 本文件已有的 noise3D（同为 value noise，哈希函数不同但统计性质一致）。
float orbSdSeg(vec2 p, vec2 a, vec2 b)
{
    vec2 pa = p - a, ba = b - a;
    return length(pa - ba * clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0));
}

float orbRingDist(vec2 p, vec2 c, float r)
{
    return abs(length(p - c) - r);
}

// 八个手绘符号之一（v15 runeDist 逐字移植），定义在单位格 (x右,y上)。
float orbRuneDist(vec2 p, float id)
{
    float k = mod(id * 3.0 + 1.0, 8.0);
    float d;
    if(k < 0.5)
    {
        d = min(min(orbSdSeg(p, vec2(0.10, 0.10), vec2(0.90, 0.10)),
                    orbSdSeg(p, vec2(0.90, 0.10), vec2(0.50, 0.90))),
                orbSdSeg(p, vec2(0.50, 0.90), vec2(0.10, 0.10)));
    }
    else if(k < 1.5)
    {
        d = min(min(orbSdSeg(p, vec2(0.12, 0.00), vec2(0.50, 1.00)),
                    orbSdSeg(p, vec2(0.50, 1.00), vec2(0.88, 0.00))),
                orbSdSeg(p, vec2(0.27, 0.40), vec2(0.73, 0.40)));
    }
    else if(k < 2.5)
    {
        d = min(orbSdSeg(p, vec2(0.50, 0.00), vec2(0.50, 1.00)),
            min(orbSdSeg(p, vec2(0.50, 1.00), vec2(0.15, 0.62)),
                orbSdSeg(p, vec2(0.50, 1.00), vec2(0.85, 0.62))));
        d = min(d, orbSdSeg(p, vec2(0.25, 0.22), vec2(0.75, 0.22)));
    }
    else if(k < 3.5)
    {
        d = min(orbRingDist(p, vec2(0.50, 0.50), 0.30),
            orbSdSeg(p, vec2(0.50, 0.00), vec2(0.50, 1.00)));
    }
    else if(k < 4.5)
    {
        d = orbSdSeg(p, vec2(0.22, 0.00), vec2(0.22, 1.00));
        d = min(d, min(orbSdSeg(p, vec2(0.22, 1.00), vec2(0.80, 0.75)),
                       orbSdSeg(p, vec2(0.80, 0.75), vec2(0.22, 0.50))));
        d = min(d, min(orbSdSeg(p, vec2(0.22, 0.50), vec2(0.80, 0.25)),
                       orbSdSeg(p, vec2(0.80, 0.25), vec2(0.22, 0.00))));
    }
    else if(k < 5.5)
    {
        d = min(min(orbSdSeg(p, vec2(0.50, 1.00), vec2(0.85, 0.65)),
                    orbSdSeg(p, vec2(0.85, 0.65), vec2(0.50, 0.30))),
                min(orbSdSeg(p, vec2(0.50, 0.30), vec2(0.15, 0.65)),
                    orbSdSeg(p, vec2(0.15, 0.65), vec2(0.50, 1.00))));
        d = min(d, orbSdSeg(p, vec2(0.50, 0.30), vec2(0.50, 0.00)));
    }
    else if(k < 6.5)
    {
        d = min(orbSdSeg(p, vec2(0.15, 0.00), vec2(0.85, 0.85)),
                orbSdSeg(p, vec2(0.85, 0.00), vec2(0.15, 0.85)));
        d = min(d, orbSdSeg(p, vec2(0.20, 1.00), vec2(0.80, 1.00)));
    }
    else
    {
        d = min(orbRingDist(p, vec2(0.50, 0.68), 0.27),
            orbSdSeg(p, vec2(0.50, 0.41), vec2(0.50, 0.00)));
        d = min(d, orbSdSeg(p, vec2(0.28, 0.18), vec2(0.72, 0.18)));
    }
    return d;
}

// 球内星壳上的一层星（v15 starLayer 逐字移植；iTime → tFrame）。
float orbStarLayer(vec3 p, float scale, float seed, float tFrame)
{
    float ca = cos(tFrame * 0.03 + seed), sa = sin(tFrame * 0.03 + seed);
    p.xz = mat2(ca, -sa, sa, ca) * p.xz;
    vec3 g = p * scale + seed * 7.3;
    vec3 i = floor(g);
    float h = hash13(i);
    if(h < 0.55)
    {
        return 0.0;
    }
    vec3 o = (vec3(hash13(i + 1.3), hash13(i + 2.7), hash13(i + 4.1)) - 0.5) * 0.5;
    float d = length(fract(g) - 0.5 - o);
    float mag = pow((h - 0.55) / 0.45, 3.0);
    float tw = 0.75 + 0.25 * sin(tFrame * (1.5 + h * 4.0) + h * 50.0);
    return (0.15 + mag) * tw * pow(max(1.0 - d * 4.0, 0.0), 6.0);
}

// 发丝金线：两个噪声场同时过中位面的曲线（v15 wisps 逐字移植）。
vec3 orbWisps(vec3 p, float tDay)
{
    float r = length(p);
    vec3 q = p;
    float c1 = cos(tDay * 0.2 + q.y * 1.2), s1 = sin(tDay * 0.2 + q.y * 1.2);
    q.xz = mat2(c1, -s1, s1, c1) * q.xz;
    float c2 = cos(tDay * 0.07), s2 = sin(tDay * 0.07);
    q.xy = mat2(c2, -s2, s2, c2) * q.xy;
    float a = noise3D(q * 2.3) + 0.5 * noise3D(q * 4.9 + 3.1) - 0.75;
    float b = noise3D(q.zxy * 2.1 + 17.0) + 0.5 * noise3D(q.yzx * 4.3 + 5.0) - 0.75;
    float line = 1.0 / (1.0 + (a * a + b * b) * 700.0);
    float w = smoothstep(0.35, 0.9, r) * smoothstep(1.0, 0.94, r)
        * (0.35 + 0.65 * smoothstep(0.5, -0.7, p.y)) + 0.15;
    return vec3(1.0, 0.68, 0.25) * line * w;
}

// 金色等离子体（v15 plasma 逐字移植；SMOKE=0 ⇒ 透明度恒 0，trans 恒 1）。
// 返回 (自发光, 烟密度)。
vec4 orbPlasma(vec3 p, float pulse, float tDay)
{
    float r = length(p);
    vec3 q = p;
    float c0 = cos(0.6 * q.y + tDay * 0.15), s0 = sin(0.6 * q.y + tDay * 0.15);
    q.xz = mat2(c0, -s0, s0, c0) * q.xz;
    q.y -= tDay * 0.18;
    q += (vec3(noise3D(q * 1.3 + 3.0), noise3D(q * 1.3 + 17.0),
        noise3D(q * 1.3 + 31.0)) - 0.5) * 0.9;

    float f = noise3D(q * 1.6) + 0.5 * noise3D(q * 3.3 + 5.0);
    float ridge = 1.0 - abs(sin(f * 3.14159265 * 3.0));
    float sheet = pow(ridge, 7.0);
    float body = pow(ridge, 2.5);
    float mask = 0.25 + smoothstep(0.35, 0.7, noise3D(q * 0.9 + 40.0));
    float low = smoothstep(1.0, -1.0, p.y);
    float heat = 0.5 + 1.8 * low * low;
    float edge = smoothstep(1.0, 0.9, r);

    vec3 c = mix(vec3(1.0, 0.45, 0.05), vec3(1.0, 0.85, 0.40), sheet);
    vec3 e = c * sheet * mask * heat * edge * END_ORB_PLASMA;
    e += vec3(1.0, 0.40, 0.06) * body * mask * heat * edge *
        END_ORB_PLASMA * 0.12 * min(END_ORB_SMOKE, 1.0);

    vec3 s = p - vec3(0.0, -0.95, 0.0);
    e += vec3(1.0, 0.80, 0.40) * exp(-dot(s, s) * 14.0) * 1.2 * END_ORB_PLASMA;

    return vec4(e * pulse, body * mask * edge * END_ORB_SMOKE);
}

// 箍带姿态（v15 bandSpace 逐字；GLSL v*=m 与 mat2 共轭写法等价：CCW）。
vec3 orbBandSpace(vec3 p, float spin)
{
    float c0 = cos(0.85), s0 = sin(0.85);
    p.xy = mat2(c0, s0, -s0, c0) * p.xy;
    float c1 = cos(0.25), s1 = sin(0.25);
    p.yz = mat2(c1, s1, -s1, c1) * p.yz;
    float c2 = cos(spin), s2 = sin(spin);
    p.xz = mat2(c2, s2, -s2, c2) * p.xz;
    return p;
}

float orbBandWave(float ang)
{
    return 0.13 * sin(2.0 * ang);
}

vec2 orbRuneCell(float ang, float y, out float cid)
{
    float u = (ang / 6.28318531 + 0.5) * END_ORB_RUNES;
    cid = floor(u);
    return vec2((fract(u) - 0.25) / 0.5, (y / END_ORB_BAND_H + 0.6) / 1.2);
}

// 箍带 SDF（v15 sdBand 逐字）：芯带 + 符文/边线刻槽 + 边缘圆唇。
float orbSdBand(vec3 p, float spin)
{
    vec3 q = orbBandSpace(p, spin);
    float r = length(q);
    float ang = atan(q.z, q.x);
    float y = q.y - orbBandWave(ang);
    float core = max(abs(r - END_ORB_BAND_R) - 0.016, abs(y) - END_ORB_BAND_H);
    if(core < 0.02)
    {
        float cid;
        vec2 c = orbRuneCell(ang, y, cid);
        float glyph = max((orbRuneDist(c, cid) - 0.055) * 0.17,
            (END_ORB_BAND_R + 0.004) - r);
        float line = max(abs(abs(y) - 0.128) - 0.004,
            (END_ORB_BAND_R + 0.009) - r);
        core = max(core, -min(glyph, line));
    }
    float lip = length(vec2(r - END_ORB_BAND_R - 0.004,
        abs(y) - END_ORB_BAND_H)) - 0.028;
    return min(core, lip);
}

vec3 orbBandNormal(vec3 p, float spin)
{
    vec2 e = vec2(1.0, -1.0) * 0.0012;
    return normalize(e.xyy * orbSdBand(p + e.xyy, spin) +
        e.yyx * orbSdBand(p + e.yyx, spin) +
        e.yxy * orbSdBand(p + e.yxy, spin) +
        e.xxx * orbSdBand(p + e.xxx, spin));
}

// Cook-Torrance GGX（v15 ggx 逐字）。
vec3 orbGgx(vec3 n, vec3 v, vec3 l, float rough, vec3 F0)
{
    vec3 h = normalize(v + l);
    float nl = max(dot(n, l), 0.0);
    float nv = max(dot(n, v), 1e-3);
    float nh = max(dot(n, h), 0.0);
    float vh = max(dot(v, h), 0.0);
    float a = rough * rough, a2 = a * a;
    float dd = nh * nh * (a2 - 1.0) + 1.0;
    float D = a2 / (3.14159265 * dd * dd);
    float k = (rough + 1.0); k = k * k / 8.0;
    float G = nl / (nl * (1.0 - k) + k) * nv / (nv * (1.0 - k) + k);
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - vh, 5.0);
    return D * G * F / (4.0 * nl * nv + 1e-3) * nl;
}

// PBR 金（v15 pbrGold；唯一的改写：env 立方体贴图换代用品 —— 末地无
// cubemap，用"球自身辉光场 + 朝球心的镜面瓣"近似金的 IBL）。
vec3 orbPbrGold(vec3 p, vec3 n, vec3 v, float rough, vec3 extra)
{
    vec3 F0 = vec3(1.0, 0.766, 0.336);
    vec3 col = vec3(0.0);
    vec3 lo = -normalize(p);
    vec3 orbLight = vec3(1.0, 0.70, 0.30);
    col += min(orbGgx(n, v, lo, max(rough, 0.6), F0), vec3(2.0)) * orbLight * 1.2;
    // ⚠ 必须带各向同性底光：我们看到的总是带的外皮，而球心在这层的**背面**
    //   （dot(n,lo) < 0），只按球心方向打光会把整条金带渲成纯黑 —— 离线自检
    //   cpp/.tmp/orb/preview.js 抓到的正是这个。0.10 那一项就是球自身辉光场。
    float ndl = max(dot(n, lo), 0.0);
    col += F0 * orbLight * (ndl * 0.25 + 0.10);
    float fres = pow(clamp(1.0 - dot(n, v), 0.0, 1.0), 5.0);
    vec3 reflDir = reflect(-v, n);
    float towardOrb = pow(max(dot(reflDir, lo), 0.0), 3.0);
    vec3 envSurrogate = vec3(0.02) + END_ORB_GLOW_COLOR * (0.30 + 1.7 * towardOrb);
    col += (F0 + (1.0 - F0) * fres) * envSurrogate;
    return col + F0 * extra;
}

// 箍带着色（v15 shadeBand 逐字；iTime → tFrame）。
vec3 orbShadeBand(vec3 p, vec3 n, vec3 rd, float spin, float tFrame)
{
    vec3 v = -rd;
    vec3 q = orbBandSpace(p, spin);
    float r = length(q);
    float ang = atan(q.z, q.x);
    float y = q.y - orbBandWave(ang);
    float cid;
    vec2 c = orbRuneCell(ang, y, cid);
    float runeD = orbRuneDist(c, cid);
    vec3 runeCol = vec3(1.0, 0.60, 0.16);
    float flick = 0.85 + 0.15 * sin(tFrame * 2.0 + cid * 1.9);
    float outer = step(END_ORB_BAND_R, r);
    float spill = exp(-max(runeD - 0.055, 0.0) * 9.0) * outer *
        step(abs(y), END_ORB_BAND_H * 0.8);
    float rough = 0.26 + 0.08 * noise3D(q * 40.0);
    vec3 col = orbPbrGold(p, n, v, rough, runeCol * spill * flick * 1.5);
    float lit = smoothstep(0.06, 0.035, runeD) *
        step(r, END_ORB_BAND_R + 0.0075) * outer;
    return mix(col, (runeCol + vec3(1.0, 0.9, 0.6) * 0.3) *
        END_ORB_GLYPH_GLOW * flick, lit);
}
#endif // END_ORB_ENABLE

// 星空日周自转 / 星图采样已抽到 star_map_common.glsl（与 sky_atmosphere.comp
// 的天空 LUT 共用同一份实现，见该文件头部说明）。

float starFieldLayer(vec3 dir, float cellScale, float densityThreshold, float sizeBase, float sizeRange)
{
    // ⚠ 这条程序化星场只在 STAR_MAP_ENABLED=false 时启用（默认 true ⇒ 死代码），
    //   所以固定 rate 1.0，不跟随末地的 END_SKY_SPIN_RATE。
    dir = starSphereSampleDirection(dir);

    vec3 p = dir * cellScale;
    vec3 cell = floor(p);
    vec3 f = fract(p) - 0.5;

    float h = hash13(cell);
    if(h < densityThreshold)
    {
        return 0.0;
    }

    vec3 starPosition = vec3(
        hash13(cell + vec3(19.19, 13.13, 7.77)),
        hash13(cell + vec3(31.27, 27.27, 23.23)),
        hash13(cell + vec3(3.5, 5.5, 9.9))
    ) - 0.5;

    float distanceToStar = length(f - starPosition);
    float size = sizeBase + sizeRange * fract(h * 91.7);
    float brightness = smoothstep(size, size * 0.3, distanceToStar);
    brightness *= brightness;

    // 轻微闪烁，相位与单元哈希绑定，保证帧间稳定
    float twinkle = 0.75 + 0.25 * sin(float(cam.frameCount) * 0.03 + h * 41.0);
    return brightness * twinkle;
}

float evaluateStarField(vec3 dir)
{
    // 亮星层：稀疏、较大；暗星层：密集、较小
    float bright = starFieldLayer(dir, 90.0, 0.97, 0.040, 0.030);
    float dim = starFieldLayer(dir, 45.0, 0.88, 0.016, 0.020);
    // 星空强度滑杆由调用方统一施加，两条星场分支才等价。
    return bright * 1.4 + dim * 0.35;
}

// 星图解码（STAR_MAP_* 常量与 evaluateStarMap*）同样在 star_map_common.glsl。

// 分析式太阳透射染色（物理天空关闭时的兜底）：
// 空气质量的 Rayleigh 逐通道消光，日落时红化。
vec3 analyticSunTint(float elevationSin)
{
    float airmass = 1.0 / max(elevationSin, 0.03);
    return exp(-vec3(0.020, 0.065, 0.185) * airmass);
}

// ---------------------------------------------------------------- moon ----
// The night celestial body used to be the sun's disk with a blue tint at the
// sun's luminance: it clipped into a featureless white ball and read as "the
// sun is still up".  The moon keeps the same angular size (the disk slider
// drives it) but gets a real lunar surface - dark maria, mottled regolith and
// a few bright ray craters - at a luminance close to the real albedo ratio
// instead of the solar one.  Fully procedural, so no texture, sampler or extra
// descriptor binding is needed in the ray-tracing set.
const float MOON_DISK_LUMINANCE = 0.85;

vec3 evaluateMoonSurface(vec3 rayDir, vec3 moonDir, float limbCosine)
{
    vec3 reference = abs(moonDir.y) > 0.95 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 moonRight = normalize(cross(reference, moonDir));
    vec3 moonUp = cross(moonDir, moonRight);
    float radiusSin = max(sqrt(max(1.0 - limbCosine * limbCosine, 0.0)), 1.0e-4);
    vec2 local = vec2(dot(rayDir, moonRight), dot(rayDir, moonUp)) / radiusSin;
    float r = length(local);
    if(r >= 1.0)
    {
        return vec3(0.0);
    }
    // Spherical warp: without it the crater field stretches at the limb.
    vec3 samplePosition = vec3(local, sqrt(max(1.0 - r * r, 0.0)));
    float maria = smoothstep(0.40, 0.66, fbm3D(samplePosition * 2.3 + 5.0));
    float mottle = fbm3D(samplePosition * 7.0 + 23.0);
    float rays = smoothstep(0.74, 0.96, fbm3D(samplePosition * 12.0 + 71.0));
    float albedo = clamp(0.94 - 0.46 * maria + 0.12 * (mottle - 0.5) + 0.20 * rays,
        0.22, 1.0);
    // Crisp limb (6% of the radius) instead of the sun's very soft feather, so
    // the moon reads as a solid body rather than as another glow.
    return vec3(albedo * smoothstep(1.0, 0.94, r));
}


void main() {
    payload.hitDistance = -1.0;
    payload.normal = vec3(0.0);

    vec3 rayDir = gl_WorldRayDirectionEXT;

    // 2026-10-05 末地天空：本文件所有新分支都包在这个门里，主世界/下界逐字节不变。
    const bool isEnd = abs(cam.weatherParams.z - 3.0) < 0.25;

    // At night the visible disk is the moon, opposite the sun and therefore
    // above the horizon, matching the raygen light direction exactly.
    float sunElevation = getTimeDrivenSunElevation();
    vec3 sunDir = getTimeDrivenSunDirection();
    // 翻转之前先留一份真实太阳方向：地球的晨昏线与全食遮挡必须用它（夜里那份
    // 翻转后的方向是"月亮"，拿它算会让地球在夜里整面反着亮）。与原翻转逻辑
    // 并存，不改它。
    vec3 realSunDir = sunDir;
    if(sunElevation < 0.0)
    {
        sunDir = -sunDir;
    }

#if END_ORB_ENABLE
    // 2026-10-07 地球退役：末地天体只剩光源球，遮挡/相位/地球盘全部下线。
#else
    // 地球方向（现在**会动**：2 圈/周期，沿黄道；见 END_SKY_SPIN_RATE）。
    // 遮挡因子、地球盘取景、地球相位三处必须用同一个值，所以在这里算一次。
    vec3 earthDir = endEarthDirection();
#endif

    vec3 diskColor = sunElevation >= 0.0
        ? vec3(1.0, 0.98, 0.9)
        // Moon regolith is a warm grey, not a blue bulb; the cool moonlight
        // tint belongs to glowColor below.
        : vec3(0.97, 0.95, 0.92);
    vec3 glowColor = sunElevation >= 0.0
        ? vec3(1.0, 0.82, 0.55)
        : vec3(0.42, 0.54, 0.95);
    // 太阳视觉滑杆：以默认值 (1.20, 0.18, 0.53°) 归一，默认时与旧固定值完全一致。
    // 夜晚改为月亮基准亮度：太阳盘 20.0 会直接过曝成白球，月亮用接近真实
    // 反照率的 0.85（≈ 太阳表面的 4%），使月面细节能留在显示范围内。
    float diskScale = cam.skyParams0.x *
        (sunElevation >= 0.0 ? 20.0 / 1.20 : MOON_DISK_LUMINANCE / 1.20);
    float glowScale = cam.skyParams0.y *
        (sunElevation >= 0.0 ? 0.4 / 0.18 : 0.2 / 0.18);

    vec3 zenithColor = vec3(0.12, 0.28, 0.58);
    vec3 horizonColor = vec3(0.48, 0.62, 0.82);
    float skyGradient = pow(clamp(1.0 - rayDir.y, 0.0, 1.0), 2.2);
    vec3 skyColor = mix(zenithColor, horizonColor, skyGradient);

    // 2026-10-22：LUT 里米氏日晕（单次散射前向瓣）的亮度占比，由
    // sky_atmosphere.comp 写在 alpha。它不是解析项，bit 14/15 关不掉；
    // 只有阴影水面反射（bit 15）读它，其余路径保持 0 ⇒ 乘 1.0 逐位不变。
    float skyAureoleRatio = 0.0;
    if(cam.skyParams1.y >= 0.5)
    {
        // 散射强度已由天空预计算 pass 应用，这里直接采用纹理值。
        // Explicit LOD: implicit derivatives are undefined in a miss shader
        // (same reason evaluateStarMap pins LOD 0), and on this driver the
        // undefined LOD sampled garbage here -- magenta curtains across the
        // night sky.  The sampler already pins maxLod = 0, but that only
        // clamps the *result* of the derivative computation; the NaN
        // derivative itself still reaches the sampler.
        vec4 scattering = textureLod(skyScatteringTex,
            directionToEquirect(rayDir), 0.0);
        skyColor = scattering.rgb;
        skyAureoleRatio = clamp(scattering.a, 0.0, 1.0);

        // 太阳圆盘颜色取自大气透射（纹理内已按亮度归一化）。
        // 滑杆 0.6 及以上 = 完整物理染色（默认不变）；低于 0.5 逐渐褪去染色。
        //
        // 2026-10-01 修（月亮被涂黑）：夜里 sunDir 已经翻到月亮方向，可透射率
        // 纹理里存的是**真太阳**的 GetTransmittanceToSun —— 太阳一落到地平线下，
        // 它末尾的 smoothstep(-sinθh·ω, +sinθh·ω, mu_s - cosθh) 就精确归零
        // （bruneton/common.glsl:193），于是 textureTint = 0 ⇒ diskColor *= 0
        // 把月盘整个涂黑。下方 legacy 分支本就带 sunElevation 门，这里与它对齐：
        // 透射染色只在白天生效（glow 本来就是 day-only，一并进同一条件）。
        if(sunElevation >= 0.0)
        {
            vec3 textureTint = textureLod(skyTransmittanceTex,
                directionToEquirect(sunDir), 0.0).rgb;
            vec3 transmittanceTint = mix(vec3(1.0),
                textureTint, clamp(cam.skyParams1.x * 2.0, 0.0, 1.0));
            diskColor *= transmittanceTint;
            glowColor = mix(glowColor, glowColor * transmittanceTint, 0.75);
        }
    }
    else
    {
        // 兜底：分析式透射染色
        float tintStrength = cam.skyParams1.x;
        if(tintStrength > 0.001 && sunElevation >= 0.0)
        {
            vec3 tint = analyticSunTint(max(sunDir.y, 0.0));
            diskColor *= mix(vec3(1.0), tint, tintStrength);
            glowColor = mix(glowColor, glowColor * tint, tintStrength * 0.75);
        }
    }

    // 2026-10-05 末地：黑寂太空 + 星空背景。末地没有大气，所以这里直接覆盖掉
    // 大气散射 / legacy 蓝渐变，不叠任何雾/云/体积光。
    // 2026-10-18：背景改为 Star Nest；构成仍抽在 end_sky_common.glsl ——
    // sky_atmosphere.comp 的天空 LUT 与球体环境反射要用**同一份**代码，否则
    // "进 LUT / 进反射的星空"会与眼睛看到的错位。
    if(isEnd)
    {
        // 2026-10-18 星空重做：Star Nest 体积分形星空（end_sky_common.glsl）。
        // 原图集银河 + 程序化银河已退役。时间用 frameCount*0.03（≈秒级@60fps），
        // 与天空 LUT / 球体环境反射传同一个值，三处观感一致。
        skyColor = endSkyRadiance(rayDir, float(cam.frameCount) * 0.03);
    }

    // 2026-10-06 极光（雪地 / 云杉群系）：纯天空层叠加。夜间 / 雨雪 / 维度的门控
    // 与帘幕画法都在 aurora_common.glsl 里，这里只加一项辐射。非寒冷群系强度为 0
    // ⇒ 加 vec3(0) 在主世界之外逐位不变。
    skyColor += evaluateAurora(rayDir, sunElevation);

    // 夜间星空：太阳沉入地平线后渐显，随恒星时旋转
    float nightFactor = smoothstep(0.08, -0.15, sunElevation);
    // 拂晓时天已经在亮，星必须比夜里淡；否则紫玫瑰色天幕上还压着一片亮星。
    nightFactor *= mix(1.0, PREDAWN_STAR_FADE, twilightPredawnWeight(sunElevation));
    // 2026-09-30 P1 雨天：星随雨隐没（穹顶散射已在 sky_atmosphere 压暗，
    // 这里补非穹顶成分）。雨天能量 0.05 ≈ 原版"雨中看不见星"。
    float rainCelestial = mix(1.0, 0.05, clamp(cam.weatherParams.x, 0.0, 1.0));
    if(nightFactor > 0.001 && cam.skyParams0.w > 0.001)
    {
        float horizonFade = smoothstep(-0.05, 0.18, rayDir.y);
        vec3 stars;
        if(STAR_MAP_ENABLED)
        {
            // 末地的星空与银河同一个 2× 速率；非末地传 1.0（常量折叠后与旧代码一致）。
            stars = evaluateStarMapRate(rayDir, isEnd ? END_SKY_SPIN_RATE : 1.0);
        }
        else
        {
            float density = evaluateStarField(rayDir);
            vec3 starColor = mix(vec3(0.55, 0.65, 0.95), vec3(1.0, 0.92, 0.78),
                hash13(floor(rayDir * 90.0)) * 0.6);
            stars = starColor * density * 1.5;
        }
        // 末地的银河子图里本来就含星点，程序化星场只作为很淡的叠加
        // （END_STAR_STRENGTH；非末地乘 1.0，逐位不变）。
        skyColor += stars * nightFactor * horizonFade * cam.skyParams0.w *
            rainCelestial * (isEnd ? END_STAR_STRENGTH : 1.0);
    }

    float sunDot = max(dot(rayDir, sunDir), 0.0);
    // 角直径滑杆（度，默认 0.53）：cos 阈值 ≈ 1 - θ²/2，按 (θ/0.53)² 缩放即可线性改变视直径，
    // 默认值时与旧固定阈值 0.9992 / 0.9998 完全一致。
    //
    // 2026-10-05 末地：视角在月球上，太阳视直径固定 0.53°（不跟滑杆走），并按
    // (滑杆/0.53)² 补偿盘面辐亮度 —— 立体角缩小多少倍、辐亮度就放大多少倍，
    // 于是地表照度仍与主世界正午观感相当。滑杆保持默认 0.53 时该系数恰为 1.0。
    float sunDiameterDegrees = isEnd ? END_SUN_DIAMETER_DEGREES : cam.skyParams0.z;
    float sizeScale = clamp(sunDiameterDegrees / 0.53, 0.0, 10.0);
    // ⚠ 末地**不能**复用主世界那两颗阈值：0.9992 / 0.9998 的 acos 是
    //   2.29° / 1.15° 的**半径**（直径约 4.6°）—— 主世界的滑杆刻度"0.53"
    //   不是度数，那边画出来的日盘比 1.9° 的地球还大。末地直接按真实角半径
    //   取 cos 阈值，才是"14 px 的小圆盘"。0.85 倍收成 ≈1.5 像素软边。
    float diskOuter = isEnd
        ? cos(END_SUN_ANGULAR_RADIUS)
        : 1.0 - (1.0 - 0.9992) * sizeScale * sizeScale;
    float diskInner = isEnd
        ? cos(END_SUN_ANGULAR_RADIUS * 0.85)
        : 1.0 - (1.0 - 0.9998) * sizeScale * sizeScale;
    // 非末地恒 1.0（乘 1.0 在 IEEE 下逐位精确），主世界/下界不受影响。
    float endIlluminanceScale = isEnd
        ? pow(clamp(cam.skyParams0.z, 0.0, 90.0) / END_SUN_DIAMETER_DEGREES, 2.0)
        : 1.0;
#if END_ORB_ENABLE
    // 2026-10-07 地球退役 ⇒ 无月食；遮挡因子恒 1（非末地本来恒 1.0）。
    float endEclipse = 1.0;
#else
    // 月食遮挡：地球完全挡住太阳时可见盘整体消失。非末地恒 1.0。
    float endEclipse = endEarthEclipseFactor(realSunDir, earthDir);
#endif
    // Day: soft-feathered solar disk.  Night: the same footprint filled with
    // the procedural lunar surface (the moon is always full here, because the
    // sun and the moon are exactly opposite in this cycle model).
    // P1 雨天：日盘/月面随雨隐没（0.15 ≈ 原版"雨中看不见日月"）。
    vec3 sunDisk = isEnd
        // 2026-10-07 末地太阳/月亮盘退役：末地的天体只剩光源球（下面）。
        ? vec3(0.0)
        : sunElevation >= 0.0
            ? (diskColor * endIlluminanceScale) * smoothstep(diskOuter, diskInner, sunDot) * diskScale
            : (diskColor * endIlluminanceScale) * evaluateMoonSurface(rayDir, sunDir, diskInner) * diskScale;
    sunDisk *= rainCelestial;
    // 月食：可见盘整体消失（全食时 sunDot 的 softstep 值再乘 0 —— 抗锯齿边缘
    // 也一起收掉，不会留下"细线太阳"）。
    sunDisk *= endEclipse;
#if GI_SKIP_SUN_DISK || WATER_SKY_SUN_OCCLUSION
    // MIS-A：GI 射线不计日盘（置位者见 rt_test.rgen 的 GI 回路 trace 点）。
    // 2026-10-22：阴影水面的反射射线也置这一位（同一次水面太阳透射）。
    // 放在日盘全部调制之后、合成之前：只影响这一段，日冕瓣由 bit 15 单独处理。
    if((payload.state & RT_STATE_NO_SUN_DISK_BIT) != 0u)
    {
        sunDisk = vec3(0.0);
    }
#endif
    // The glow lobe is the sun's aureole and carries no day/night switch of its
    // own.  After the sunset flip above, sunDir points at the moon, so this term
    // used to paint a ~+-8 degree cool-blue halo around a disk only ~1 degree
    // wide -- drowning the procedural lunar surface it is meant to sit beside.
    // The moon is a solid body here, not a light source, so the lobe stays
    // day-only.  The daytime aureole is bit-for-bit unchanged.
    vec3 sunGlow = sunElevation >= 0.0
        ? glowColor * pow(sunDot, 64.0) * glowScale
        : vec3(0.0);
    sunGlow *= rainCelestial;
    // 日冕瓣也得一起被地球挡住：日盘半径只有 0.265°，而 pow64 的瓣宽到 ±8°，
    // 不压下去的话全食时会在纯黑地球周围留一圈橙光。非末地恒 1.0。
    sunGlow *= endEclipse;
    // 末地是真空：没有大气就没有日冕瓣（否则 0.53° 的小盘会被 ±8° 的瓣糊成
    // 一大团亮斑，就是用户报的"太阳太大"）。非末地乘 1.0，逐位不变。
    sunGlow *= isEnd ? END_SUN_GLOW_SCALE : 1.0;
#if WATER_SKY_SUN_OCCLUSION
    // 阴影水面的反射：日冕瓣同样不可见（否则会从墙顶/屋檐边缘漏成白斑）。
    if((payload.state & RT_STATE_NO_SUN_GLOW_BIT) != 0u)
    {
        sunGlow = vec3(0.0);
        // 日盘/日冕是解析项，LUT 天空里还烘着一块米氏日晕（太阳旁亮一两个
        // 数量级的前向瓣）。只关解析项的话，反射天空里仍会留下那块白斑 ——
        // 按烘焙时算好的亮度占比把这一份一起减掉（不是整片压天空）。
        if(!isEnd)
        {
            skyColor *= 1.0 - skyAureoleRatio;
        }
    }
#endif

    vec3 finalColor = skyColor + sunDisk + sunGlow;

    // 2026-10-07 末地光源球 v3：按 Shadertoy "AI Slop v15" Buffer A 移植 ——
    // 玻璃球(IOR1.46) + 金色等离子体(弦上 march) + 内嵌星壳 + 刻符金箍带 +
    // B/C/D 辉光链(角空间高斯近似)。原版是屏幕空间后期 + 完整场景，miss 里
    // 单射线无邻域、无递归 ⇒ "behind"用同方向天空近似、env 反射省略、
    // 色散省略；PLASMA/GLYPH_GLOW 的 HDR 峰值会被 main 末尾 2.5 的 albedo
    // 钳制压平。旋转矩阵 rot(a)=mat2(c,-s,s,c) 与原版同构。
#if END_ORB_ENABLE
    if(isEnd)
    {
        vec3 orbDir = endOrbDirection();
        float orbCos = dot(rayDir, orbDir);
        float orbAngle = acos(clamp(orbCos, -1.0, 1.0));
        // 时钟：脉动/符文闪烁/星眨用 frameCount（≈秒级@60fps），等离子体
        // 漂移用 unwrap 后的公转相位（×29.5，与方向副本同一公式）×速率。
        float tFrame = float(cam.frameCount) * 0.03;
        float tDay = fract(cam.historyParams.w * 29.5) * END_ORB_ANIM_RATE;
        float pulse = 0.85 + 0.15 * sin(tFrame * 1.7);

        // B/C/D 辉光链：紧晕+宽晕两档解析高斯（σ/强度见 sky_model_common 注释）。
        float orbHalo = exp(-orbAngle * orbAngle *
            (1.0 / (2.0 * END_ORB_HALO_SIGMA * END_ORB_HALO_SIGMA)));
        float orbAura = exp(-orbAngle * orbAngle *
            (1.0 / (2.0 * END_ORB_AURA_SIGMA * END_ORB_AURA_SIGMA)));
        vec3 glow = END_ORB_GLOW_COLOR *
            ((END_ORB_HALO_STRENGTH * orbHalo + END_ORB_AURA_STRENGTH * orbAura) * pulse);

        // 几何：单位距离球（Dc=1，物理半径 rs=sin α）。原版球在本地空间是
        // 单位球 ⇒ 本地坐标 = (p - C)/rs，方向不变。
        const float orbRs = sin(END_ORB_ANGULAR_RADIUS);
        float disc = orbCos * orbCos - (1.0 - orbRs * orbRs);
        bool hitS = disc > 0.0;
        float t0 = orbCos - sqrt(max(disc, 0.0));
        vec3 pin = rayDir * t0;
        vec3 n = normalize(pin - orbDir);
        float ndv = max(dot(n, -rayDir), 0.0);
        // 从空气入玻璃不会全内反射，refract 恒有效。
        vec3 r1 = refract(rayDir, n, 1.0 / END_ORB_IOR);
        float chordL = -2.0 * dot(n, r1);   // 本地单位球弦长

        vec3 base = finalColor;
        // 球内"透过的天空"的兜底值：任何非有限值 / 透射塌陷都回落到它。
        vec3 orbTransmittedSky = finalColor * END_ORB_SKY_FLOOR;
        if(hitS)
        {
            vec3 pinL = (pin - orbDir) / orbRs;
            // 弦上 march 等离子体 + 金线（原版 64 步，这里 32 步；抖动去带纹）
            vec3 vol = vec3(0.0);
            float trans = 1.0;
            float dt = chordL / float(END_ORB_VOLUME_STEPS);
            float jitter = hash13(rayDir * 137.0);
            float tv = dt * jitter;
            for(int i = 0; i < END_ORB_VOLUME_STEPS; i++)
            {
                vec3 p = pinL + r1 * tv;
                vec4 pl = orbPlasma(p, pulse, tDay);
                vol += trans * (pl.rgb + orbWisps(p, tDay) * 3.0) * dt;
                trans *= exp(-pl.a * dt);
                tv += dt;
            }
            // 五层内嵌星壳（折射线上，保留放大效应）
            vec3 stars = vec3(0.0);
            float bm = dot(pinL, r1);
            for(int k = 0; k < 5; k++)
            {
                float fk = float(k);
                float shellR = 0.25 + 0.15 * fk;
                float hM = bm * bm - (1.0 - shellR * shellR);
                if(hM > 0.0)
                {
                    hM = sqrt(hM);
                    vec3 tint = mix(vec3(0.7, 0.85, 1.0), vec3(1.0, 0.85, 0.6),
                        hash13(vec3(fk, 9.0, 4.0)));
                    stars += tint * (orbStarLayer(pinL + r1 * (-bm - hM),
                                    24.0 - 3.0 * fk, fk, tFrame) +
                                     orbStarLayer(pinL + r1 * (-bm + hM),
                                    24.0 - 3.0 * fk, fk, tFrame));
                }
            }
            // 玻璃体：吸收染色 + 乳白 + 菲涅尔；"behind"用同方向天空近似。
            //
            // 2026-10-09 黑球修复：这一项（透过的天空）是真空里这颗球**唯一**能让
            // 它看起来像玻璃球的成分 —— 没有 cubemap 可做环境反射，而理想玻璃球对
            // 无穷远天空本来就不产生角向畸变（只平行位移），所以正确观感就是
            // "透过来的天空 ×(1-F) + 菲涅尔边 + 内部等离子体"。一旦这一项被吃掉
            // （吸收吃满 / 透射率塌成 0），整颗球就只剩"黑圆盘 + 外圈暖晕" —— 正是
            // 用户报的"末地有这种球形区域"。下面加一条下限 + 非有限值回退，保证
            // 球内**永远**透出至少 END_ORB_SKY_FLOOR 倍的天空。
            vec3 absorb = exp(-chordL * vec3(0.10, 0.04, 0.05));
            float F = 0.04 + 0.96 * pow(1.0 - ndv, 5.0);
            vec3 transmittedSky = finalColor * absorb * trans;
            if(any(isnan(transmittedSky)) || any(isinf(transmittedSky)))
            {
                transmittedSky = orbTransmittedSky;
            }
            transmittedSky = max(transmittedSky, orbTransmittedSky);
            orbTransmittedSky = transmittedSky;
            vec3 glass = (transmittedSky + vol +
                stars * 8.0 * (0.3 + 0.7 * trans)) * (1.0 - F);
            glass += vec3(0.9, 0.95, 1.0) * END_ORB_MILK * chordL;
            // 2026-10-09：补回 v15 移植时省掉的**环境反射**（原版有 env 反射项，
            // 当初"无 cubemap 故省略"）。miss 里没有 cubemap，但可以把反射方向直接
            // 送进同一个末地天空函数（2026-10-18 起 = Star Nest）——与可见天空
            // 同源，球缘因此会映出旁边的星云。按同一个菲涅尔 F 加权 ⇒ 与上面透射项的
            // (1-F) 严格互补（能量守恒）。这正是"玻璃球读起来像球、而不是一个黑
            // 洞"的关键：球后面的天区在末地多半是黑的，只靠透射，球心永远是黑的。
            // 代价：球只占天空立体角约 2.3%，只有这些像素多算一次银河。
#if END_ORB_ENV_REFLECTION
            {
                vec3 reflDir = reflect(rayDir, n);
                vec3 reflSky = endSkyRadiance(
                    reflDir, float(cam.frameCount) * 0.03);
                if(!any(isnan(reflSky)) && !any(isinf(reflSky)))
                {
                    glass += F * reflSky;
                }
            }
#endif
            // 前表面薄膜虹彩（原版还有 env 反射项，无立方体贴图故省略）
            vec3 film = 0.5 + 0.5 * cos(6.28318531 * (ndv * 1.6 +
                noise3D(n * 2.5 + tDay * 0.05) * 1.2 + vec3(0.0, 0.33, 0.67)));
            glass += film * vec3(0.7, 0.8, 1.2) * pow(1.0 - ndv, 1.5) * 0.18;
            float bodyMask = smoothstep(END_ORB_ANGULAR_RADIUS,
                END_ORB_ANGULAR_RADIUS - 0.0015, orbAngle);
            base = mix(base, glass, bodyMask);
        }
        else
        {
            // 球外近场逃逸光（v15: exp(-(dc-1)*3)*0.1*pulse）
            float dcU = sin(orbAngle) / orbRs;
            glow += END_ORB_GLOW_COLOR *
                exp(-max(dcU - 1.0, 0.0) * 3.0) * 0.1 * pulse;
        }

        // 金箍带 v4：真 3D SDF march（v15 主循环逐字换算）。v3 的投影椭圆
        // 没有透视收缩/圆唇/刻槽法线，是"贴纸感"的根源。包围球 1.3（球半径
        // =1 的本地单位），march 止于玻璃前表面 —— 远半带被玻璃挡住的遮挡
        // 自动成立，不再需要手写 q2 遮蔽判定。
        float spinA = tDay * 0.15;
        const float bandBoundR = 1.3 * orbRs;
        float discB = orbCos * orbCos - (1.0 - bandBoundR * bandBoundR);
        bool hitBand = false;
        float tBand = 0.0;
        if(discB > 0.0)
        {
            float hB = sqrt(discB);
            float tB = max(orbCos - hB, 0.0);
            float tEnd = orbCos + hB;
            if(hitS) tEnd = min(tEnd, t0);
            for(int i = 0; i < 110; i++)
            {
                float dLocal = orbSdBand((rayDir * tB - orbDir) / orbRs, spinA);
                if(dLocal < 0.0008) { hitBand = true; break; }
                tB += dLocal * orbRs * 0.7;
                if(tB > tEnd) break;
            }
            tBand = tB;
        }
        if(hitBand)
        {
            vec3 bpLocal = (rayDir * tBand - orbDir) / orbRs;
            base = orbShadeBand(bpLocal, orbBandNormal(bpLocal, spinA),
                rayDir, spinA, tFrame);
        }
        finalColor = base + glow;
        // 2026-10-08 的品红 NaN 探针 2026-10-09 退役：显示端把 NaN/±Inf 直接写成
        // 纯黑（看起来就是"黑球 + 外圈暖晕"），染品红只是把同一个坏值换个颜色，
        // 不是修复。现在一律回落到球内透射的天空（= 玻璃球本该有的样子），
        // 探针留在 -DEND_ORB_NAN_MAGENTA=1 备用。
#if END_ORB_NAN_MAGENTA
        if(any(isnan(finalColor))) finalColor = vec3(8.0, 0.0, 8.0);
#endif
        if(any(isnan(finalColor)) || any(isinf(finalColor)))
        {
            finalColor = orbTransmittedSky;
        }
    }
#else
    // 2026-10-05 末地地球盘。位置：星空之后、日月盘之上 —— 地球是实体，会挡住
    // 它后面的日盘（全食正是这个几何）。
    // 用 mix 而不是 return：main 末尾还要写 payload.albedo，早退会漏掉它。
    if(isEnd)
    {
        vec3 earthVec = rayDir;   // miss 射线的方向就是视线方向
        float earthCos = dot(earthVec, earthDir);
        float earthAngle = acos(clamp(earthCos, -1.0, 1.0));
        if(earthAngle < END_EARTH_ANGULAR_RADIUS)
        {
            // 视面上那一点的球面法线。**不能用** normalize(rayVec - dir*dot)
            // 那种近似：它在盘心退化成 normalize(0)（黑点），而且整片法线都垂直于
            // 视线 —— 晨昏线会反。这里用真实球面求交的结果：
            //   n = ((cos a - sqrt(R² - sin²a)) · r - D) / R
            // 于是盘心 n = -D（正对我们），盘缘 n ⊥ D，正确的半球。
            float earthSin = sqrt(max(1.0 - earthCos * earthCos, 0.0));
            // 球面求交公式里用的是 sin R 而不是 R 本身（25° 时两者差 3%，会
            // 让晨昏线位置偏一点）。
            const float earthRadiusSin = sin(END_EARTH_ANGULAR_RADIUS);
            float earthDepth = sqrt(max(
                earthRadiusSin * earthRadiusSin - earthSin * earthSin, 0.0));
            vec3 n = normalize(
                ((earthCos - earthDepth) * earthVec - earthDir) /
                earthRadiusSin);

            // 球面 UV。自转轴与朝向见 endEarthAxis() 的注释（与地球方向始终垂直
            // ⇒ 视面中心永远在赤道附近）。自转的"快相位"从慢相位还原：
            // slow = 天数/29.5 ⇒ slow*29.5 = 天数，fract 后就是"每 MC 天一圈"。
            vec3 earthAxis = endEarthAxis();
            vec3 earthRight = normalize(
                cross(earthAxis, vec3(0.0, 0.0, 1.0)));
            vec3 earthFwd = cross(earthRight, earthAxis);
            float earthLon = atan(dot(n, earthFwd), dot(n, earthRight));
            float earthLat = asin(clamp(dot(n, earthAxis), -1.0, 1.0));
            float spinPhase = fract(cam.historyParams.w * 29.5);
            // ⚠ u 必须 fract 回绕到 [0,1)：atan 值域是 (-π,π]，转成 u 再加上自转
            //   相位后是 (-0.5, 1.5)。不回绕就会越界采到**隔壁象限**（夜面 / 银河），
            //   表现为地球上一道道"拼接错的贴图"（2026-10-05 用户报"三道不同的
            //   贴图"就是这个）。等距圆柱在 u=0/1 处本来就是同一条子午线（±180°），
            //   回绕正是它应有的行为。
            float earthU = fract(earthLon * 0.15915494 + spinPhase);
            // ⚠ 纬度要翻：等距圆柱的 v=0 是**北极**，而 lat=+90° 代入 0.5+lat/π 得到
            //   1.0（子图下边）⇒ 不翻则地球南北倒置（南极冰盖长在头顶）。
            float earthV = 0.5 - earthLat * 0.31830989;

            // 图集 2x2 象限（8192x4096，契约见 rt_end_atlas.png 与 §8.10）：
            // 左上银河 / 右上云层 / 左下昼面 / 右下夜面，每格 4096x2048（2:1）。
            // LOD 显式给 0：miss 里隐式导数是未定义的（曾经的品红幕布）。
            // 半纹素内缩（END_ATLAS_INSET）：不加的话极点那一行会混进隔壁格。
            float atlasU = endAtlasInset(earthU);
            float atlasV = endAtlasInset(earthV);
            vec3 earthDayColor = textureLod(endSkyAtlas,
                vec2(atlasU * 0.5, atlasV * 0.5 + 0.5), 0.0).rgb;
            vec3 earthNightColor = textureLod(endSkyAtlas,
                vec2(atlasU * 0.5 + 0.5, atlasV * 0.5 + 0.5), 0.0).rgb;

            // 云层：独立相位（比地面转得快一点 ⇒ 云相对大陆漂移）。同样 fract
            // 回绕、同样翻纬度（云图与地图书同一套等距圆柱约定）。
            // 源图是灰度"白云/黑底"，红通道就是密度；阈值见 sky_model_common.glsl。
            float cloudSpin = fract(
                cam.historyParams.w * 29.5 * END_CLOUD_SPIN_MULTIPLIER);
            float cloudU = fract(earthLon * 0.15915494 + cloudSpin);
            float cloudRaw = textureLod(endSkyAtlas,
                vec2(endAtlasInset(cloudU) * 0.5 + 0.5, atlasV * 0.5), 0.0).r;
            float cloudDensity = smoothstep(
                END_CLOUD_THRESHOLD_LO,
                END_CLOUD_THRESHOLD_HI,
                cloudRaw) * END_CLOUD_OPACITY;

            // 晨昏线。用**真实**太阳方向定（全食时太阳在地球背后 ⇒ 正对我们的
            // 是暗面 + 城市灯；太阳在我们背后 ⇒ 完整昼面）—— 这就是"亮暗面随太阳
            // 变化"，而且地球现在会扫过天空，相位变化 4.9 小时就能看一轮。
            float sunOnSurface = dot(n, realSunDir);
            float dayLit = smoothstep(-0.04, 0.04, sunOnSurface);

            // 图集是 sRGB 8bit，先解成**线性反射率**再上光。不解码的话暗端会被
            // 显示端的 sRGB 编码大幅抬亮（夜景字节 0.02 → 屏幕 17% 灰），整颗球
            // 发灰、明暗对比弱 —— 这正是"明暗对比明显点"要修的东西。
            vec3 albedoDay = pow(earthDayColor, vec3(END_EARTH_SRGB));
            vec3 albedoNight = pow(earthNightColor, vec3(END_EARTH_SRGB));

            // 昼面用**中性正午日光**（地球昼面是被它自己的正午照亮的，与末地此刻
            // 太阳的高度角无关；否则末地夜里看"满地"时地球会被涂成落日红），
            // 再叠朗伯衰减：越靠晨昏线越暗 ⇒ 球体转折感（原实现整片一个亮度）。
            float dayRamp = smoothstep(0.0, END_DAY_RAMP_TOP, sunOnSurface);
            // 夜面的极区冰盖要压掉：夜景图里两极是亮的（源数据），而视面中心在
            // 赤道 ⇒ 两极正好在盘缘，不压就会多出一圈亮弧（离线渲染实测）。
            float nightPolarFade = 1.0 - END_NIGHT_POLAR_FADE * smoothstep(
                0.70, 0.96, abs(earthLat) * 0.63661977);
            vec3 earthColor = mix(
                // 夜面：先减掉夜景图的灰蓝底（它不是纯黑底），再增益成城市灯。
                // 顺序反了会把整颗夜面抬成亮蓝 —— 明暗对比就在这里。
                max(albedoNight - vec3(END_NIGHT_BASE), vec3(0.0))
                    * (END_NIGHT_LIGHTS_STRENGTH * nightPolarFade),
                albedoDay * END_EARTH_DAYLIGHT * dayRamp,
                dayLit);

            // 云只被昼面照亮：正对太阳处是白的，贴近晨昏线的云染暖色，夜面看不见
            // （所以乘 dayLit）。云的强度在解码后的线性空间里给（≈0.8 白云反照率）。
            float cloudLit = smoothstep(0.0, 0.30, sunOnSurface);
            vec3 cloudWarm = vec3(END_CLOUD_ALBEDO,
                END_CLOUD_ALBEDO * 0.72, END_CLOUD_ALBEDO * 0.55);
            vec3 cloudColor = mix(cloudWarm, vec3(END_CLOUD_ALBEDO), cloudLit);
            // 云的亮度也要吃同一份朗伯衰减（dayRamp）：否则晨昏线只剩地面变暗、
            // 云还亮着，盘缘会出现一圈白云弧（离线渲染实测）。
            earthColor = mix(earthColor,
                cloudColor * dayRamp, cloudDensity * dayLit);

            // 月食的红边：地球大气把阳光折射到暗面边缘。只在遮挡窗口里出现
            //（(1 - endEclipse) 在其余时刻恒为 0，所以平时没有这圈红）。
            // 宽度按**弧度**给（0.006 rad ≈ 0.34°，约 9 像素）——按半径百分比给
            // 的话，地球放大到 40° 后 10% 就是 4°＝110 像素的大红环。
            float rimBand = smoothstep(
                END_EARTH_ANGULAR_RADIUS - 0.006,
                END_EARTH_ANGULAR_RADIUS, earthAngle);
            // 0.18 是"解码后的线性空间"里的量级（昼面约 0.1、夜面近 0），
            // 所以这圈红比暗面亮一个量级、又不会盖过昼面。
            earthColor += vec3(1.0, 0.35, 0.15) *
                (rimBand * (1.0 - endEclipse) * 0.18);

            // 边缘抗锯齿：同样按弧度给约 1.5 像素的过渡（0.0012 rad ≈ 0.069°）。
            float earthCoverage = smoothstep(
                END_EARTH_ANGULAR_RADIUS,
                END_EARTH_ANGULAR_RADIUS - 0.0012,
                earthAngle);

            finalColor = mix(finalColor, earthColor, earthCoverage);
        }
    }
#endif // END_ORB_ENABLE

    // 次级光线保留 Tyndall 云行军；主光线天空像素由计算云管线合成。
    if((payload.state & 0x7u) != RT_PURPOSE_PRIMARY_SKY)
    {
        vec4 cloudResult = sampleVolumetricClouds(
            gl_WorldRayOriginEXT,
            rayDir,
            sunDir);
        finalColor = mix(finalColor, cloudResult.rgb, 1.0 - cloudResult.a);
    }

    // Preserve RGB transmission accumulated by GLASS_VISIBILITY Any-Hit.
    // Ordinary sky/environment rays still receive the physical sky colour.
    if((payload.state & 0x7u) != 5u)
    {
        payload.albedo = clamp(finalColor, 0.0, 2.5);
    }
    // Deferred mode: these resets happen in the ray-generation dispatcher, which
    // owns the local HitPayload; the identity payload has no such fields.
    payload.roughness = 1.0;
    payload.f0Metal = 0.0;
    payload.emission = vec3(0.0);
    payload.parallaxRayTravel = 0.0;
    payload.pomSelfShadow = 1.0;

    // 2026-10-09 运动矢量：显式清零。
    // payload 在同一像素内被多次 traceRay 复用，miss 不清零就会把**上一次命中**的
    // 位移留在里面。rgen 侧虽然用 `primaryHitDistance >= 0` 把 miss 排除在外
    // （rmiss 会把 hitDistance 设成 -1.0），但"靠下游判据兜住未初始化值"不该是
    // 唯一防线 —— 这里补齐是零成本的确定行为。
    payload.prevOffsetX = 0.0;
    payload.prevOffsetY = 0.0;
    payload.prevOffsetZ = 0.0;
}
