#ifndef CLOUD_BEVY_CORE
#define CLOUD_BEVY_CORE

const float BEVY_UI0 = 1597334673.0;
const float BEVY_UI1 = 3812015801.0;
const float BEVY_UIF = 1.0 / 4294967295.0;
const float BEVY_MAX_DISTANCE = 1.0e9;

struct BevyCloudConfig
{
    float baseScale;
    int raymarchSteps;
    float bottomHeight;
    float topHeight;
    float coverage;
    float density;
    float detailScale;
    float detailStrength;
    float baseEdgeSoftness;
    float bottomSoftness;
    int shadowSteps;
    float shadowStepSize;
    float shadowStepMultiply;
    vec3 ambientTop;
    vec3 ambientBottom;
    float minTransmittance;
    float shapeAmount;
    float shapeScale;
    float shapeWorldScale;
    float planetRadius;
    float forwardG;
    float backwardG;
    float scatteringLerp;
    vec3 sunDir;
    vec3 sunColor;
    float time;
    float cloudType;
    float erosionScale;
    float weatherPeriod;
    float viewDistance;
    float fadeStart;
    float silver;
    float cohesion;
    float sunGlow;
};

// ---------------------------------------------------------------- 数学工具
float bevyLinearstep(float s, float e, float v)
{
    return clamp((v - s) * (1.0 / (e - s)), 0.0, 1.0);
}

float bevyLinearstep0(float e, float v)
{
    return min(v * (1.0 / e), 1.0);
}

float bevyRemap(float v, float s, float e)
{
    float d = e - s;
    if(abs(d) < 1.0e-6)
    {
        return v > s ? 1.0 : 0.0;
    }
    return (v - s) / d;
}

// Hash without Sine (Dave Hoskins, shadertoy 4djSRW)
float bevyHash13(vec3 p3in)
{
    vec3 p3 = fract(p3in * 1031.1031);
    p3 += dot(p3, p3.yzx + 19.19);
    return fract((p3.x + p3.y) * p3.z);
}

float bevyValueHash(vec3 p3in)
{
    vec3 p3 = fract(p3in * 0.1031);
    p3 += dot(p3, p3.yzx + 19.19);
    return fract((p3.x + p3.y) * p3.z);
}

// Tileable value noise（Frostbite TileableVolumeNoise 的 hash 版）
float bevyHashBasedNoise(vec3 x, float tile)
{
    vec3 p = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(bevyValueHash(mod(p, tile)),
                       bevyValueHash(mod(p + vec3(1.0, 0.0, 0.0), tile)), f.x),
                   mix(bevyValueHash(mod(p + vec3(0.0, 1.0, 0.0), tile)),
                       bevyValueHash(mod(p + vec3(1.0, 1.0, 0.0), tile)), f.x), f.y),
               mix(mix(bevyValueHash(mod(p + vec3(0.0, 0.0, 1.0), tile)),
                       bevyValueHash(mod(p + vec3(1.0, 0.0, 1.0), tile)), f.x),
                   mix(bevyValueHash(mod(p + vec3(0.0, 1.0, 1.0), tile)),
                       bevyValueHash(mod(p + vec3(1.0, 1.0, 1.0), tile)), f.x), f.y), f.z);
}

vec3 bevyHash33(vec3 p)
{
    uvec3 q = uvec3(ivec3(p)) * uvec3(uint(BEVY_UI0), uint(BEVY_UI1), 2798796415u);
    q = (q.x ^ q.y ^ q.z) * uvec3(uint(BEVY_UI0), uint(BEVY_UI1), 2798796415u);
    return -1.0 + 2.0 * vec3(q) * BEVY_UIF;
}

float bevyGradientNoise(vec3 x, float freq)
{
    vec3 p = floor(x);
    vec3 w = fract(x);
    vec3 u = w * w * w * (w * (w * 6.0 - 15.0) + 10.0);

    vec3 ga = bevyHash33(mod(p + vec3(0.0, 0.0, 0.0), freq));
    vec3 gb = bevyHash33(mod(p + vec3(1.0, 0.0, 0.0), freq));
    vec3 gc = bevyHash33(mod(p + vec3(0.0, 1.0, 0.0), freq));
    vec3 gd = bevyHash33(mod(p + vec3(1.0, 1.0, 0.0), freq));
    vec3 ge = bevyHash33(mod(p + vec3(0.0, 0.0, 1.0), freq));
    vec3 gf = bevyHash33(mod(p + vec3(1.0, 0.0, 1.0), freq));
    vec3 gg = bevyHash33(mod(p + vec3(0.0, 1.0, 1.0), freq));
    vec3 gh = bevyHash33(mod(p + vec3(1.0, 1.0, 1.0), freq));

    float va = dot(ga, w - vec3(0.0, 0.0, 0.0));
    float vb = dot(gb, w - vec3(1.0, 0.0, 0.0));
    float vc = dot(gc, w - vec3(0.0, 1.0, 0.0));
    float vd = dot(gd, w - vec3(1.0, 1.0, 0.0));
    float ve = dot(ge, w - vec3(0.0, 0.0, 1.0));
    float vf = dot(gf, w - vec3(1.0, 0.0, 1.0));
    float vg = dot(gg, w - vec3(0.0, 1.0, 1.0));
    float vh = dot(gh, w - vec3(1.0, 1.0, 1.0));

    return va +
        u.x * (vb - va) + u.y * (vc - va) + u.z * (ve - va) +
        u.x * u.y * (va - vb - vc + vd) +
        u.y * u.z * (va - vc - ve + vg) +
        u.z * u.x * (va - vb - ve + vf) +
        u.x * u.y * u.z * (-va + vb + vc - vd + ve - vf - vg + vh);
}

float bevyVoronoi(vec3 x, float tile)
{
    vec3 p = floor(x);
    vec3 f = fract(x);
    float res = 100.0;
    for(float k = -1.0; k < 1.1; k += 1.0)
    for(float j = -1.0; j < 1.1; j += 1.0)
    for(float i = -1.0; i < 1.1; i += 1.0)
    {
        vec3 b = vec3(i, j, k);
        vec3 c = mod(p + b, vec3(tile));
        vec3 r = b - f + bevyHash13(c);
        res = min(res, dot(r, r));
    }
    return 1.0 - res;
}

