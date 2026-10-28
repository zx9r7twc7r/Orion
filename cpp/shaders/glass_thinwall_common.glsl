// =====================================================
// glass_thinwall_common.glsl —— 物理薄片（thin-walled）玻璃滤波
//
// 参考实现（逐条对齐，不自行发明）：
//   dxvk-remix / src/dxvk/shaders/rtx/concept/surface_material/
//     translucent_surface_material_interaction.slangh:539-694
//        · 539 translucentSurfaceMaterialInteractionCalcSpecularTransmissionSample
//        · 598-683  thin-walled 分支：预处理 Fresnel → 几何级数 throughput →
//                   "ray direction never changes for thin-walled materials"
//   dxvk-remix / src/dxvk/shaders/rtx/utility/brdf.slangh:497-539
//        · 499 evalThinAttenuationDistance(thickness, normal, direction)
//              = thickness / dot(-normal, direction)
//        · 511 evalThinGeometricSeries(x)          = 1 / (1 - x^2)
//        · 533 evalThinTransmissionGeometricSeriesThroughput
//              = atten * (1 - Fo) * (1 - Fi) * series(atten * Fi)
//        · 520 evalThinReflectionGeometricSeriesThroughput
//              = Fo + Fi * atten^2 * (1 - Fo) * (1 - Fi) * series(atten * Fi)
//        · 226 evalTranslucentSchlickFresnel(baseReflectivity, cos)
//              = F0 + (1 - F0) * (1 - cos)^5
//   [Kulla 2017, "Revisiting Physically Based Shading at Imageworks"] 为级数来源。
//
// 与 Remix 的差异（本仓库几何约束，已注明，不是"自创模型"）：
//   1. Remix 的 thin-walled 事件是**单次**（进/出两个界面在解析式里合并），
//      本仓库的 Minecraft 几何是**双面**（方块两个平行面都会被射线命中），
//      因此调用方仍需要"每片玻璃只乘一次"的事件配对逻辑（介质状态机在 rahit 里）。
//      本文件只负责"一次事件该乘多少"。
//   2. 板厚由 glassSlabThicknessFromFace() 从命中面与格心解析给出（玻璃板 0.125，
//      整块 1.0），不引入额外射线。
//   3. 染料色沿用本仓库 computeGlassBlockTransmission() 的输出（sprite×顶点色/染料
//      去重后的每格透射色），与 Remix 的 transmittanceColor 语义相同。
//
// 能量口径自检（正入射、F0 = 0.04、tint = 1）：
//   atten = 1 ⇒ T = (1-0.04)^2 = 0.9216，R = 0.04（薄片双界面一次反射）
//   与 Remix 同公式同值；旧链路的 sheetTransmission 也是 ~0.92，故清玻璃口径不变。
// =====================================================
#ifndef GLASS_THINWALL_COMMON_GLSL
#define GLASS_THINWALL_COMMON_GLSL

// 染料色下限：避免 log(0)。与 glass_dielectric_common.glsl 的段透射下限同值。
#ifndef GLASS_THINWALL_TINT_FLOOR
#define GLASS_THINWALL_TINT_FLOOR 1.0e-4
#endif

// 相对折射率下限（防止 ior <= 1 时除零）。
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

    // 体内折射角（Snell）：sinT^2 = sinI^2 / ior^2
    float eta = 1.0 / max(ior, GLASS_THINWALL_MIN_IOR);
    float sinI2 = max(1.0 - c * c, 0.0);
    float sinT2 = sinI2 * eta * eta;

    if(sinT2 >= 1.0)
    {
        // 平板从空气入射不可能全反射；数值极端时按全反射收尾。
        transmission = vec3(0.0);
        reflection = vec3(1.0);
        return;
    }

    float cosT = sqrt(max(1.0 - sinT2, 0.0));

    // 双界面 Fresnel（外部入射 / 内部出射），与 Remix 的 Fo / Fi 同义。
    float outsideFresnel = glassThinSchlickFresnel(baseReflectivity, c);
    float insideFresnel = glassThinSchlickFresnel(baseReflectivity, cosT);

    // 体内光程（格）→ 染料衰减。斜视时光程自然变长（Remix 同式：
    // evalThinAttenuationDistance(thickness, normal, refractedDirection)）。
    float attenuationDistance =
        max(thicknessBlocks, 0.0) / max(cosT, 1.0e-4);
    vec3 attenuation = glassThinBeerAttenuation(
        transmittanceColor,
        attenuationDistance
    );

    vec3 series = glassThinGeometricSeries(attenuation * insideFresnel);

    // [Remix brdf.slangh:533]
    transmission =
        attenuation *
        (1.0 - outsideFresnel) *
        (1.0 - insideFresnel) *
        series;

    // [Remix brdf.slangh:520]
    reflection =
        vec3(outsideFresnel) +
        insideFresnel *
        attenuation * attenuation *
        (1.0 - outsideFresnel) *
        (1.0 - insideFresnel) *
        series;
}

#endif // GLASS_THINWALL_COMMON_GLSL
