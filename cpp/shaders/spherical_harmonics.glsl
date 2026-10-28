// ============================================================================
// spherical_harmonics.glsl —— RTX Remix 球谐辐照度的**逐行移植**
//
// 原文：_ref/dxvk-remix/src/dxvk/shaders/rtx/concept/light/spherical_harmonics.slangh
//        （全文 177 行，2026-10-27 逐行对照翻译）
//       颜色变换取自 utility/color.slangh:137-160（rgbToYCoCg / yCoCgToRGB 的矩阵）
//
// 为什么只有 4 个系数：Remix 的 volume radiance cache 用**亮度 L0+L1 的 2 阶 SH**
// 加一个**标量色度**（CoCg），不是 9 阶全 SH。原文注释（:22-26）：
//   "stores luminance as 2nd order SH, with average chrominance as scalar"
//   shY = (L[1,1], L[1,-1], L[1,0], L[0,0])
// 所以每 froxel 只占 6 个浮点（float4 + float2），两张 3D 纹理即可。
//
// 与原文的对应关系（函数名删去 Slang 的 struct 方法语法，公式逐字保留）：
//   rgbToYCoCg / yCoCgToRGB      → shRgbToYCoCg / shYCoCgToRgb   (color.slangh:137,148)
//   evalShBasis                  → shEvalBasis                   (:43-50)
//   addRadiance                  → shAddRadiance                 (:66-74)
//   getIrradiance                → shGetIrradiance               (:77-81)
//   resolveColorToDiffuse        → shResolveColorToDiffuse       (:84-99, Frostbite p.49)
//   scale / hanningFilter        → shScale / shHanningFilter      (:101-112)
//   HarmonicsHelpers::lerp       → shLerp                        (:118-124)
//   HarmonicsHelpers::load3D     → shLoad3D                      (:142-148)
//   HarmonicsHelpers::loadFiltered3D → shLoadFiltered3D          (:158-164)
//   HarmonicsHelpers::store3D    → shStore3D                     (:172-176)
// ============================================================================
#ifndef SPHERICAL_HARMONICS_GLSL
#define SPHERICAL_HARMONICS_GLSL

// 原文 :5-10。注释里的数值是原文自带的。
#define SH_C0 (0.5 * sqrt(1.0 / 3.14159265359))   // ~0.282095
#define SH_C1 (0.5 * sqrt(3.0 / 3.14159265359))   // ~0.488603
// 仅用于带 m=0 的 zonal 系数（原文保留，本文件暂无消费者）。
#define SH_C2 (0.25 * sqrt(5.0 / 3.14159265359))  // ~0.315392
#define SH_C3 (0.25 * sqrt(7.0 / 3.14159265359))  // ~0.373176

// 原文 :23-27。GLSL 没有 Slang 的 interface，直接一个结构体。
struct SphericalHarmonic
{
    vec4 shY;    // 亮度： (L[1,1], L[1,-1], L[1,0], L[0,0])
    vec2 cocg;   // 非方向性色度
};

// 原文 utility/color.slangh:137-146
//   matrix(0.25, 0.5, 0.25,   0.5, 0.0, -0.5,   -0.25, 0.5, -0.25) * color
vec3 shRgbToYCoCg(vec3 color)
{
    return vec3(
        0.25 * color.r + 0.5 * color.g + 0.25 * color.b,
        0.5  * color.r + 0.0 * color.g - 0.5  * color.b,
        -0.25 * color.r + 0.5 * color.g - 0.25 * color.b);
}

// 原文 utility/color.slangh:148-157
//   matrix(1, 1, -1,   1, 0, 1,   1, -1, -1) * color
//   ⇒ R = Y + Co - Cg,  G = Y + Cg,  B = Y - Co - Cg
// ⚠ 2026-10-27 自检抓到：第一版把 G 写成 Y（漏了 + Cg），三基色往返误差 0.25~0.5。
//   该矩阵与 shRgbToYCoCg 严格互逆，往返误差应为 0（见 .tmp/sh_selftest.py）。
vec3 shYCoCgToRgb(vec3 color)
{
    return vec3(
        color.x + color.y - color.z,
        color.x + color.z,
        color.x - color.y - color.z);
}

// 原文 :34-40
SphericalHarmonic shCreate()
{
    SphericalHarmonic sh;
    sh.shY = vec4(0.0);
    sh.cocg = vec2(0.0);
    return sh;
}

// 原文 :43-50
vec4 shEvalBasis(vec3 D)
{
    const float L00 = SH_C0;
    const float L1_1 = SH_C1 * D.y;
    const float L10 = SH_C1 * D.z;
    const float L11 = SH_C1 * D.x;
    return vec4(L11, L1_1, L10, L00);
}

