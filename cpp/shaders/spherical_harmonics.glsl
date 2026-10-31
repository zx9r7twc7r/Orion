#ifndef SPHERICAL_HARMONICS_GLSL
#define SPHERICAL_HARMONICS_GLSL

#define SH_C0 (0.5 * sqrt(1.0 / 3.14159265359))
#define SH_C1 (0.5 * sqrt(3.0 / 3.14159265359))
#define SH_C2 (0.25 * sqrt(5.0 / 3.14159265359))
#define SH_C3 (0.25 * sqrt(7.0 / 3.14159265359))
// 原文 :23-27。GLSL 没有 Slang 的 interface，直接一个结构体。
struct SphericalHarmonic
{
    vec4 shY;
    vec2 cocg;
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

    Y *= (2.0 * 3.14159265359 * (1.0 - cosHalfAngle)) / 3.14159265359;

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


#endif
