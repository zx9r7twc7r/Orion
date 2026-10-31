#ifndef VOL_RESTIR_COMMON_GLSL
#define VOL_RESTIR_COMMON_GLSL

const int VOL_RESTIR_SAMPLE_COUNT = 32;

const uint VOL_RESTIR_LIGHT_INDEX_BIAS = 1u;
const uint VOL_RESTIR_LIGHT_INDEX_MASK = 0xffffu;

const uint VOL_RESTIR_AGE_MAX = 8u;

// reservoir 解包后的形态。布局（16B / RGBA32_UINT）：
//   x = lightIndex(16b) | flags(2b) << 16 | age(8b) << 18
//   y = weightSum（WRS 全和，float bits）
//   z = W = weightSum / M（float bits）
//   w = M（样本数）
struct VolReservoir
{
    uint lightIndex;
    uint flags;
    uint age;
    float weightSum;
    float weight;
    uint sampleCount;
};

uint volRestirHash(uint value)
{
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    value ^= value >> 16u;
    return value;
}

float volRestirRand(inout uint state)
{
    state = volRestirHash(state + 0x9e3779b9u);
    return float(state & 0x00ffffffu) / float(0x01000000u);
}

// 每帧每 froxel 的随机种子。z 也参与，避免同像素各层用同一串随机数。
uint volRestirSeed(ivec3 coord, uint frame)
{
    return volRestirHash(
        uint(coord.x) * 0x8da6b343u ^
        uint(coord.y) * 0xd8163841u ^
        uint(coord.z) * 0xcb1ab31fu ^
        (frame * 0x9e3779b9u));
}

VolReservoir volRestirUnpack(uvec4 raw)
{
    VolReservoir reservoir;
    reservoir.lightIndex = raw.x & VOL_RESTIR_LIGHT_INDEX_MASK;
    reservoir.flags = (raw.x >> 16u) & 0x3u;
    reservoir.age = (raw.x >> 18u) & 0xffu;
    reservoir.weightSum = uintBitsToFloat(raw.y);
    reservoir.weight = uintBitsToFloat(raw.z);
    reservoir.sampleCount = raw.w;
    return reservoir;
}

uvec4 volRestirPack(VolReservoir reservoir)
{
    const uint packedX =
        (reservoir.lightIndex & VOL_RESTIR_LIGHT_INDEX_MASK) |
        ((reservoir.flags & 0x3u) << 16u) |
        ((reservoir.age & 0xffu) << 18u);

    return uvec4(packedX,
        floatBitsToUint(reservoir.weightSum),
        floatBitsToUint(reservoir.weight),
        reservoir.sampleCount);
}

// froxel 像素坐标 → 世界射线方向（与 volumetric_light.comp:819-825 同口径）。
vec3 volRestirWorldDirection(vec2 froxelPixel, vec2 froxelExtent)
{
    vec2 ndc = (froxelPixel + 0.5) / froxelExtent * 2.0 - 1.0;
    ndc.y = -ndc.y;

    vec4 viewPosition = cam.projInverse * vec4(ndc, 1.0, 1.0);
    vec3 viewTarget = abs(viewPosition.w) > 1.0e-6
        ? viewPosition.xyz / viewPosition.w
        : viewPosition.xyz;

    return normalize((cam.viewInverse * vec4(normalize(viewTarget), 0.0)).xyz);
}

// 第 t 层（0..1）的 view-space 深度。**逐字照抄 RTX Remix**
// `rtx/utility/froxel.slangh:85-95 depthSliceToViewZ`：
//
//   viewZ = nearPlane + pow(saturate(slice / depthSlices), exponent) * maxDistance
//
// 注意 maxDistance 的语义是"超出近平面的可见距离"，不是"相机到远处的总距离"；
// 早先本文件写的是 near + (far - near) * pow(t, exp)，两者在 near≈0.05 时数值
// 接近，但语义不同，且边缘像素会随之下差分（见下）。
float volRestirSliceViewZ(float sliceT, float maxDistance, float exponent)
{
    float nearPlane = max(cam.cameraParams.x, 1.0e-3);

    return nearPlane + pow(clamp(sliceT, 0.0, 1.0), max(exponent, 1.0)) *
        max(maxDistance, 1.0);
}

// restir froxel 的中心世界位置。
// restirCoord 是本帧 restir 网格上的整数坐标（xy = 屏幕、z = 层）。
//
// 换算口径对齐 Remix 的 froxelCoordinateToTranslatedWorldPosition：切片的量是
// **view-space Z**（沿相机前向轴的深度），不是沿射线的长度。所以屏幕边缘的
// froxel 世界距离要除以 cos(视线与前向的夹角)——否则边缘一圈的 froxel 会系统性地
// 比中心近，选灯与重投影都跟着偏。
vec3 volRestirFroxelCenter(
    ivec3 restirCoord,
    ivec3 restirSize,
    vec2 froxelExtent,
    int restirDownscale,
    float maxDistance,
    float exponent)
{
    vec2 froxelPixel = (vec2(restirCoord.xy) + 0.5) * float(max(restirDownscale, 1)) - 0.5;

    vec3 worldDirection = volRestirWorldDirection(froxelPixel, froxelExtent);
    vec3 cameraPosition = cam.viewInverse[3].xyz;

    float sliceT = (float(restirCoord.z) + 0.5) / float(max(restirSize.z, 1));
    float viewZ = volRestirSliceViewZ(sliceT, maxDistance, exponent);

    vec3 cameraForward = -normalize(cam.viewInverse[2].xyz);
    float cosAngle = max(dot(worldDirection, cameraForward), 1.0e-3);

    return cameraPosition + worldDirection * (viewZ / cosAngle);
}


// 上一帧的 world→clip 矩阵。cam.prevViewInverse / prevProjInverse 是**逆矩阵**，
// 而我们要的正变换 prevProj * prevView = inverse(prevViewInverse * prevProjInverse)
// —— 与 rt_test.rgen:19782 的 MV 构造逐字同口径。
mat4 volRestirPrevViewProj()
{
    return inverse(cam.prevViewInverse * cam.prevProjInverse);
}

#endif
