// ===========================================================================
// clear_sky_aerial.glsl —— 解析晴空大气透视（A1）的**参数化核心**（单一真源）
// ===========================================================================
// 两个消费者：
//   · rt_test.rgen            —— 地形 / DH 远场的空气透视（2026-10-01 引入的 A1）；
//   · volumetric_clouds.comp  —— 云层空气透视。
//
// 抽出来的理由与 sky_model_common.glsl / sky_sun_common.glsl 同一条：两边必须用
// **同一份数学**，否则"地面远处泛蓝白"与"云层远处泛蓝白"会各说各话 —— 这类
// "两处各写一份然后漂移"是本工程最容易出的静默错位。
//
// ⚠ 本文件**不含任何 getTimeDriven\* / cam / 天气依赖**：太阳高度、光方向、天光色、
//   直射能量标度、天气因子、视线段消光标度全部由**调用方以参数传入**。这样 rgen
//   的 time-driven 调色板栈与云着色器自己的 lightDirection/directLightColor 都能
//   喂进来，而本文件保持纯净、可被任意消费者复用。
// ===========================================================================

const vec3 ATM_RAYLEIGH_BETA = vec3(5.5e-6, 13.0e-6, 22.4e-6);
const vec3 ATM_MIE_BETA = vec3(18.0e-6);
const float ATM_RAYLEIGH_HEIGHT = 8000.0;
const float ATM_MIE_HEIGHT = 1200.0;
const float ATM_MIE_G = 0.76;
// Keep the existing clear-sky/aerial-perspective brightness unchanged after
// restoring the direct-sun scale above.  5.22209428235 = 8.5 * 0.75 /
// 1.22077458876, so only direct/occluded contrast changes; the weather/sky
// response itself does not get brighter.
const float ATM_SKY_EXPOSURE = 5.22209428235;
// 借鉴思路：臭氧吸收 (2.1, 5.0, 0.2)e-6/m 折进直射阳光消光
// （8 km Rayleigh 标高 = (0.0168, 0.0400, 0.0016) 光学深度），只作用于阳光穿过的
// 那一段大气，不动天空散射本身 ⇒ 地平线附近远场直射更红、与
// sky_sun_common.glsl 的物理阳光色保持一致。置 0 即回退。
#ifndef ATM_A1_OZONE
#define ATM_A1_OZONE 1
#endif
#if ATM_A1_OZONE
const vec3 ATM_A1_OZONE_OD = vec3(0.0168, 0.0400, 0.0016);
#else
const vec3 ATM_A1_OZONE_OD = vec3(0.0);
#endif

// 视线段的逐通道基础消光（单位 1/格，标称高度）。调用方乘自己的强度滑杆、
// 高度密度与天气密度 ⇒ 同一份"什么颜色衰减得更快"的物理在两边一致：
// 蓝通道最快 ⇒ 越远越发蓝白。
const vec3 ATM_AP_BASE_EXTINCTION = vec3(0.000030, 0.000052, 0.000090);

float atmosphereAirMass(float elevation)
{
    float h = clamp(elevation, -0.065, 1.0);
    return min(24.0, 1.0 / max(0.045, h + 0.135));
}

float atmosphereRayleighPhase(float cosTheta)
{
    return 0.05968310366 * (1.0 + cosTheta * cosTheta);
}

float atmosphereMiePhase(float cosTheta)
{
    float g2 = ATM_MIE_G * ATM_MIE_G;
    float denominator = max(1.0e-4, 1.0 + g2 - 2.0 * ATM_MIE_G * cosTheta);
    float invDenominator = inversesqrt(denominator);
    return (3.0 * (1.0 - g2) * (1.0 + cosTheta * cosTheta)) /
        (8.0 * 3.14159265359 * (2.0 + g2)) *
        invDenominator * invDenominator * invDenominator;
}

// 某个视线方向上的晴空解析 in-scatter（能量已含 ATM_SKY_EXPOSURE）。
//   skyLightColor            天光色（含暮色 / 金山 / 极光等一切调色）
//   skyLightRadianceScale    直射能量标度（正午 ≈ 1.22、夜晚 ≈ 0.03 的量纲）
//   weatherSunRadianceFactor 雨天对直射的压制（rgen 的 20% 参考响应）
//   weatherSkyTint           雷暴冷色调色
vec3 clearSkyInscatter(
    vec3 rayDir,
    float sunElevation,
    vec3 lightDirection,
    vec3 skyLightColor,
    float skyLightRadianceScale,
    float weatherSunRadianceFactor,
    vec3 weatherSkyTint)
{
    rayDir = normalize(rayDir);
    float cosTheta = clamp(dot(rayDir, lightDirection), -1.0, 1.0);

    float viewMass = atmosphereAirMass(rayDir.y);
    float lightMass = atmosphereAirMass(abs(sunElevation));
    vec3 sunTransmittance = exp2(-1.44269504089 * (
        ATM_RAYLEIGH_BETA * ATM_RAYLEIGH_HEIGHT +
        ATM_MIE_BETA * ATM_MIE_HEIGHT +
        ATM_A1_OZONE_OD
    ) * lightMass);
    vec3 rayleighScatter = vec3(1.0) - exp2(-1.44269504089 * ATM_RAYLEIGH_BETA * ATM_RAYLEIGH_HEIGHT * viewMass);
    vec3 mieScatter = vec3(1.0) - exp2(-1.44269504089 * ATM_MIE_BETA * ATM_MIE_HEIGHT * viewMass);

    vec3 sourceRadiance = skyLightColor *
        (3.2 * skyLightRadianceScale) *
        sunTransmittance * weatherSunRadianceFactor * weatherSkyTint;
    vec3 scatter = sourceRadiance * ATM_SKY_EXPOSURE * (
        rayleighScatter * atmosphereRayleighPhase(cosTheta) +
        mieScatter * atmosphereMiePhase(cosTheta)
    );

    float nightAmount = 1.0 - smoothstep(-0.03, 0.12, sunElevation);
    float horizon = 1.0 - clamp(rayDir.y, 0.0, 1.0);
    horizon *= horizon * (3.0 - 2.0 * horizon);
    vec3 nightAmbient = mix(vec3(0.0010, 0.0018, 0.0050), vec3(0.004, 0.008, 0.020), horizon);
    return max(scatter + nightAmbient * nightAmount, vec3(0.0));
}

// A1 的高度密度：**视线中段**在世界里的高度决定这一段的空气有多厚
// （全工程海平面 = 63 格）。两边必须共用 ⇒ 交接处不会出现亮度台阶。
float clearSkyAerialHeightDensity(
    vec3 cameraOrigin,
    vec3 cameraDirection,
    float distance)
{
    float d = clamp(distance, 0.0, 10000.0);
    float midpointY = cameraOrigin.y + cameraDirection.y * (0.5 * d);
    float heightDensity = exp(-0.025 * clamp(midpointY - 63.0, -32.0, 256.0));
    return clamp(heightDensity, 0.10, 2.23);
}