float bevyTilableVoronoi(vec3 p, int octaves, float baseFreq)
{
    float freq = baseFreq;
    float amplitude = 1.0;
    float noise = 0.0;
    float w = 0.0;
    for(int i = 0; i < 8; ++i)
    {
        if(i >= octaves) break;
        noise += amplitude * bevyVoronoi(p * freq, freq);
        freq *= 2.0;
        w += amplitude;
        amplitude *= 0.5;
    }
    return noise / max(w, 1.0e-6);
}

float bevyTilablePerlinFbm(vec3 p, int octaves, float baseFreq)
{
    float freq = baseFreq;
    float amplitude = 1.0;
    float noise = 0.0;
    float w = 0.0;
    for(int i = 0; i < 8; ++i)
    {
        if(i >= octaves) break;
        noise += amplitude * bevyHashBasedNoise(p * freq, freq);
        freq *= 2.0;
        w += amplitude;
        amplitude *= 0.5;
    }
    return noise / max(w, 1.0e-6);
}

// ---------------------------------------------------------------- 噪声烘焙
// clouds_compute.wgsl:256-273 —— 2D 图集：R=底色 G=阈值 B=高度因子
// 参考把 pixel 坐标 / 分辨率后使用；这里直接收归一化 uv（0..1）
vec4 bevyRenderAtlas(vec2 uv)
{
    vec3 coord = vec3(uv, 0.5);
    const float mfbm = 0.9;
    const float mvor = 0.7;
    return vec4(
        mix(1.0, bevyTilablePerlinFbm(coord, 7, 4.0), mfbm) *
            mix(1.0, bevyTilableVoronoi(coord, 8, 9.0), mvor),
        0.625 * bevyTilableVoronoi(coord, 3, 15.0) +
            0.250 * bevyTilableVoronoi(coord, 3, 19.0) +
            0.125 * bevyTilableVoronoi(coord, 3, 23.0) - 1.0,
        1.0 - bevyTilableVoronoi(coord + 0.5, 6, 9.0),
        1.0);
}

// clouds_compute.wgsl:275-283 —— 32³ worley（三级叠加）
vec4 bevyRenderWorley(vec3 coord)
{
    float r = bevyTilableVoronoi(coord, 16, 3.0);
    float g = bevyTilableVoronoi(coord, 4, 8.0);
    float b = bevyTilableVoronoi(coord, 4, 16.0);
    return vec4(max(0.0, 1.0 - (r + g * 0.5 + b * 0.25) / 1.75));
}

bool gBevyPuffMode = false;
bool gBevyUse3D = false;
float bevyPerlin3D(vec3 p, float period);
float bevyWorley3D(vec3 p, float freq);
const int kPuffCount = 12;
const vec3 kPuffCenter[12] = vec3[12](
    vec3(   300.0, 2500.0,  4300.0),
    vec3( -3600.0, 2600.0,  5200.0),
    vec3(  3800.0, 2450.0,  6000.0),
    vec3(   800.0, 2800.0,  8200.0),
    vec3( -7000.0, 1980.0,  9500.0),
    vec3( -4200.0, 1960.0, 12000.0),
    vec3( -1200.0, 2000.0,  8800.0),
    vec3(  2200.0, 1970.0, 10500.0),
    vec3(  5200.0, 1990.0,  8800.0),
    vec3(  8000.0, 1960.0, 12500.0),
    vec3( -9500.0, 2000.0, 14000.0),
    vec3( 10000.0, 2010.0, 16000.0));
const float kPuffRadius[12] = float[12](
    1600.0, 1500.0, 1450.0, 1550.0,
    340.0, 320.0, 300.0, 330.0, 310.0, 350.0, 300.0, 340.0);
const float kPuffSquash[12] = float[12](
    1.10, 1.10, 1.10, 1.10,
    1.35, 1.35, 1.35, 1.35, 1.35, 1.35, 1.35, 1.35);

const vec3 kPuffLobe[10] = vec3[10](
    vec3( 0.00,  0.10,  0.00),
    vec3( 0.50,  0.06,  0.26),
    vec3(-0.46,  0.04,  0.34),
    vec3( 0.06,  0.36, -0.48),
    vec3(-0.32,  0.30, -0.26),
    vec3( 0.34, -0.24, -0.28),
    vec3(-0.14, -0.30,  0.44),
    vec3( 0.44,  0.34, -0.12),
    vec3(-0.52, -0.16, -0.10),
    vec3( 0.12, -0.06, -0.56));
const float kPuffLobeRadius[10] = float[10](
    0.44, 0.34, 0.35, 0.30, 0.29, 0.31, 0.30, 0.26, 0.28, 0.27);

float bevyPuffCover(vec3 localPos)
{
    float best = 0.0;
    for(int i = 0; i < kPuffCount; ++i)
    {
        vec3 q = (localPos - kPuffCenter[i]) / kPuffRadius[i];
        float env = length(vec3(q.x, q.y * kPuffSquash[i], q.z));
        env += (bevyPerlin3D(q * 1.5 + vec3(float(i) * 13.7), 64.0) - 0.5) * 0.55;

        float ca = cos(float(i) * 1.7);
        float sa = sin(float(i) * 1.7);
        float lobes = 0.0;
        int lobeCount = i < 4 ? 10 : 3;
        for(int j = 0; j < 10; ++j)
        {
            if(j >= lobeCount) break;
            vec3 sp = kPuffLobe[j];
            vec2 rot = mat2(ca, -sa, sa, ca) * sp.xz;
            sp = vec3(rot.x, sp.y, rot.y);
            float d = length((q - sp) / kPuffLobeRadius[j]);
            lobes = max(lobes, 1.0 - smoothstep(0.70, 1.0, d));
        }
        float cover = (1.0 - smoothstep(0.55, 1.15, env)) * lobes;
        best = max(best, cover);
    }
    return best;
}

bool gBevyVolumeMode = false;
vec3 gBevyVolumeOrigin = vec3(0.0);
float gBevyVolumeExtent = 10000.0;
float gBevyLayerHeight = 1296.0;
float gBevyVolumeYRange = 0.30;
const float kPuffCell = 2800.0;

