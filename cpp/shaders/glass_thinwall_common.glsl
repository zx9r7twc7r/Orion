#ifndef GLASS_THINWALL_COMMON_GLSL
#define GLASS_THINWALL_COMMON_GLSL

#ifndef GLASS_THINWALL_TINT_FLOOR
#define GLASS_THINWALL_TINT_FLOOR 1.0e-4
#endif

#ifndef GLASS_THINWALL_MIN_IOR
#define GLASS_THINWALL_MIN_IOR 1.0001
#endif

// [Remix brdf.slangh:226] evalTranslucentSchlickFresnel
float glassThinSchlickFresnel(float baseReflectivity, float cosTheta)
{
    float c = clamp(cosTheta, 0.0, 1.0);
    float f = 1.0 - c;
    float f2 = f * f;
    return clamp(
        baseReflectivity + (1.0 - baseReflectivity) * f2 * f2 * f,
        0.0,
        1.0
    );
}

// [Remix brdf.slangh:511] evalThinGeometricSeries —— 1 + x^2 + x^4 + ...
vec3 glassThinGeometricSeries(vec3 x)
{
    vec3 x2 = clamp(x * x, vec3(0.0), vec3(1.0 - 1.0e-4));
    return vec3(1.0) / (vec3(1.0) - x2);
}

// Beer-Lambert：每格透射色 transmittanceColor 对应"1 格光程"的测量距离
// （Remix 在 thin-walled 分支里固定用 1.0，见 translucent_surface_material_
//  interaction.slangh:673-675 的注释），这里保持同一口径。
vec3 glassThinBeerAttenuation(vec3 transmittanceColor, float distanceBlocks)
{
    vec3 t = clamp(
        transmittanceColor,
        vec3(GLASS_THINWALL_TINT_FLOOR),
        vec3(1.0)
    );
    return exp(log(t) * max(distanceBlocks, 0.0));
}

// =====================================================
// 一次薄片透射事件的完整结果。
//   transmittanceColor : 每格染料透射色（computeGlassBlockTransmission 输出）
//   thicknessBlocks    : 该玻璃体沿法线方向的厚度（格）
//   cosIncident        : |N·V|，V 指向表面
//   ior                : 该材质折射率（玻璃 1.5 / 冰 1.309 / …）
//   baseReflectivity   : F0（玻璃类 0.04；Remix 用的是 baseReflectivity 而非 ior，
//                        两者在此场景数值一致，见文件头自检）
// 输出：
//   transmission : 透射 lobe 的 throughput（方向不变，已含 (1-Fo)(1-Fi) 与染料）
//   reflection   : 反射 lobe 的 throughput（含内部反射级数）
// =====================================================
void glassThinWallFactors(
    vec3 transmittanceColor,
    float thicknessBlocks,
    float cosIncident,
    float ior,
    float baseReflectivity,
    out vec3 transmission,
    out vec3 reflection
)
{
    float c = clamp(cosIncident, 0.0, 1.0);

    float eta = 1.0 / max(ior, GLASS_THINWALL_MIN_IOR);
    float sinI2 = max(1.0 - c * c, 0.0);
    float sinT2 = sinI2 * eta * eta;

    if(sinT2 >= 1.0)
    {
        transmission = vec3(0.0);
        reflection = vec3(1.0);
        return;
    }

    float cosT = sqrt(max(1.0 - sinT2, 0.0));

    float outsideFresnel = glassThinSchlickFresnel(baseReflectivity, c);
    float insideFresnel = glassThinSchlickFresnel(baseReflectivity, cosT);

    float attenuationDistance =
        max(thicknessBlocks, 0.0) / max(cosT, 1.0e-4);
    vec3 attenuation = glassThinBeerAttenuation(
        transmittanceColor,
        attenuationDistance
    );

    vec3 series = glassThinGeometricSeries(attenuation * insideFresnel);

    transmission =
        attenuation *
        (1.0 - outsideFresnel) *
        (1.0 - insideFresnel) *
        series;

    reflection =
        vec3(outsideFresnel) +
        insideFresnel *
        attenuation * attenuation *
        (1.0 - outsideFresnel) *
        (1.0 - insideFresnel) *
        series;
}

#endif
