#ifndef TRANSPARENT_PROFILE_COMMON_GLSL
#define TRANSPARENT_PROFILE_COMMON_GLSL

uint getTransparentProfileFromRawMaterialId(float materialId)
{
    return uint(
        floor(
            clamp(fract(materialId) * 10.0, 0.0, 4.0) + 0.5
        )
    );
}

#endif