vec4 bevyRenderPuffVolume(vec3 coord)
{
    vec2 wp = gBevyVolumeOrigin.xz + (coord.xz - 0.5) * gBevyVolumeExtent;
    float wy = (coord.y - 0.5) * gBevyVolumeYRange * gBevyLayerHeight;

    vec2 baseCell = floor(wp / kPuffCell);
    float best = 0.0;
    for(int dz = -1; dz <= 1; ++dz)
    for(int dx = -1; dx <= 1; ++dx)
    {
        vec2 c = baseCell + vec2(float(dx), float(dz));
        float h0 = bevyValueHash(vec3(c, 1.0));
        if(h0 > 0.80) continue;
        vec2 jitter = vec2(bevyValueHash(vec3(c, 2.0)), bevyValueHash(vec3(c, 3.0)));
        vec2 center = (c + 0.5 + (jitter - 0.5) * 0.5) * kPuffCell;
        float radius = mix(700.0, 1300.0, bevyValueHash(vec3(c, 4.0)));
        float heightOffset = (bevyValueHash(vec3(c, 5.0)) - 0.5) * gBevyLayerHeight * 0.35;

        vec2 d2 = (wp - center) / radius;
        vec3 q = vec3(d2.x, (wy - heightOffset) / (radius * 0.8), d2.y);
        float env = length(q);
        if(env > 1.35) continue;
        float ca = cos(h0 * 6.2831853);
        float sa = sin(h0 * 6.2831853);
        float lobes = 0.0;
        for(int j = 0; j < 10; ++j)
        {
            vec3 sp = kPuffLobe[j];
            vec2 rot = mat2(ca, -sa, sa, ca) * sp.xz;
            sp = vec3(rot.x, sp.y, rot.y);
            lobes = max(lobes,
                1.0 - smoothstep(0.70, 1.0, length((q - sp) / kPuffLobeRadius[j])));
        }
        best = max(best, (1.0 - smoothstep(0.62, 1.15, env)) * lobes);
    }
    return vec4(best, 0.5, 0.5, 1.0);
}

float bevyCloudMapBase(vec3 p, float normalizedHeight, BevyCloudConfig cfg,
    sampler2D atlasTex, vec2 atlasSize, sampler3D baseTex)
{
    if(gBevyVolumeMode)
    {
        vec3 localPosV = p - vec3(0.0, cfg.planetRadius, 0.0);
        vec2 uvwXZ = (localPosV.xz - gBevyVolumeOrigin.xz) / gBevyVolumeExtent + 0.5;
        float yFrac = clamp(0.5 + (normalizedHeight - 0.5) * gBevyVolumeYRange, 0.02, 0.98);
        float edge = max(abs(uvwXZ.x - 0.5), abs(uvwXZ.y - 0.5));
        float fade = 1.0 - smoothstep(0.40, 0.50, edge);
        float coverV = texture(baseTex, vec3(uvwXZ.x, yFrac, uvwXZ.y)).r * fade;
        return 0.30 + 0.40 * coverV;
    }
    if(gBevyPuffMode)
    {
        vec3 localPos = p - vec3(0.0, cfg.planetRadius, 0.0);
        return 0.30 + 0.40 * bevyPuffCover(localPos);
    }
    vec3 localPos = p - vec3(0.0, cfg.planetRadius, 0.0);
    vec2 xz = localPos.xz * (0.00035 * cfg.baseScale);
    vec3 cloud = texture(atlasTex, xz).rgb;
    float n = normalizedHeight * normalizedHeight * cloud.b +
        pow(1.0 - normalizedHeight, 16.0);
    return bevyRemap(cloud.r - n, cloud.g, 1.0);
}

// clouds_compute.wgsl:72-84
// 2026-10-18：参考是 textureLoad 逐 texel 取 + 手工 y 向插值（源码里挂着
// "TODO: add bilinear filtering"）。这里改走硬件三线性（采样器本来就 repeat+linear）：
// 无插值时，场是常值的块，步长（~13 格）与 texel（~250 格）不同步，掠射方向就会
// 出现一串"珠粒"/方块边。三线性后场是 C0 连续的，采样再稀也只是变光滑，不出块。
float bevyCloudMapDetail(vec3 position, BevyCloudConfig cfg, sampler3D worleyTex)
{
    vec3 localPos = position - vec3(0.0, cfg.planetRadius, 0.0);
    vec3 p = localPos * (0.0016 * cfg.baseScale * cfg.detailScale);
    return texture(worleyTex, p * (1.0 / 32.0)).r;
}
// clouds_compute.wgsl:86-92
float bevyCloudGradient(float h)
{
    return bevyLinearstep(0.0, 0.1, h) - bevyLinearstep(0.8, 1.2, h);
}

// clouds_compute.wgsl:94-110
//
// ===========================================================================
// 2026-10-18（第十三轮）：**照抄 Schneider 2015 / 密度模型**。
// method from Schneider" / SIGGRAPH 2015），逐行对应如下：
//
//   vec4 n        = textureLod(perlworlnoise, p*0.00008, mip);
//   float fbm     = n.g*0.625 + n.b*0.25 + n.a*0.125;
//   float g       = densityHeightGradient(h, weather.r);
//   float base    = remap(n.r, -(1.0-fbm), 1.0, 0.0, 1.0);
//   float cov     = cloud_coverage * weather.b;
//   base          = remap(base*g, 1.0-cov, 1.0, 0.0, 1.0);
//   base         *= cov;
//   float hfbm    = worl_noise.rgb 按 0.625/0.25/0.125 合成;
//   hfbm          = mix(hfbm, 1.0-hfbm, clamp(h*4.0, 0.0, 1.0));
//   base          = remap(base, hfbm*0.4*h, 1.0, 0.0, 1.0);
//   return pow(clamp(base,0,1), (1.0-h)*0.8 + 0.5);
//
// 关键差别（也是前面十几轮白忙的原因）：形状/侵蚀是**remap**出来的，
// 不是乘、也不是减。乘法会把细节压平，减法会把边缘啃成毛边；remap 保留值域。
//
// 资产映射：
//   perlworlnoise → shapeVolume 64³（r=Perlin-Worley，g/b/a=worley 4/8/16 三档）
//   worlnoise     → worleyVolume 64³
//   weather map   → 我们的 2D 图集（R 通道当覆盖率场，保留"离散云团"的水平构图）
// ===========================================================================
float bevyDensityHeightGradient(float h, float cloudType)
{
    const vec4 STRATUS_GRADIENT = vec4(0.02, 0.05, 0.09, 0.11);
    const vec4 STRATOCUMULUS_GRADIENT = vec4(0.02, 0.2, 0.48, 0.625);
    const vec4 CUMULUS_GRADIENT = vec4(0.01, 0.0625, 0.78, 1.0);
    float stratus = 1.0 - clamp(cloudType * 2.0, 0.0, 1.0);
    float stratocumulus = 1.0 - abs(cloudType - 0.5) * 2.0;
    float cumulus = clamp(cloudType - 0.5, 0.0, 1.0) * 2.0;
    vec4 grad = STRATUS_GRADIENT * stratus +
        STRATOCUMULUS_GRADIENT * stratocumulus +
        CUMULUS_GRADIENT * cumulus;
    return smoothstep(grad.x, grad.y, h) - smoothstep(grad.z, grad.w, h);
}