// 原文 :66-74
void shAddRadiance(inout SphericalHarmonic sh, vec3 color, vec3 dir)
{
    const vec3 YCoCg = shRgbToYCoCg(color);
    const float Y = YCoCg.x;

    // 原文：if(any(isnan(dir))) dir = 0..xxx;
    if(any(isnan(dir)))
    {
        dir = vec3(0.0);
    }

    sh.cocg += YCoCg.yz;
    sh.shY += shEvalBasis(dir) * Y;
}

// 原文 :77-81  —— 球面平均辐照度
vec3 shGetIrradiance(SphericalHarmonic sh)
{
    const float Y = max(0.0, sh.shY.w);
    return shYCoCgToRgb(vec3(Y, sh.cocg * SH_C0));
}

// 原文 :84-99 —— 沿法线的漫反射投影（Frostbite GDC2018 p.49）
vec3 shResolveColorToDiffuse(SphericalHarmonic sh, vec3 N, float cosHalfAngle)
{
    const float d = dot(sh.shY.xyz, N);
    float Y = 0.25 * sqrt(4.0 * 3.14159265359) * sh.shY.w +
              0.5 * sqrt(4.0 * 3.14159265359 / 3.0) * d;

    // 弯折法线的修正
    Y *= (2.0 * 3.14159265359 * (1.0 - cosHalfAngle)) / 3.14159265359;

    // 亮度被改过，色度按比例跟上去
    const float modifier =
        (sh.shY.w == 0.0 || Y == 0.0) ? 0.0 : (SH_C0 * Y / sh.shY.w);
    const vec2 adjCoCg = sh.cocg * modifier;

    return shYCoCgToRgb(vec3(Y, adjCoCg));
}

// 原文 :101-105
void shScale(inout SphericalHarmonic sh, float scalar)
{
    sh.shY *= scalar;
    sh.cocg *= scalar;
}

// 原文 :109-112 —— 压 SH 振铃（PPSloan "Stupid SH Tricks"）
void shHanningFilter(inout SphericalHarmonic sh, float k)
{
    sh.shY.xyz *= (1.0 + cos(3.14159265359 / k)) * 0.5;
}

// 原文 :52-64 —— 只有体积散射相函数分支会用到（我们暂未接）。
vec3 shEvaluateHenyeyGreenstein(SphericalHarmonic sh, vec3 D, float g)
{
    const vec4 shBasis = shEvalBasis(D);
    const vec4 hgBasis = vec4(vec3(g), 1.0);
    const vec4 hgProjectedZH = hgBasis * shBasis;
    const float resultY = dot(sh.shY, hgProjectedZH);

    const float modifier =
        (sh.shY.w == 0.0 || resultY == 0.0) ? 0.0 : (SH_C0 * resultY / sh.shY.w);
    const vec2 adjCoCg = sh.cocg * modifier;

    return max(vec3(0.0), shYCoCgToRgb(vec3(resultY, adjCoCg)));
}

// 原文 HarmonicsHelpers::lerp（:118-124）
SphericalHarmonic shLerp(SphericalHarmonic x, SphericalHarmonic y, float s)
{
    SphericalHarmonic result;
    result.shY = mix(x.shY, y.shY, s);
    result.cocg = mix(x.cocg, y.cocg, s);
    return result;
}

// 原文 HarmonicsHelpers::load3D（:142-148）—— 点采样（nearest）
SphericalHarmonic shLoad3D(
    sampler3D texY,
    sampler3D texCoCg,
    ivec3 coordinate)
{
    SphericalHarmonic result;
    result.shY = texelFetch(texY, coordinate, 0);
    result.cocg = texelFetch(texCoCg, coordinate, 0).xy;
    return result;
}

// 原文 HarmonicsHelpers::loadFiltered3D（:158-164）—— 三线性采样
// GLSL 的 textureLod(…, 0.0) 即 Slang 的 SampleLevel(uvw, 0)。
SphericalHarmonic shLoadFiltered3D(
    sampler3D texY,
    sampler3D texCoCg,
    vec3 uvw)
{
    SphericalHarmonic result;
    result.shY = textureLod(texY, uvw, 0.0);
    result.cocg = textureLod(texCoCg, uvw, 0.0).xy;
    return result;
}

// 原文 HarmonicsHelpers::store3D（:172-176）
//
// ⚠ GLSL 与 Slang 的一处语法差异：image 参数不带格式限定符时，glslang 要求
//   **同一个函数内所有 image 参数格式一致**（原文的 RWTexture3D<float4>/<float2>
//   是两种格式，Slang 允许）。为不强迫两张 3D 纹理同格式，写端不进本头文件，
//   由调用方直接写：
//       imageStore(volumeShY,  coordinate, sh.shY);
//       imageStore(volumeCoCg, coordinate, vec4(sh.cocg, 0.0, 0.0));
//   读端（shLoad3D / shLoadFiltered3D）用 sampler3D，没有这个限制，保留在头里。

#endif // SPHERICAL_HARMONICS_GLSL
