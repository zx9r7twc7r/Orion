// =====================================================================
// 体积雾密度 —— 单一真源（2026-10-19）
//
// 常量取自 VFOG_NOISE_TYPE == 0 分支的出厂标定。
//
// 为什么单独拆出来：体积雾（volumetric_fog.glsl）与体积光
// （volumetric_light.comp）的 A/B 空气路径在参考里共用同一份 `FogDensity()`；
// 本工程原先两边各自一套介质，高度剖面/雨天加厚对不上。把常量与函数抽到这里，
// 两边 #include 同一份。注意：空气光柱 2026-10-19 已退役（VL_AIR_GODRAYS=0），
// 所以这份"同一层空气"现在只在水下分支之外的 A/B 路径里生效。
//
// 平台适配：无。这一段是参考的逐行移植，常量全部取参考出厂值。
// =====================================================================
#ifndef FOG_DENSITY_COMMON_GLSL
#define FOG_DENSITY_COMMON_GLSL

// 参考出厂（Lib/Settings.glsl）：
//   #define VFOG_DENSITY               0.01
//   #define VFOG_FALLOFF               40.0
//   #define VFOG_SUNLIGHT_ABSORPTION   1.0
//   #define VFOG_SUNLIGHT_STEPLENGTH   5.0
const float VFOG_DENSITY = 0.01;
const float VFOG_FALLOFF = 40.0;
const float VFOG_SUNLIGHT_ABSORPTION = 1.0;
const float VFOG_SUNLIGHT_STEPLENGTH = 5.0;

// 参考 VolumetricFog.glsl:5-16 FogDensity()（VFOG_NOISE_TYPE == 0）。
//   dh      = 到 [heightMin, heightMax] 这个高度带之外的距离
//   density = exp2(-dh * 2/FALLOFF * (1 - wetness*0.5))
//   return  = (density * VFOG_DENSITY*0.15 + baseDensity) * fogTimeFactor
float fogDensityAt(
    vec3 fogPos,
    float baseDensity,
    float fogTimeFactor,
    float wetness,
    float heightMax,
    float heightMin)
{
    float dh =
        max(fogPos.y - heightMax, 0.0) +
        max(heightMin - fogPos.y, 0.0);

    dh *= (2.0 / VFOG_FALLOFF) * (1.0 - wetness * 0.5);

    float density = exp2(-dh);

    return (density * (VFOG_DENSITY * 0.15) + baseDensity) * fogTimeFactor;
}

#endif // FOG_DENSITY_COMMON_GLSL