// ===========================================================================
// 2026-10-18 **重构**：2D 云图 + 线性高度窗（iq "Clouds" / SEUS 路线）
//
// raymarching clouds.glsl）的密度函数只有三行：
//
//     float f = fbm(q, 3);
//     return clamp(2.0 * f - p.y - 1.0, 0.0, 1.0);
//
// 要点：
//   · 噪声是 **2D**（xz 平面），高度只以**线性方式**混进来 —— 没有 3D 体积、
//     没有阈值切分、没有 remap 侵蚀。这就是 Minecraft 光影云的全部秘密；
//   · 逐像素的成本只有几次 value-noise hash，比 3D 纹理便宜，也不受体积分辨率限制；
//   · 地平线附近的"云毯"是**透视自然产生**的：视线接近平行于云层，光程变长。
// 之前用 3D Perlin-Worley 体积失败的根本原因是 128³ 覆盖 6000 格 = 47 格/体素，
// 云缘（100~200 格）只有 2~4 个体素，形状必然是块状。
// ===========================================================================
float bevyValueNoise2D(vec2 p)
{
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = bevyValueHash(vec3(i, 0.0));
    float b = bevyValueHash(vec3(i + vec2(1.0, 0.0), 0.0));
    float c = bevyValueHash(vec3(i + vec2(0.0, 1.0), 0.0));
    float d = bevyValueHash(vec3(i + vec2(1.0, 1.0), 0.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// 2026-10-18：2D Worley（细胞）噪声 —— 目标图那种"泡状团块"的来源。
// value noise 给的是板状/棉絮，Worley 的 F1 才会产生一粒粒圆泡。
float bevyVoronoi2D(vec2 p)
{
    vec2 cell = floor(p);
    vec2 f = fract(p);
    float best = 8.0;
    for(int y = -1; y <= 1; ++y)
    for(int x = -1; x <= 1; ++x)
    {
        vec2 offset = vec2(float(x), float(y));
        vec2 jitter = vec2(
            bevyValueHash(vec3(cell + offset, 0.137)),
            bevyValueHash(vec3(cell + offset, 0.731)));
        vec2 d = offset + jitter - f;
        best = min(best, dot(d, d));
    }
    return 1.0 - best;
}
vec3 bevyMod289v3(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 bevyMod289v4(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 bevyPermute4(vec4 x) { return bevyMod289v4(((x * 34.0) + 1.0) * x); }
vec4 bevyTaylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
vec3 bevyFade5(vec3 t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }

float bevyCnoise(vec3 P)
{
    vec3 Pi0 = floor(P);
    vec3 Pi1 = Pi0 + vec3(1.0);
    Pi0 = bevyMod289v3(Pi0);
    Pi1 = bevyMod289v3(Pi1);
    vec3 Pf0 = fract(P);
    vec3 Pf1 = Pf0 - vec3(1.0);
    vec4 ix = vec4(Pi0.x, Pi1.x, Pi0.x, Pi1.x);
    vec4 iy = vec4(Pi0.yy, Pi1.yy);
    vec4 iz0 = Pi0.zzzz;
    vec4 iz1 = Pi1.zzzz;

    vec4 ixy = bevyPermute4(bevyPermute4(ix) + iy);
    vec4 ixy0 = bevyPermute4(ixy + iz0);
    vec4 ixy1 = bevyPermute4(ixy + iz1);

    vec4 gx0 = ixy0 * (1.0 / 7.0);
    vec4 gy0 = fract(floor(gx0) * (1.0 / 7.0)) - 0.5;
    gx0 = fract(gx0);
    vec4 gz0 = vec4(0.5) - abs(gx0) - abs(gy0);
    vec4 sz0 = step(gz0, vec4(0.0));
    gx0 -= sz0 * (step(0.0, gx0) - 0.5);
    gy0 -= sz0 * (step(0.0, gy0) - 0.5);

    vec4 gx1 = ixy1 * (1.0 / 7.0);
    vec4 gy1 = fract(floor(gx1) * (1.0 / 7.0)) - 0.5;
    gx1 = fract(gx1);
    vec4 gz1 = vec4(0.5) - abs(gx1) - abs(gy1);
    vec4 sz1 = step(gz1, vec4(0.0));
    gx1 -= sz1 * (step(0.0, gx1) - 0.5);
    gy1 -= sz1 * (step(0.0, gy1) - 0.5);

    vec3 g000 = vec3(gx0.x, gy0.x, gz0.x);
    vec3 g100 = vec3(gx0.y, gy0.y, gz0.y);
    vec3 g010 = vec3(gx0.z, gy0.z, gz0.z);
    vec3 g110 = vec3(gx0.w, gy0.w, gz0.w);
    vec3 g001 = vec3(gx1.x, gy1.x, gz1.x);
    vec3 g101 = vec3(gx1.y, gy1.y, gz1.y);
    vec3 g011 = vec3(gx1.z, gy1.z, gz1.z);
    vec3 g111 = vec3(gx1.w, gy1.w, gz1.w);

    vec4 norm0 = bevyTaylorInvSqrt(vec4(dot(g000, g000), dot(g010, g010),
        dot(g100, g100), dot(g110, g110)));
    g000 *= norm0.x; g010 *= norm0.y; g100 *= norm0.z; g110 *= norm0.w;
    vec4 norm1 = bevyTaylorInvSqrt(vec4(dot(g001, g001), dot(g011, g011),
        dot(g101, g101), dot(g111, g111)));
    g001 *= norm1.x; g011 *= norm1.y; g101 *= norm1.z; g111 *= norm1.w;

    float n000 = dot(g000, Pf0);
    float n100 = dot(g100, vec3(Pf1.x, Pf0.yz));
    float n010 = dot(g010, vec3(Pf0.x, Pf1.y, Pf0.z));
    float n110 = dot(g110, vec3(Pf1.xy, Pf0.z));
    float n001 = dot(g001, vec3(Pf0.xy, Pf1.z));
    float n101 = dot(g101, vec3(Pf1.x, Pf0.y, Pf1.z));
    float n011 = dot(g011, vec3(Pf0.x, Pf1.yz));
    float n111 = dot(g111, Pf1);

    vec3 fxyz = bevyFade5(Pf0);
    vec4 nz = mix(vec4(n000, n100, n010, n110), vec4(n001, n101, n011, n111), fxyz.z);
    vec2 nyz = mix(nz.xy, nz.zw, fxyz.y);
    return 2.2 * mix(nyz.x, nyz.y, fxyz.x);
}

// 2026-10-19：3D value noise（8 次 hash）。
// 用 2 个 octave（16 次 hash）替代 4 octave 2D（同样 16 次），
// 但 y 方向有**真实变化** —— 这是同时消掉"竖柱"和"同心环"的唯一正确做法。
float bevyValueNoise3D(vec3 p)
{
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float v000 = bevyValueHash(i);
    float v100 = bevyValueHash(i + vec3(1.0, 0.0, 0.0));
    float v010 = bevyValueHash(i + vec3(0.0, 1.0, 0.0));
    float v110 = bevyValueHash(i + vec3(1.0, 1.0, 0.0));
    float v001 = bevyValueHash(i + vec3(0.0, 0.0, 1.0));
    float v101 = bevyValueHash(i + vec3(1.0, 0.0, 1.0));
    float v011 = bevyValueHash(i + vec3(0.0, 1.0, 1.0));
    float v111 = bevyValueHash(i + vec3(1.0, 1.0, 1.0));
    return mix(mix(mix(v000, v100, f.x), mix(v010, v110, f.x), f.y),
               mix(mix(v001, v101, f.x), mix(v011, v111, f.x), f.y), f.z);
}

float bevyCloudFbm(vec2 p)
{
    float sum = 0.0;
    float weight = 0.5;
    for(int octave = 0; octave < 4; ++octave)
    {
        sum += weight * bevyValueNoise2D(p);
        weight *= 0.5;
        p *= 2.03;
    }
    return sum;
}
float bevyGetDensity(vec3 pos, float normalizedHeight, BevyCloudConfig cfg,
    sampler2D atlasTex, vec2 atlasSize, sampler3D baseTex, sampler3D worleyTex,
    sampler3D shapeSampler, bool cheapVersion)
{
    {
        float hn = clamp(normalizedHeight, 0.0, 1.0);
        if(normalizedHeight < 0.0 || normalizedHeight > 1.0) return 0.0;
        vec3 wp = pos;
        float ct = clamp(cfg.cloudType, 0.0, 1.0);
        float pStratus = smoothstep(0.00, 0.15, hn) * (1.0 - smoothstep(0.80, 1.00, hn));
        float pCumulus = smoothstep(0.08, 0.32, hn) * (1.0 - smoothstep(0.60, 0.95, hn));
        float profile = mix(pStratus, pCumulus, ct);
        if(profile <= 0.001) return 0.0;

        float shapeScale = max(cfg.baseScale, 0.05) * 1400.0;
        const float warpAmt = 0.40;
        float warpN = bevyValueNoise2D(wp.xz / (shapeScale * 2.0) + vec2(hn * 1.7, 0.0));
        vec2 warpOff = vec2(warpN, warpN * 1.37) * shapeScale * warpAmt;
        float vertStretch = 1.0 + clamp(cfg.detailStrength, 0.0, 1.0) * 4.0;
        vec3 np = vec3((wp.xz + warpOff) / shapeScale, hn * vertStretch);
        float perlin = 0.55 * bevyCnoise(np)
                     + 0.30 * bevyCnoise(np * 2.3)
                     + 0.15 * bevyCnoise(np * 5.1);
        perlin = clamp(perlin * 0.5 + 0.5, 0.0, 1.0);

        float weather = bevyValueNoise2D(wp.xz / (shapeScale * 3.5));

        float cov = clamp(cfg.coverage, 0.0, 1.0);
        float lo = 1.0 - cov;
        float coverage = smoothstep(lo, lo + 0.18, weather);

        float coh = clamp(cfg.cohesion, 0.0, 1.0);
        float thLo = mix(0.45, 0.26, coh);
        float thHi = mix(0.72, 0.82, coh);
        float base = smoothstep(thLo, thHi, perlin) * coverage;
        if(base <= 0.02) return 0.0;

        if(cheapVersion)
        {
            return clamp(base * profile, 0.0, 1.0);
        }

        float detailScale = max(cfg.detailScale, 0.1) * 55.0;
        vec3 dp = vec3(wp.xz / detailScale, hn * 8.0);
        float detail = bevyCnoise(dp) * 0.5 + 0.5;
        float erosion = clamp(cfg.erosionScale, 0.0, 2.0) * 0.42 * mix(1.0, 0.30, coh);
        float edgeMask = 1.0 - smoothstep(0.30, 0.80, base);
        float erosionMask = mix(0.15, edgeMask, 0.85);
        float dens = base - (1.0 - detail) * erosion * erosionMask;

        dens *= profile;
        return clamp(dens, 0.0, 1.0);
    }

    if(!gBevyUse3D)
    {
        {
            float hn = clamp(normalizedHeight, 0.0, 1.0);
            if(normalizedHeight < 0.0 || normalizedHeight > 1.0) return 0.0;
            vec2 worldXZ = pos.xz;
            float scale = max(cfg.baseScale, 0.05) * 1200.0;
            vec3 np = vec3(worldXZ / scale, hn * 3.6);
            float perlin = 0.62 * bevyCnoise(np)
                         + 0.38 * bevyCnoise(np * 2.3);
            float f = clamp(perlin * 0.5 + 0.5, 0.0, 1.0);
            float ct = clamp(cfg.cloudType, 0.0, 1.0);
            float hShift = clamp(cfg.bottomSoftness * 3.0, 0.0, 1.0) * 0.10;
            float hLo = mix(0.02, 0.18, ct) + hShift;
            float hHi = min(mix(0.98, 0.72, ct) + hShift, 0.99);
            float hnWarp = hn + (f - 0.5) * 0.22;
            float hWindow = smoothstep(hLo, hLo + 0.30, hnWarp) *
                            (1.0 - smoothstep(hHi - 0.30, hHi, hnWarp));
            float maskNoise = bevyValueNoise2D(worldXZ / (scale * 3.5) + vec2(29.1, 7.3));
            float maskThreshold = 0.72 - clamp(cfg.coverage, 0.0, 1.0) * 0.5;
            float region = smoothstep(maskThreshold, maskThreshold + 0.18, maskNoise);
            const float cloudIso = 0.76;
            float d = 2.0 * f - (1.0 - hWindow) * 1.15 - cloudIso;
            d *= region;
            d -= (1.0 - region) * 2.0;
            float amp = 0.45 * clamp(cfg.erosionScale, 0.0, 2.0);
            float dScale = 0.15 * (10.0 / clamp(cfg.detailScale, 0.1, 12.0));
            float warpSeed = bevyValueNoise2D(worldXZ / (scale * 1.8) + vec2(5.3, 11.7));
            vec2 warpOff = vec2(warpSeed - 0.5, warpSeed * 1.73 - 0.865) * 0.55;
            float dA = bevyValueNoise2D(worldXZ / (scale * dScale) + warpOff);
            float dB = bevyValueNoise2D(worldXZ / (scale * 0.055) + vec2(17.3, 9.1) + warpOff * 1.8);
            float wB = clamp(cfg.detailStrength * 2.5, 0.05, 0.95);
            float erosionMask = 1.0 - smoothstep(0.10, 0.55, d);
            d += (dA - 0.5) * amp * erosionMask;
            d += (dB - 0.5) * amp * wB * 0.62 * erosionMask;
            return clamp(d, 0.0, 1.0) * cfg.density;
        }

#if 0
        float h = clamp(normalizedHeight, 0.0, 1.0);
        if(normalizedHeight < 0.0 || normalizedHeight > 1.0) return 0.0;
        vec3 localPos = pos - vec3(0.0, cfg.planetRadius, 0.0);
        float thickness = max(cfg.topHeight - cfg.bottomHeight, 1.0);
        float horizScale = max(cfg.baseScale, 0.05) * thickness;
        vec2 p2 = localPos.xz / horizScale;
        float w2 = clamp(cfg.detailStrength * 2.5, 0.05, 0.95);
        float highFreq = clamp(cfg.shapeScale * 13.0, 1.3, 4.0);
        float baseNoise = bevyValueNoise2D(p2) * (1.0 - w2) +
                          bevyValueNoise2D(p2 * highFreq) * w2;
        float fadeT = clamp(cfg.bottomSoftness * 3.0, 0.0, 1.0);
        float fade = mix(1.0 - h, 1.0 - h * h, fadeT);
        float coverage = clamp(cfg.coverage, 0.0, 1.0) * fade;
        float threshold = clamp(cfg.baseEdgeSoftness, 0.35, 0.95);
        float cloudBase = clamp(baseNoise - (threshold - coverage), 0.0, 1.0);
        if(cloudBase <= 0.01) return 0.0;
        float detailPeriod = 4500.0 / clamp(cfg.detailScale, 0.1, 12.0);
        float detail = texture(shapeSampler, localPos / detailPeriod).r;
        detail = max(detail - 0.25, 0.0) * 2.0;
        float cloudDensity = clamp(cloudBase -
            detail * 0.55 * clamp(cfg.erosionScale, 0.0, 2.0), 0.0, 1.0);
        float s = max(sin(h * 3.14159265), 0.0);
        float s2 = s * s;
        float ct = clamp(cfg.cloudType, 0.0, 1.0);
        float profile = mix(s, s2, clamp(ct * 2.0, 0.0, 1.0));
        profile = mix(profile, s2 * s2, clamp(ct * 2.0 - 1.0, 0.0, 1.0));
        cloudDensity *= profile;
        return clamp(cloudDensity * cfg.density * 5.0, 0.0, 1.0);
#endif
    }

#if 1
    vec3 localPos = pos - vec3(0.0, cfg.planetRadius, 0.0);
    float h = clamp(normalizedHeight, 0.0, 1.0);
    float basePeriod = 3200.0 * 0.9 / max(cfg.baseScale, 0.05);
    vec4 n = texture(shapeSampler,
        vec3(localPos.x, localPos.y * 2.2, localPos.z) / basePeriod);
    float fbm = n.g * 0.625 + n.b * 0.25 + n.a * 0.125;
    float base = bevyRemap(n.r, -(1.0 - fbm), 1.0);

    float weather3D = texture(shapeSampler, localPos / max(cfg.weatherPeriod, 1000.0)).r;
    weather3D = smoothstep(0.15, 0.75, weather3D);
    vec2 auv = localPos.xz * (0.00005 * cfg.baseScale);
    float weather2D = clamp(texture(atlasTex, auv).r, 0.0, 1.0);
    float weather = mix(weather3D, weather2D, 0.10);
    float grad = bevyDensityHeightGradient(h, cfg.cloudType);
    weather = smoothstep(0.20, 0.90, weather);
    float coverage = clamp(cfg.coverage * weather, 0.0, 1.0);
    if(coverage < 1.0e-4)
    {
        return 0.0;
    }
    base = bevyRemap(base * grad, 1.0 - coverage, 1.0) * coverage;

    float hfbm = bevyCloudMapDetail(pos, cfg, worleyTex);
    hfbm = mix(hfbm, 1.0 - hfbm, clamp(h * 4.0, 0.0, 1.0));
    hfbm = smoothstep(0.15, 0.60, hfbm);
    base = bevyRemap(base, hfbm * 1.7 * cfg.erosionScale * h, 1.0);
    base *= mix(1.0, 1.0 - hfbm, 0.85);

    base = pow(clamp(base, 0.0, 1.0), (1.0 - h) * 0.8 + 0.5);

    return clamp(base * cfg.density, 0.0, 1.0);
#endif
}

float bevyNormalizedHeight(vec3 pos, BevyCloudConfig cfg)
{
    float h = cfg.topHeight - cfg.bottomHeight;
    return (length(pos) - (cfg.planetRadius + cfg.bottomHeight)) / h;
}

float bevyVolumetricShadow(vec3 origin, BevyCloudConfig cfg,
    sampler2D atlasTex, vec2 atlasSize, sampler3D baseTex, sampler3D worleyTex, sampler3D shapeSampler)
{
    const int steps = max(cfg.shadowSteps, 1);
    const float stepCount = float(steps);

    const float originRadius = length(origin);
    const float topRadius = cfg.planetRadius + cfg.topHeight;
    const float b = originRadius * cfg.sunDir.y;
    const float c0 = originRadius * originRadius - topRadius * topRadius;
    const float disc = max(b * b - c0, 0.0);
    const float tExit = -b + sqrt(disc);

    const float maxLightDistance = cfg.shadowStepSize * stepCount;
    const float totalLightDistance = clamp(tExit, 0.0, maxLightDistance);
    const float intervalSize = totalLightDistance / stepCount;

    float opticalDepth = 0.0;
    for(int j = 0; j < 32; ++j)
    {
        if(j >= steps) break;
        const vec3 pos = origin + cfg.sunDir * (intervalSize * (0.25 + float(j)));
        const float h = bevyNormalizedHeight(pos, cfg);
        if(h > 1.0) break;
        const float density = bevyGetDensity(pos, h, cfg, atlasTex, atlasSize,
            baseTex, worleyTex, shapeSampler, true);
        opticalDepth += density;
    }

    const float tau = opticalDepth * cfg.density * intervalSize;
    return exp(-tau);
}

// clouds_compute.wgsl:138-146
float bevyIntersectSphere(float rayDirY, float sampleRadius, float planetRadius)
{
    float b = planetRadius * rayDirY;
    float c = sampleRadius * sampleRadius + 2.0 * planetRadius * sampleRadius;
    float d = b * b + c;
    if(rayDirY > 0.0) return c / (sqrt(d) + b);
    return sqrt(d) - b;
}

// 2026-10-18 正确版：原点是 (0, originRadius, 0)，球壳半径 sphereRadius。
// 解 t² + 2bt + c0 = 0（b = Ro·dirY，c0 = Ro² - Rs²），取**近交点**。
// 参考那版 `intersect_planet_sphere` 隐含"相机在海平面"（Ro = R），
// 我们相机在云下几百格，用它会把交点整体推后 ~camY 距离 ⇒ 采样点跑到云层之上，
// 于是"俯视有云、仰视全空"。
//
// 数值稳定性（2026-10-18 第二次修）：不能直接写 c/(s+b)。
// 相机在云层之上往下看时 b ≈ -3.6e6、s ≈ +3.6e6，两者相加要在 float32 里
// 相消掉 6 位有效数字，剩下的全是噪声 ⇒ 俯视整片"没有云"（y_top 那张平板）。
// 按二次方程的标准稳定写法：先算**幅值大**的那个根 q = -(b + sign(b)·s)
// （加法，无相消），再用两根之积等于 c0 求出近交点 c0/q。
//   相机在层内：c0<0，近交点为负 ⇒ 调用方据此判定"起点在壳内"。
float bevyIntersectShell(float originRadius, float rayDirY, float sphereRadius)
{
    float b = originRadius * rayDirY;
    float c0 = originRadius * originRadius - sphereRadius * sphereRadius;
    float d = b * b - c0;
    if(d < 0.0) return -1.0;
    float s = sqrt(d);
    float q = -(b + (b >= 0.0 ? s : -s));
    if(abs(q) < 1.0e-9) return 0.0;
    return c0 / q;
}

float bevyHG(float rayDotSun, float g);
float bevyMultiScatter(float sigma, float cosTheta, float g)
{
    float total = 0.0;
    float amp = 1.0;
    float attenuation = 1.0;
    for(int octave = 0; octave < 3; ++octave)
    {
        const float octaveG = min(g * (1.0 - 0.35 * float(octave)), 0.95);
        const float phase = mix(bevyHG(cosTheta, octaveG), bevyHG(cosTheta, -0.35 * g), 0.45);
        total += amp * phase * attenuation;
        amp *= 0.5;
        attenuation *= exp(-sigma * 0.35);
    }
    return total;
}

// clouds_compute.wgsl:148-151
float bevyHG(float rayDotSun, float g)
{
    float g2 = g * g;
    return (1.0 - g2) / pow(1.0 + g2 - 2.0 * g * rayDotSun, 1.5);
}

vec4 bevyRaymarch(vec3 rayOrigin, vec3 rayDir, float maxDist, BevyCloudConfig cfg,
    sampler2D atlasTex, vec2 atlasSize, sampler3D baseTex, sampler3D worleyTex, sampler3D shapeSampler,
    out float hitDistance)
{
    hitDistance = maxDist;

    const float originRadius = length(rayOrigin);
    float start = bevyIntersectShell(originRadius, rayDir.y,
        cfg.planetRadius + cfg.bottomHeight);
    float end = bevyIntersectShell(originRadius, rayDir.y,
        cfg.planetRadius + cfg.topHeight);
    float inside = 0.0;

    {
        const float tBottom = start;
        const float tTop = end;
        const bool insideShell = (tBottom <= 0.0 && tTop > 0.0);
        if(insideShell)
        {
            start = 0.0;
            end = (rayDir.y >= 0.0) ? tTop : tBottom;
        }
        else
        {
            start = max(min(tBottom, tTop), 0.0);
            end = max(tBottom, tTop);
        }
    }
    end = min(end, maxDist);
    if(!(end > start)) return vec4(0.0, 0.0, 0.0, 1.0);

    if(start > maxDist) return vec4(0.0, 0.0, 0.0, 1.0);

    int steps = max(cfg.raymarchSteps, 1);
    float stepDistance = (end - start) / float(steps);

    float detailLod = 5714.0 / max(cfg.baseScale * stepDistance, 1.0e-3);
    cfg.detailScale = min(cfg.detailScale, max(1.0, floor(detailLod * 2.0) * 0.5));

    float hashedOffset = bevyHash13(rayDir + fract(cfg.time));
    float dirLength = start - stepDistance * hashedOffset;

    float rayDotSun = dot(rayDir, cfg.sunDir);
    float scattering = mix(bevyHG(rayDotSun, cfg.forwardG),
        bevyHG(rayDotSun, cfg.backwardG), clamp(cfg.scatteringLerp * 0.85, 0.0, 1.0));

    vec3 scatteredLight = vec3(0.0);
    float transmittance = 1.0;
    float dist = maxDist;

    for(int step = 0; step < 256; ++step)
    {
        if(step >= steps) break;
        vec3 worldPosition = rayOrigin + dirLength * rayDir;
        float h = clamp(bevyNormalizedHeight(worldPosition, cfg), 0.0, 1.0);
        float density = bevyGetDensity(worldPosition, h, cfg, atlasTex, atlasSize, baseTex, worleyTex, shapeSampler, false);

        if(density > 0.0)
        {
            dist = min(dist, dirLength);

            float albedoDensity = clamp(density * 1.8, 0.0, 1.0);
            vec3 albedo3 = mix(vec3(1.00, 0.97, 0.90), vec3(0.42, 0.48, 0.56), albedoDensity);
            const float albedo = 0.98;
            float shadow = bevyVolumetricShadow(worldPosition, cfg, atlasTex, atlasSize, baseTex,
                worleyTex, shapeSampler);
            float msShadow = 0.47 * shadow
                + 0.28 * sqrt(shadow)
                + 0.16 * sqrt(sqrt(shadow))
                + 0.09 * sqrt(sqrt(sqrt(shadow)));
            shadow = mix(shadow * shadow, msShadow, 0.50);
            float skyVis = 0.03 + 0.97 * clamp(shadow, 0.0, 1.0);
            vec3 ambient = mix(cfg.ambientBottom, cfg.ambientTop, h) * skyVis;
            float phaseTotal = scattering + bevyMultiScatter(density, rayDotSun, cfg.forwardG);
            float sigmaT = density * cfg.density;
            float powder = 1.0 - exp(-sigmaT * 60.0);
            float powderWeight = clamp(dot(rayDir, cfg.sunDir), 0.0, 1.0);
            float powderTerm = mix(1.0, 0.4 + 0.6 * powder, powderWeight);
            float sunElev = clamp(cfg.sunDir.y, 0.0, 1.0);
            float lowSun = 1.0 - smoothstep(0.04, 0.35, sunElev);
            float bottomWeight = mix(0.45, 0.12, lowSun);
            float verticalLight = mix(bottomWeight, 1.0, smoothstep(0.0, 0.55, h));
            float backlit = clamp(rayDotSun, 0.0, 1.0);
            verticalLight *= mix(1.0, 1.0 - 0.55 * backlit, lowSun);
            vec3 Lsingle = cfg.sunColor * phaseTotal * shadow * powderTerm;
            float T1 = clamp(shadow, 0.0, 1.0);
            float T2 = sqrt(T1);
            float T4 = sqrt(T2);
            float msOctave = 0.500 * T1 + 0.250 * T2 + 0.125 * T4;
            vec3 Lms = cfg.sunColor * phaseTotal * powderTerm * msOctave * cfg.sunGlow;
            vec3 S = sigmaT * albedo * (ambient + Lsingle + Lms) * verticalLight * albedo3;
            if(cfg.silver > 0.001)
            {
                float glow = max(rayDotSun, 0.0);
                glow *= glow; glow *= glow; glow *= glow;
                float edgeW = 1.0 - smoothstep(0.0, 0.45, density);
                S += cfg.sunColor * (glow * edgeW * cfg.silver * albedo3);
            }
            vec3 sunsetTint = mix(vec3(1.0), vec3(1.0, 0.68, 0.34), lowSun * 0.85);
            S *= sunsetTint;
            float deltaT = exp(-sigmaT * stepDistance);
            scatteredLight += transmittance * S * (1.0 - deltaT) / max(sigmaT * albedo, 1.0e-5);
            transmittance *= deltaT;
        }

        if(transmittance <= cfg.minTransmittance) break;
        dirLength += stepDistance;
    }

    float fadeBegin = cfg.viewDistance * clamp(cfg.fadeStart, 0.0, 0.99);
    float fadeEnd = cfg.viewDistance;
    float distFade = 1.0 - smoothstep(fadeBegin, fadeEnd, dist);
    scatteredLight *= distFade;
    transmittance = mix(1.0, transmittance, distFade);

    hitDistance = dist;
    return vec4(scatteredLight, transmittance);
}


// ===========================================================================
// 3D 形状体生成器
//
// 与参考库的差别：bevy 用一张 2D 图集假装 3D，导致云是"竖直柱/横向条带"。
// 这里改为真正的 3D 体：
//   r  = Perlin-Worley（remap(perlin, 0, 1, worley, 1)）
//   g/b/a = 三级 Worley FBM（不同频率），供侵蚀使用
//
// 生成一次（64³ 或 128³），之后按世界坐标采样；成本与 32³ worley 同量级。
// ===========================================================================
// period = 单位立方体内的格点数（整数）。格点索引按 period 取模 ⇒ 噪声在 [0,1)³ 上
// **可无缝平铺**。3D 体积是按 REPEAT 平铺采样的（每个 shapeWorldScale 一次），
// 不平铺就会在世界坐标 x=0 / ±1200 处留竖直接缝（相机在 x=0 时正好劈在画面正中）。
// Worley 那边本来就是 tilable（bevyVoronoi 带 tile 参数），之前只有 Perlin 漏了。
float bevyPerlin3D(vec3 p, float period)
{
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n = 0.0;
    for(int z = 0; z < 2; ++z)
    for(int y = 0; y < 2; ++y)
    for(int x = 0; x < 2; ++x)
    {
        vec3 corner = mod(i + vec3(x, y, z), period);
        float g = bevyValueHash(corner) * 2.0 - 1.0;
        vec3 d = vec3(x, y, z) - f;
        n += g * (1.0 - abs(d.x)) * (1.0 - abs(d.y)) * (1.0 - abs(d.z));
    }
    return n * 0.5 + 0.5;
}

float bevyWorley3D(vec3 p, float freq)
{
    return bevyVoronoi(p * freq, freq);
}

float bevyWorleyFBM3D(vec3 p, float baseFreq)
{
    return 0.625 * bevyWorley3D(p, baseFreq) +
           0.250 * bevyWorley3D(p, baseFreq * 2.0) +
           0.125 * bevyWorley3D(p, baseFreq * 4.0);
}

vec4 bevyShapeVolume(vec3 coord);
vec4 bevyRenderBase3D(vec3 coord)
{
    float v = bevyTilableVoronoi(coord, 3, 2.0);
    float cover = 1.0 - smoothstep(0.62, 0.80, v);
    return vec4(cover, 0.5, 0.5, 1.0);
}

vec4 bevyShapeVolume(vec3 coord)
{
    vec3 p = coord * 4.0;
    float perlin = 0.0;
    float amp = 1.0;
    float total = 0.0;
    for(int octave = 0; octave < 3; ++octave)
    {
        float pf = 4.0 * float(1 << octave);
        perlin += amp * bevyPerlin3D(p * float(1 << octave), pf);
        total += amp;
        amp *= 0.5;
    }
    perlin /= total;

    float wfbm = clamp(bevyWorleyFBM3D(coord, 4.0), 0.02, 0.98);
    float perlinWorley = bevyRemap(perlin, 1.0 - wfbm, 1.0);
    perlinWorley = clamp(perlinWorley, 0.0, 1.0);

    return vec4(perlinWorley,
        bevyWorley3D(coord, 4.0),
        bevyWorley3D(coord, 8.0),
        bevyWorley3D(coord, 16.0));
}

#endif
