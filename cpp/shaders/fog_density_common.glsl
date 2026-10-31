#ifndef FOG_DENSITY_COMMON_GLSL
#define FOG_DENSITY_COMMON_GLSL

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

#endif
