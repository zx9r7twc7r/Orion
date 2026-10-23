// ===========================================================================
// 体积云 v4 核心 —— evroon/bevy-volumetric-clouds（MIT）逐段移植
//
// 源文件：cpp/.tmp/reference/bevy-clouds/src/shaders/{common,clouds_compute}.wgsl
// 版权：MIT License, © evroon 与 bevy-volumetric-clouds 贡献者
//
// 与 v3 的结构差异（这也是它便宜一个数量级的原因）：
//   · 密度 = 2D "cloud map" 图集（R 底色 / G 覆盖率阈值 / B 高度因子）
//            + 32³ worley 做细节侵蚀；没有 128³ Perlin-Worley 体
//   · 光照 = Frostbite 能量守恒积分 S·(1-ΔT)/σ，自带 ambient 顶/底两色
//   · 阴影 = 短 march + 步长递增
//   · 噪声在 GPU 上用 init pass 一次性生成（tilable perlin/voronoi）
// 本文件**不含引擎依赖**，游戏与 tools/cloudpreview 共用。
// ===========================================================================
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
    float shapeAmount;     // 3D shape modulation strength (0 = pure 2D atlas)
    float shapeScale;      // spatial frequency of that variation
    float shapeWorldScale; // world size of one 3D shape tile (blocks)
    float planetRadius;
    float forwardG;
    float backwardG;
    float scatteringLerp;
    vec3 sunDir;          // 指向太阳
    vec3 sunColor;
    float time;
 // 2026-10-18：外形可调项
    float cloudType;      // 0=层云 0.5=层积云 1=积云（高度梯度）
    float erosionScale;   // 云缘侵蚀幅度倍率（1.0 = 当前标定）
    float weatherPeriod;  // 天气图（3D 低频）的世界周期，格。出厂 18000
    float viewDistance;   // 云视距（格）。超过即不画（硬上限）
    float fadeStart;      // **远处淡出起点**：距离的比例（0~1），从 fadeStart×viewDistance 开始淡出
    float silver;         // **逆光银边强度** 0~1（rayDotSun^8 × 边缘权重）
    float cohesion;       // **凝聚度** 0~1：0 = 碎（窄阈值 + 强侵蚀），1 = 整（宽阈值 + 弱侵蚀）
    float sunGlow;        // 逆光银边强度 0~1。出厂 0.10（再大夕阳处会过曝）

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
 // 2026-10-18 修 NaN：密度里有 remap(x, 1-cov, 1)，分母 = cov。
    // cov → 0 时这里是 0/0 = NaN，NaN 会一路传到 transmittance（exp(-NaN)），
    // 表现为"云量为 0 也有一个黑色球体/整片黑天空"。分母趋零时按阶跃处理。
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

// ---------------------------------------------------------------- 密度
// ===========================================================================
// A 方案原型：**有限云团**（2026-10-18，仅预览器打开；游戏端此开关恒 false）
//
// ⚠️ 已证伪，不要再沿这条路优化：只放 12 朵云就掉到 20.7 fps（图集版 65.9 fps，
// 1280×720 存档几何）。原因是每次密度采样要做 ~120 次球求值，而主 march 有 112 步、
// 加自阴影 12 步，且空域无法跳过；成本随云团数线性增长。
// 保留此段只为对照（证明"竖拖笔是 2D 场造成的"这个判断），产品化要走体积纹理。
//
// 与 2D 图集的根本差别：覆盖度是**局部三维坐标**的函数，Y 方向有真实结构，
// 所以仰视时不会再出现"水平场被投影成竖直柱"的拖笔。
// 每朵云团是**有限尺寸**的（超出半径直接没有云），因此也不会像平铺噪声那样
// "到处都有一点"而糊成连续云盖。
//
// 这不是最终形态：这里是手工摆的 6 朵（固定中心/半径，无 CPU 侧流送），
// 目的只有一个 —— 在预览器里验证"局部坐标 + 有限云团"能不能消掉竖拖笔。
// ===========================================================================
bool gBevyPuffMode = false;
// 2026-10-18：3D 体积密度实验（预览器 --use3d）。
// false = 当前上线的 2D FBM 路线；true = 走下面 3D Perlin-Worley 路径。
bool gBevyUse3D = false;
float bevyPerlin3D(vec3 p, float period);   // 定义在本文件末尾，原型里先用到
float bevyWorley3D(vec3 p, float freq);     // 同上（Perlin-Worley 组合要用）

const int kPuffCount = 12;
const vec3 kPuffCenter[12] = vec3[12](
    // 4 朵主云：距离 4300~8200、半径 1450~1600（照片里那种"一朵占半屏"的量级）
    vec3(   300.0, 2500.0,  4300.0),
    vec3( -3600.0, 2600.0,  5200.0),
    vec3(  3800.0, 2450.0,  6000.0),
    vec3(   800.0, 2800.0,  8200.0),
    // 8 朵低层碎云：贴着云底（y≈1980），构成照片下半部那条"层积云带"
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

// 子泡：单位云团坐标下的偏移 + 半径。云团内部 = 这些球的并集（菜花状）。
// 10 个泡 ≈ 照片里一朵云表面能数出来的鼓包量级；半径 0.26~0.44 让它们互相咬合，
// 泡与泡之间自然形成凹槽 ⇒ 才有可被自阴影压暗的沟壑。
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
        // 云团**局部坐标**：噪声域每朵都不同，且不随世界坐标平铺
        vec3 q = (localPos - kPuffCenter[i]) / kPuffRadius[i];
        // 云团整体包络（椭球，竖向压扁 ⇒ 底平顶鼓）
        float env = length(vec3(q.x, q.y * kPuffSquash[i], q.z));
        // 包络再加低频扰动，免得每朵都是同一个椭球
        env += (bevyPerlin3D(q * 1.5 + vec3(float(i) * 13.7), 64.0) - 0.5) * 0.55;

        // 菜花状 = 一堆小球取并集（不是"椭球 + 噪声位移"：后者只会给出折角的
        // 石块状轮廓，实测就是"石膏块"）。子泡位置/半径固定表 + 按云团序号旋转，
        // 这样每朵云的鼓包分布不同，又不用每朵存一张表。
        // 6 个子泡 × 12 朵 = 72 次 length()/采样，相对 100 步的 march 可以接受。
        float ca = cos(float(i) * 1.7);
        float sa = sin(float(i) * 1.7);
        float lobes = 0.0;
        int lobeCount = i < 4 ? 10 : 3;   // 低层碎云本身很小，3 个泡就够
        for(int j = 0; j < 10; ++j)
        {
            if(j >= lobeCount) break;
            vec3 sp = kPuffLobe[j];
            vec2 rot = mat2(ca, -sa, sa, ca) * sp.xz;
            sp = vec3(rot.x, sp.y, rot.y);
            float d = length((q - sp) / kPuffLobeRadius[j]);
            lobes = max(lobes, 1.0 - smoothstep(0.70, 1.0, d));
        }
        // 包络裁剪 + 泡泡并集：两者相乘 ⇒ 鼓包只出现在云团内部，云团边界仍是有限尺寸
        float cover = (1.0 - smoothstep(0.55, 1.15, env)) * lobes;
        best = max(best, cover);
    }
    return best;
}

// ===========================================================================
// B 方案：把云团场**烘焙进 3D 体积**，采样回到一次 texture()
//
// 2026-10-18 第二轮：从"无限平铺"改成**以相机为中心的单块体积**（CLAMP 寻址）。
// 平铺版（周期 8000 格）在视距内会把同一排云重复 4~6 次，肉眼可见。
// 现在的做法：
//   · 云团位置由**世界坐标的 cell 点阵**决定（每 4200 格一格，hash 出中心/半径/高度），
//     相机移动时云在世界里是**钉住的**，重烘只改变覆盖范围，不会"云跟着人跑"；
//   · 体积以相机为中心、覆盖 gBevyVolumeExtent 格，边缘淡出（见 bevyCloudMapBase）；
//   · 相机水平移动超过 extent/8 才重烘（宿主侧调度，见 viewer.cpp）。
// ===========================================================================
bool gBevyVolumeMode = false;
vec3 gBevyVolumeOrigin = vec3(0.0);   // 体积中心（世界 xz；y 不用）
float gBevyVolumeExtent = 10000.0;    // 体积边长（格）
float gBevyLayerHeight = 1296.0;      // 云层厚度（格），y 方向映射用
float gBevyVolumeYRange = 0.30;       // 体积 y 只承载层高的这一比例（层 1296 占 10000 的 13%）

// cell 点阵间距（格）。10000 格视距内 ≈ (10000/2800)² ≈ 12 个格子、
// 8 成有云 ⇒ 视野里能看到 4~6 朵（4200 时只有 1~2 朵，实测太空）。
const float kPuffCell = 2800.0;

vec4 bevyRenderPuffVolume(vec3 coord)
{
    // 体积坐标 → 世界（xz 以格计；y 是**相对层中心**的高度，单位也是格）
    vec2 wp = gBevyVolumeOrigin.xz + (coord.xz - 0.5) * gBevyVolumeExtent;
    float wy = (coord.y - 0.5) * gBevyVolumeYRange * gBevyLayerHeight;

    vec2 baseCell = floor(wp / kPuffCell);
    float best = 0.0;
    for(int dz = -1; dz <= 1; ++dz)
    for(int dx = -1; dx <= 1; ++dx)
    {
        vec2 c = baseCell + vec2(float(dx), float(dz));
        float h0 = bevyValueHash(vec3(c, 1.0));
        if(h0 > 0.80) continue;           // 20% 的格子留空 ⇒ 云隙里是干净的蓝天

        vec2 jitter = vec2(bevyValueHash(vec3(c, 2.0)), bevyValueHash(vec3(c, 3.0)));
        vec2 center = (c + 0.5 + (jitter - 0.5) * 0.5) * kPuffCell;
        float radius = mix(700.0, 1300.0, bevyValueHash(vec3(c, 4.0)));
        float heightOffset = (bevyValueHash(vec3(c, 5.0)) - 0.5) * gBevyLayerHeight * 0.35;

        // 竖向半径取 0.8R：层厚 1296 会自然把底部切平（真实积云也是底平顶鼓）。
        // 注意 vec3(vec2, float) 会把"世界 z"塞进 y 分量 —— 必须显式摆到 xz 上，
        // 否则云被横向压成刀片（踩过）。
        vec2 d2 = (wp - center) / radius;
        vec3 q = vec3(d2.x, (wy - heightOffset) / (radius * 0.8), d2.y);
        float env = length(q);
        if(env > 1.35) continue;          // 先做便宜的包络剔除，绝大多数格子在这里就退出了

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
        // 一次 3D 纹理采样（CLAMP + 三线性），成本与图集分支相同量级。
        // y 用**层内归一化高度**而非世界 y：体积的 y 只承载云层那 1296 格，
        // 否则云团全被层底裁掉（实测过），同时 y 被压缩 ⇒ 得到"底平顶鼓"的扁云团。
        vec3 localPosV = p - vec3(0.0, cfg.planetRadius, 0.0);
        vec2 uvwXZ = (localPosV.xz - gBevyVolumeOrigin.xz) / gBevyVolumeExtent + 0.5;
        float yFrac = clamp(0.5 + (normalizedHeight - 0.5) * gBevyVolumeYRange, 0.02, 0.98);
        // 边缘淡出：CLAMP 寻址在体积外会把边缘值拉满，必须手动收到 0，
        // 否则视野边缘会出现一圈"贴边云墙"。
        float edge = max(abs(uvwXZ.x - 0.5), abs(uvwXZ.y - 0.5));
        float fade = 1.0 - smoothstep(0.40, 0.50, edge);
        // 体积的通道分配是 (世界 x, 层内高度, 世界 z)，与烘焙端一致；
        // 写成 vec3(uvwXZ, yFrac) 会把世界 z 塞进 y 通道 ⇒ 云整片错位（踩过）。
        float coverV = texture(baseTex, vec3(uvwXZ.x, yFrac, uvwXZ.y)).r * fade;
        return 0.30 + 0.40 * coverV;
    }
    if(gBevyPuffMode)
    {
        // 映射到与图集分支相同的"归一化前"值域：下游 (m-0.30)*2.5 之后
        // 0..1 正好对应 cover 0..1（所以覆盖度滑条语义不变）。
        vec3 localPos = p - vec3(0.0, cfg.planetRadius, 0.0);
        return 0.30 + 0.40 * bevyPuffCover(localPos);
    }
    // 覆盖度回到 2D 图集（tilable voronoi 的 2D 版）：**它是目前唯一能出
    // 正确团块尺度与离散云团的方案**。代价是仰视有竖向拖笔（见 HANDOFF 第十/十一轮）。
    // 3D 体积的探索（voronoi 3D）代码保留在 bevyRenderBase3D 里，
    // 换回来需要把下面的 cover 改成采样 baseTex，并按 --hist 重标定。
    // 2026-10-18（第十二轮）：坐标扭曲试过，**没用**——剪切只是把柱体整体掰斜，
    // 竖拖笔变成斜拖笔（同一条结论：2D 场沿 y 恒定时，位移改不了"柱"的本质）。
    // 真正的出路在 bevyGetDensity 里：用 3D 噪声定义**每处的云顶高度**。
    //
    // 位移**试过并且回滚了**（2026-10-18）：把幅度加到 0.22 tile（≈630 格）、
    // 随 h 以 3.5 频率变化后，云变成"羽毛/扫帚"——2D 图案被拉伸，方向性刷痕更明显。
    // 这是信息量问题：2D 场沿 y 恒定，任何位移都只是把图案掰弯/拉长，不会生出新结构。
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
    // 2026-10-18 修精度：position 是**行星球心坐标**，y ≈ 6.36e6。直接乘
    // 0.0016*baseScale*detailScale（detailScale=24 时约 0.035）后 y 变成 22 万，
    // 而 float32 在 22 万量级的间距是 0.04 —— 比一个 texel（1/64 = 0.0156）还粗，
    // 采样就会在 Y 方向整格跳，画面表现为一圈竖条纹。
    // 减掉行星半径回到"海拔"和平面坐标再乘，量级降到 1e3 以内，精度足够。
    vec3 localPos = position - vec3(0.0, cfg.planetRadius, 0.0);
    // 去掉 abs()：它会在 x=0 / z=0 平面留下镜像折痕（相机在 x=0 时正好劈在画面正中，
    // 表现为左右两半镜像）。采样器是 REPEAT，负坐标本来就是合法的。
    vec3 p = localPos * (0.0016 * cfg.baseScale * cfg.detailScale);
    return texture(worleyTex, p * (1.0 / 32.0)).r;   // REPEAT 寻址 = 原来的 mod(p,32)
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
    // 参考 mixGradients()：三种云型的竖向剖面按 cloudType 插值（权重和 = 1）
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
    return 1.0 - best;      // 细胞中心 1，边界 0
}

// 2026-10-18：Worley 版实测云太稀（F1 值域集中在高位），回到 value-noise FBM。
// 4 octave、weight 0.5 递减、倍频 2.03（不可公比，避免条带）。
// ===== Classic Perlin 3D（Stefan Gustavson, webgl-noise）=====
// 2026-10-19 第三次重构引入。之前用的是 value noise / 伪 Perlin（hash 加三线性权重），
// 都有明显的网格感；这是**真正的梯度噪声**（8 个梯度点积 + 五次插值），
// 空间连续性和各向同性都好得多，且不需要噪声纹理。
// 返回约 -1..1。
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
    return sum;             // 0 .. 0.9375
}

// cheapVersion：光线步进专用（2026-10-22）。
// 参考 jiaozi158/UnityVolumetricCloudsURP（MIT, 2024）VolumetricCloudsUtilities.hlsl:531
// 的 EvaluateCloudProperties(..., cheapVersion=true, ...)：沿太阳方向求透过率时只需要
// "这里有没有云、大概多厚"，云缘的 detail 侵蚀在那个采样尺度上根本采不到
// （我们的注释 §第15轮也记过同一条：比步长更细的 octave 只会被 TAA 平均成噪声）。
// 所以阴影分支白付的那 1 次 cnoise 可以直接省掉。
float bevyGetDensity(vec3 pos, float normalizedHeight, BevyCloudConfig cfg,
    sampler2D atlasTex, vec2 atlasSize, sampler3D baseTex, sampler3D worleyTex,
    sampler3D shapeSampler, bool cheapVersion)
{
    // =====================================================================
    // 2026-10-19 **全量重构：Hillaire 模型**
    // 参考：Hillaire, "Physically Based Sky, Atmosphere and Cloud Rendering
    //       in Frostbite"（SIGGRAPH 2016），云密度 + 能量守恒散射。
    //
    // 与旧实现的根本区别：
    //   · 密度是**物理量 0~1**，消光 σt = 密度 × σtBase（在光照侧乘），
    //     不再返回"密度 × 某个魔法系数"；
    //   · 竖向用 **stratus / cumulus 剖面曲线**（Hillaire 的高度函数），
    //     不再是 sin² 或线性 vbias；
    //   · 覆盖率用**平滑 remap**（weather map → coverage → smoothstep），
    //     不是"阈值 clamp"；
    //   · 侵蚀只作用于**云缘**（低密度区），云心保持完整。
    // =====================================================================
    {
        float hn = clamp(normalizedHeight, 0.0, 1.0);
        if(normalizedHeight < 0.0 || normalizedHeight > 1.0) return 0.0;
        vec3 wp = pos;   // 绝对世界坐标（anchor 跳变不影响图案）

        // ① 高度剖面：Hillaire 的 stratus（层云）/ cumulus（积云）曲线
        float ct = clamp(cfg.cloudType, 0.0, 1.0);
        float pStratus = smoothstep(0.00, 0.15, hn) * (1.0 - smoothstep(0.80, 1.00, hn));
        float pCumulus = smoothstep(0.08, 0.32, hn) * (1.0 - smoothstep(0.60, 0.95, hn));
        float profile = mix(pStratus, pCumulus, ct);
        if(profile <= 0.001) return 0.0;

        // ② 形状噪声：梯度 Perlin 2 octave + **domain warp**。
        //    · 单 octave 太光滑（云像奶油）；2 octave 才有"团块里有褶皱"；
        //    · domain warp（用一层低频噪声扭曲采样坐标）打散 Perlin 的**等值线**
        //      —— 不加的话窄阈值会把平滑场切成一条条同心环（洋葱圈）；
        //    · y 频率 2.6 → 5.0：1800 格层厚里从 2.6 个噪声周期提高到 5 个，
        //      云才会在竖直方向真正"长起来"，而不是一张平铺的毯子。
        float shapeScale = max(cfg.baseScale, 0.05) * 1400.0;
        // warp 用**廉价的 2D value noise**（4 次 hash vs cnoise 的 ~60 次浮点）。
        // 它只是低频位移，不需要梯度噪声的平滑度；实测这一项省下 ~25% 帧率。
        // 边缘卷曲固定 0.40（原 bottomSoftness lane 已改作「凝聚度」）
        const float warpAmt = 0.40;
        float warpN = bevyValueNoise2D(wp.xz / (shapeScale * 2.0) + vec2(hn * 1.7, 0.0));
        vec2 warpOff = vec2(warpN, warpN * 1.37) * shapeScale * warpAmt;
        // **各向同性**：水平噪声周期 = shapeScale（770 格），竖向也要接近它。
        // 层厚 1800，乘 2.0 ⇒ 竖向周期 900 格，与水平 770 基本一致 —— 云块才会是
        // 圆润的一团，而不是被切成"多层薄片"。之前乘 5.0（周期 360）竖切太密，
        // 这正是"像纸一层层"的几何原因。
        // 「竖向拉伸」：原 detailStrength lane，0~1 → hn 倍率 1.0~5.0。
        // 1.0 = 扁片（薄毯），5.0 = 高塔（竖向变化快、像积云塔）
        float vertStretch = 1.0 + clamp(cfg.detailStrength, 0.0, 1.0) * 4.0;
        vec3 np = vec3((wp.xz + warpOff) / shapeScale, hn * vertStretch);
        // 2026-10-19 **3 octave Perlin**（0.55 / 0.30 / 0.15）。
        // 单 octave 只有一个特征尺度 ⇒ 密度场是"一层壳"，团块之间必然有硬边界。
        // 三层不同尺度叠加后，大团里套小团、小团之间又有过渡，
        // 这才是"密"（连续且多尺度）的来源。代价：每步 3 次 cnoise。
        float perlin = 0.55 * bevyCnoise(np)
                     + 0.30 * bevyCnoise(np * 2.3)
                     + 0.15 * bevyCnoise(np * 5.1);
        perlin = clamp(perlin * 0.5 + 0.5, 0.0, 1.0);

        // ③ 天气图（大尺度覆盖，周期 3.5 倍于形状噪声）。
        // 这里用便宜的 2D value noise 就够了 —— 它是低频场，不需要梯度噪声的平滑度，
        // 而 cnoise 每步 ~60 次浮点运算，占了可观的成本。
        float weather = bevyValueNoise2D(wp.xz / (shapeScale * 3.5));

        // ④ 覆盖率 → **平滑 remap**（Hillaire 的做法，不是硬阈值）
        float cov = clamp(cfg.coverage, 0.0, 1.0);
        // Hillaire 的覆盖率 remap：窗口要窄，否则天气图均值 0.5 会让 coverage 接近 1
        // （实测 1.45 系数 + 0.30 窗口 ⇒ cov=0.55 时几乎全天空有云）。
        float lo = 1.0 - cov;
        float coverage = smoothstep(lo, lo + 0.18, weather);

        // ⑤ 基础密度：**窄过渡带**（0.45~0.66）。
        // 原来 0.35~0.78 宽达 0.43，等于把云的边缘拉成一大片渐变 —— 视觉上就是
        // "融化的奶油"。收窄后云缘有明确边界，配合侵蚀才有絮状撕裂。
        // 过渡带 + **凝聚度**：cohesion 0 ⇒ 窄阈值（碎）、1 ⇒ 宽阈值（连片）。
        float coh = clamp(cfg.cohesion, 0.0, 1.0);
        float thLo = mix(0.45, 0.26, coh);
        float thHi = mix(0.72, 0.82, coh);
        float base = smoothstep(thLo, thHi, perlin) * coverage;
        // **早退**：云外（base 极低）直接返回，省掉后面的细节噪声（实测是最大的一笔开销）
        if(base <= 0.02) return 0.0;

        // 光线步进的 cheap 收尾：到 base*profile 为止，不付 detail 那一次 cnoise。
        if(cheapVersion)
        {
            return clamp(base * profile, 0.0, 1.0);
        }

        // ⑥ 侵蚀：只作用于云缘（低密度区），云心保持实心
        float detailScale = max(cfg.detailScale, 0.1) * 55.0;
        vec3 dp = vec3(wp.xz / detailScale, hn * 8.0);
        float detail = bevyCnoise(dp) * 0.5 + 0.5;
        // 侵蚀也随凝聚度衰减：cohesion 高时云缘不被啃碎，云更"整"
        float erosion = clamp(cfg.erosionScale, 0.0, 2.0) * 0.42 * mix(1.0, 0.30, coh);
        // 侵蚀：**85% 权重压在云缘**，云心几乎不动。
        // 上一轮为了"云心有结构"给了 45% 全域权重，结果侵蚀在云体内**打穿出洞**，
        // 视线穿透到另一侧 —— 这就是"纸片镂空"的直接成因。
        // 真实云的内部结构应该靠**自阴影**表现，不是靠挖洞。
        float edgeMask = 1.0 - smoothstep(0.30, 0.80, base);
        float erosionMask = mix(0.15, edgeMask, 0.85);
        float dens = base - (1.0 - detail) * erosion * erosionMask;

        // ⑦ 竖向剖面
        dens *= profile;
        // 返回**物理密度 0~1**；σt = dens × σtBase 在光照积分里乘。
        return clamp(dens, 0.0, 1.0);
    }

    // ---- 旧实现（不可达，保留对照）----
    // === 重构后的密度（iq/SEUS 模型）===
    if(!gBevyUse3D)
    {
        // ===== 2026-10-19 **纯程序化噪声 + 侵蚀**（成本最低的细节方案）=====
        // 不做任何纹理采样：4 octave FBM 定大尺度，两层高频 value noise **扰动阈值**。
 // 每个采样点 24 次 hash，比（2 次噪噪 + 1 次 3D 纹理）更便宜。
        // 滑条全部接上：
        //   云团大小 → scale     云量 → threshold     云体密度 → density
        //   云型 → vbias（竖向铺展）   云缘细节尺度 → dScale（细节频率）
        //   云团对比 → wB（第二层细节权重）  云缘侵蚀幅度 → amp   云层上下分布 → vbias 陡缓
        {
            float hn = clamp(normalizedHeight, 0.0, 1.0);
            if(normalizedHeight < 0.0 || normalizedHeight > 1.0) return 0.0;
            vec2 worldXZ = pos.xz;      // 世界坐标：避免云层 anchor 跳变导致图案瞬移
            float scale = max(cfg.baseScale, 0.05) * 1200.0;
            // 2026-10-19 消竖条：2D 场沿 y 恒定，仰视时每朵云会向下"复制"成竖线。
            // 用高度做一个**小幅坐标扭曲**（domain warp，±0.35 个云团尺度），
            // 上下截面就错开了；只多 1 次 value noise（4 次 hash），比加 3D 纹理便宜得多。
            // 2026-10-19 **根治竖条与同心环**：基础噪声改用 3D（2 octave）。
            //   · 2D 场沿 y 恒定 ⇒ 仰视时云被"复制"成竖柱；
            //   · 只依赖高度的 warp ⇒ 图案沿等高线偏移 ⇒ 同心环。
            // 3D noise 两个问题都没有。成本：8hash×2 = 16，与 4 octave 2D（4hash×4）相同。
            // y 频率 2.2 ⇒ 层内跨 2 个噪声周期，竖向有真实层次。
            // y 频率 2.2 → 3.6：层厚 350 时竖直方向从 0.77 个噪声周期提高到 1.26 个，
            // 云内部才有"堆叠"的层次，而不是一整块均匀的雾。
            // 2026-10-19 第三次重构：**真正的梯度 Perlin（Gustavson cnoise）**
            // 替代原来的 value noise / 伪 Perlin。2 个 octave，
            // 之后映射到 0..1 供阈值使用。
            vec3 np = vec3(worldXZ / scale, hn * 3.6);
            float perlin = 0.62 * bevyCnoise(np)
                         + 0.38 * bevyCnoise(np * 2.3);
            float f = clamp(perlin * 0.5 + 0.5, 0.0, 1.0);
 // 2026-10-19 试过 Perlin-Worley 组合，
            // 结果：云被打成**颗粒状**（Worley 的 27 邻域在云团尺度下是高频噪点），
            // 帧率也从 58 掉到 49.9。纯梯度 Perlin 在这里更干净，已回退。
            // 若将来要"花椰菜鼓包"，应该用**低频** Worley（freq≈1）且混合 ≤0.15。
            // ===== 2026-10-19 高度渐变曲线（stratus / stratocumulus / cumulus）=====
            // 原来是一个线性项 `h * vbias`：云的竖向分布是"越往上越稀"，没有任何形状控制。
            // 换成**高度窗**：底部在 hLo 上升、顶部在 hHi 收掉，两端都带 0.30 的柔和过渡。
            //   cloudType 0（层云）：窗几乎占满整层（0.02~0.98，上下都平）
            //   cloudType 1（积云）：窗收窄到 0.18~0.72（底高、顶低 ⇒ 塔状）
            // 窗口外用一个大的负值把密度压掉，窗口内不受影响。
            float ct = clamp(cfg.cloudType, 0.0, 1.0);
            // 「云层上下分布」把整个窗上下移动（0 = 不动，1 = 上移 0.10）
            float hShift = clamp(cfg.bottomSoftness * 3.0, 0.0, 1.0) * 0.10;
            float hLo = mix(0.02, 0.18, ct) + hShift;
            float hHi = min(mix(0.98, 0.72, ct) + hShift, 0.99);
            // 2026-10-19 **云底不再是平面**（这是"云像纸"的第一根因）：
            // 原来 hWindow 只吃 hn ⇒ 同一水平高度上密度完全一致，仰视就是一张纸。
            // 用基础噪声 f 给高度加 ±0.11 层厚的扰动（层厚 350 ⇒ ±38 格），
            // 云底/云顶随噪声起伏，夕阳光扫过时才照得出一块块明暗。
            float hnWarp = hn + (f - 0.5) * 0.22;
            float hWindow = smoothstep(hLo, hLo + 0.30, hnWarp) *
                            (1.0 - smoothstep(hHi - 0.30, hHi, hnWarp));
            // ===== 2026-10-19 **解耦「云量」与「云团大小」** =====
            // 原来 `threshold = 1.02 - coverage*1.05`：调小云量 = 抬高阈值 = 云团从外向内
            // 收缩，于是"云变少"的同时"每朵云也变小"（用户反馈的正是这个）。
            //
            // 现在拆成两层：
            //   · **云区 mask**（低频噪声，尺度 3.5 倍于云团）：由 coverage 控制阈值，
            //     决定"天空的哪些地方允许长云"——调小云量只是云区变少；
            //   · **云团形状**（f，固定阈值）：每朵云的尺寸完全由「云团大小」决定，
            //     不再随云量变化。
            float maskNoise = bevyValueNoise2D(worldXZ / (scale * 3.5) + vec2(29.1, 7.3));
            // mask 阈值按 value-noise 的实际分布标定（它的值集中在 0.5，
            // 线性映射会让 cov=0.25 直接变成"几乎没云"）。实测：
            //   cov 0.25 → 阈值 0.60 → 约 20% 天空有云
            //   cov 0.50 → 阈值 0.47 → 约 60%
            //   cov 0.90 → 阈值 0.27 → 约 90%
            float maskThreshold = 0.72 - clamp(cfg.coverage, 0.0, 1.0) * 0.5;
            float region = smoothstep(maskThreshold, maskThreshold + 0.18, maskNoise);
            // 云团用**固定阈值**。0.76 对应原来出厂 0.25 云量时的等值线
            // （旧公式 1.02 - 0.25*1.05 = 0.7575），这样"云团本身多大"保持不变，
            // 云量只剩 mask 一个入口。
            const float cloudIso = 0.76;
            float d = 2.0 * f - (1.0 - hWindow) * 1.15 - cloudIso;
            d *= region;                    // 云区内按 mask 强度调制
            d -= (1.0 - region) * 2.0;      // 云区外**强制归零**（只乘不减会留薄云/竖丝）
            float amp = 0.45 * clamp(cfg.erosionScale, 0.0, 2.0);
            float dScale = 0.15 * (10.0 / clamp(cfg.detailScale, 0.1, 12.0));
            // 细节层也要吃同一个高度偏移，否则它们仍沿 y 复制（竖条的主因就在这里）。
            // 细节尺度更小，同样的偏移相对更大，所以乘 2.5 / 5 倍放大。
 // 2026-10-19 **curl 域扭曲**：
            // 用一层低频噪声做 2D 偏移，再喂给细节层，云缘的絮状就会"卷"起来，
            // 而不是规整的棉絮块。成本：1 次 value noise（4 次 hash）。
            float warpSeed = bevyValueNoise2D(worldXZ / (scale * 1.8) + vec2(5.3, 11.7));
            // 注意：第二分量**不能用 fract** —— 它会在噪声晶格边界跳变，
            // 云缘于是出现一轮轮的方块/阶梯（实测夕阳逆光下最明显）。
            // 用线性的 warpSeed*1.73 保持连续，两分量各自相关但都平滑。
            vec2 warpOff = vec2(warpSeed - 0.5, warpSeed * 1.73 - 0.865) * 0.55;
            float dA = bevyValueNoise2D(worldXZ / (scale * dScale) + warpOff);
            float dB = bevyValueNoise2D(worldXZ / (scale * 0.055) + vec2(17.3, 9.1) + warpOff * 1.8);
            float wB = clamp(cfg.detailStrength * 2.5, 0.05, 0.95);
            // ===== 2026-10-19 **侵蚀按密度分级**：云心保完整、边缘才撕裂 =====
            // 之前侵蚀是"一刀切"，拉满时连云心都被切碎 ⇒ 云散成一堆小团、聚不到一起。
            // 真实积云恰恰相反：核心是实心大团，只有外缘（密度刚过阈值的地方）被撕成絮状。
            // erosionMask 在 d 高（云心）时趋于 0，在 d 低（云缘）时接近 1。
            float erosionMask = 1.0 - smoothstep(0.10, 0.55, d);
            d += (dA - 0.5) * amp * erosionMask;
            d += (dB - 0.5) * amp * wB * 0.62 * erosionMask;
            return clamp(d, 0.0, 1.0) * cfg.density;
        }

#if 0   // ===== 旧的实现（保留对照，不执行）=====
 // ===== 2026-10-18 **照抄**：CalculateDensityForLayer
        // 四处关键差异，我们之前全反着做：
        //   ① 水平尺度 = positionScale × 层厚 ⇒ 云团只有 ~275 格（我们用 1080，大 4 倍）
        //   ② 竖向剖面是 sin²(h·π)：底部 0 → 中间 1 → 顶部 0（不是 smoothstep 渐变）
        //   ③ 3D 细节坐标除以层厚 ⇒ 各向同性，不会被打扁
        //   ④ 侵蚀只取高频（先减 0.8），幅度只有 0.15（我们 1.15）
        float h = clamp(normalizedHeight, 0.0, 1.0);
        if(normalizedHeight < 0.0 || normalizedHeight > 1.0) return 0.0;
        vec3 localPos = pos - vec3(0.0, cfg.planetRadius, 0.0);
        float thickness = max(cfg.topHeight - cfg.bottomHeight, 1.0);
 // ① 水平：云团尺度 = baseScale × 层厚
        float horizScale = max(cfg.baseScale, 0.05) * thickness;
        vec2 p2 = localPos.xz / horizScale;
        // 两层噪声的权重由「云团对比」控制（cfg.detailStrength 0~1，0.5 = 各半）
        // 高频占比大 ⇒ 云更碎、团块更小；占比小 ⇒ 云更整、更成片。
        // 滑条行程 0~0.5（旧「边缘侵蚀强度」的范围）映射到高频占比 0.05~0.95。
        // 0.2（出厂）⇒ 0.5，即两层噪声各半。
        float w2 = clamp(cfg.detailStrength * 2.5, 0.05, 0.95);
        // 2026-10-18 性能：原来的两次 `bevyValueNoise2D` 每次要 4 次 hash（共 8 次），
        // 每步都算一次 ⇒ 主 march 30 步就是 240 次 hash/像素。
 // 用的是**噪声纹理**（1 次 texture 抵 8 次 hash），这里改成采样图集 R 通道
        // （它本来就是 fbm，纹理重复是 REPEAT，语义一致），成本降到 2 次 texture。
        // 2026-10-18：试过用纹理代替程序化噪声（图集 / 3D 切片），**都会出竖丝**。
        // 根源不是噪声来源，而是 baseNoise 只依赖 xz、沿 y 恒定 ⇒ 视线穿过厚层时
 // 看到的是一根根竖柱。没这问题是因它层厚只有 110 格（柱很短）。
        // 所以：**厚层必须用 3D 密度**；2D 模型只在薄层（≤400 格）下成立。
        // 高频倍率（「云团碎度」）：第二层噪声相对第一层的频率。
        // 复用 cfg.shapeScale（0.05~0.3 → 1.5~4.0 倍）
        float highFreq = clamp(cfg.shapeScale * 13.0, 1.3, 4.0);
        float baseNoise = bevyValueNoise2D(p2) * (1.0 - w2) +
                          bevyValueNoise2D(p2 * highFreq) * w2;
        // 竖向衰减：「云层上下分布」控制 fade 的陡缓（0 = 线性，1 = 强二次）
        float fadeT = clamp(cfg.bottomSoftness * 3.0, 0.0, 1.0);
        float fade = mix(1.0 - h, 1.0 - h * h, fadeT);
        float coverage = clamp(cfg.coverage, 0.0, 1.0) * fade;
 // 基础阈值（「云量阈值」）：0.65 是值，越大云越稀
        float threshold = clamp(cfg.baseEdgeSoftness, 0.35, 0.95);
        float cloudBase = clamp(baseNoise - (threshold - coverage), 0.0, 1.0);
        if(cloudBase <= 0.01) return 0.0;
        // ③④ 3D 细节。**周期必须用固定世界尺度**，不能写成 thickness×2.86：
 // 层厚 110 ⇒ 周期 314 格；我们层厚 1296 照抄会变成 3706 格，
        // 128³ 体积在那尺度下等于没有细节（整片糊成白团）。
        // 取 700 格：128³/700 ≈ 5.5 格/体素，能看到鼓包、又不至于出体素台阶。
        // 细节周期由「云缘细节尺度」控制：滑条 0.1~12 → 周期 4500/值（10 ⇒ 450 格）
        float detailPeriod = 4500.0 / clamp(cfg.detailScale, 0.1, 12.0);
        float detail = texture(shapeSampler, localPos / detailPeriod).r;
 // 2026-10-18 修： `detail-0.8` 是针对它自己那套双层噪声的分布
        // （值域接近 0~1、均值高）。我们的 shape.r 均值只有 0.26（--probe 实测），
        // 减 0.8 之后恒为 0 ⇒ 侵蚀完全无效（改周期、改幅度都没反应）。
        // 按实测区间重标：减 0.25 再放大 2 倍，得到 0~1.4 的有效侵蚀量。
        detail = max(detail - 0.25, 0.0) * 2.0;
        // 2026-10-18：试过加第二层高频 3D 细节，帧率 32 → 8（每步多一次 3D 纹理，
        // 而主 march 有 30+ 步）。云缘的碎感只能靠**单层 + 更大幅度**，已回退。
 // 侵蚀幅度： 0.15 是它那套"薄层 + 低密度"的标定；
        // 我们密度高 3 倍、层厚 3 倍，0.15 在画面上几乎看不出来（实测云缘仍是光滑贝壳）。
        // 提到 0.55，云缘才有絮状撕扯。
        float cloudDensity = clamp(cloudBase -
            detail * 0.55 * clamp(cfg.erosionScale, 0.0, 2.0), 0.0, 1.0);
        // 竖向剖面：sin²(hπ) 基准，用「云型」在 sin / sin² / sin⁴ 之间插值。
        // **不要用 pow()** —— 每个采样点算一次 pow 会让帧率从 56 掉到 14（实测）。
        // 三次乘法达到同样的效果。
        float s = max(sin(h * 3.14159265), 0.0);
        float s2 = s * s;
        float ct = clamp(cfg.cloudType, 0.0, 1.0);
        float profile = mix(s, s2, clamp(ct * 2.0, 0.0, 1.0));
        profile = mix(profile, s2 * s2, clamp(ct * 2.0 - 1.0, 0.0, 1.0));
        cloudDensity *= profile;
        return clamp(cloudDensity * cfg.density * 5.0, 0.0, 1.0);
#endif
    }

    // =====================================================================
 // 死代码（2026-10-18 标注）：下面是照抄 Schneider/实现，
    // **当前不执行**——上面的 2D 版已经 return。
    //
    // ⚠️ 调云的效果必须改上面那段 2D 版；改这里不会有任何变化
    //    （曾经在这段里改了半天 hfbm/remap，全部无效，因为根本走不到）。
    // 保留仅供对照（它证明了 3D 体积 + remap 路线在这里的分辨率上限）。
    // =====================================================================
#if 1   // 由 gBevyUse3D 控制（预览 --use3d）
    vec3 localPos = pos - vec3(0.0, cfg.planetRadius, 0.0);
    float h = clamp(normalizedHeight, 0.0, 1.0);

    // ① base：3D Perlin-Worley。周期取 6000 格 —— 参考是 p*0.00008（12500 单位），
    //    其层厚 2500 单位 ⇒ 约 5 个层厚；我们层厚 1296 ⇒ 6480，取整 6000。
    // 2026-10-18：base 周期接上「云团大小」滑条。
    // 之前这里写死 6000，而 size 只影响天气图和 detail LOD ⇒ **没有任何入口能改云的尺度**，
    // 这就是"噪声怎么调都调不动"的原因。
    // 以 size=0.9 为基准（对应 6000 格）：size 越大云越小，越小云越大。
    // 2026-10-18 实验：6000 → 3200。
    // 256³ 体积在 6000 格周期下是 23 格/体素，鼓包（~200 格）只有 9 体素 ⇒ 糊成块。
    // 3200 格 ⇒ 12.5 格/体素，鼓包 16 体素，才够表达"菜花状"。
    // 代价：云变小一半、平铺更近（视距 25000 内约 8 次重复，可接受）。
    float basePeriod = 3200.0 * 0.9 / max(cfg.baseScale, 0.05);
    // 2026-10-18：**y 方向单独拉高频**。
    // 层厚 1000 时，y 只跨 1000/1440 = 0.7 个噪声周期 ⇒ 竖直方向几乎没有层次，
    // 云被画成一张"扁盘"。真实积云在竖直方向的层次反而比水平更强（底平顶鼓），
    // 这里给 y 加 2.2 倍频率，云团就立起来了。
    vec4 n = texture(shapeSampler,
        vec3(localPos.x, localPos.y * 2.2, localPos.z) / basePeriod);
    float fbm = n.g * 0.625 + n.b * 0.25 + n.a * 0.125;
    float base = bevyRemap(n.r, -(1.0 - fbm), 1.0);

    // ② weather map：图集 R 通道当覆盖率场（水平构图仍由图集决定，
 // 3D 体积只负责"云长什么样"，这正是分工）。
    // ===== 2026-10-18 关键修正：覆盖度改用 **3D** 低频，不再用 2D 图集 =====
    // 诊断图（cloud_debug.flag=1）证明：云"有/无"的边界在屏幕上是以视角为中心的
    // 三角/扇形 —— 这正是把 2D 平面图（天气图）投影到天空球的结果。
    // 只要"哪里有云"由 2D 决定，仰视就必然是扇形，无论数值怎么调。
    // 现在用 shape 体积在**更大周期**（18000 格）上的采样做天气图：3D、各向同性，
    // 投影到天空不会产生方向性图案；2D 图集只留 10% 的轻微调制。
    float weather3D = texture(shapeSampler, localPos / max(cfg.weatherPeriod, 1000.0)).r;
    weather3D = smoothstep(0.15, 0.75, weather3D);
    vec2 auv = localPos.xz * (0.00005 * cfg.baseScale);
    float weather2D = clamp(texture(atlasTex, auv).r, 0.0, 1.0);
    float weather = mix(weather3D, weather2D, 0.10);
    // 2026-10-18：删掉自创的 hWarp（噪声扰动高度场）。
    // 它叠加在 height gradient 上，会把「云型」滑条的效果盖住 —— 这正是
    // "怎么调都几乎不动"的原因之一。云底起伏留给云型梯度本身表达。
    float grad = bevyDensityHeightGradient(h, cfg.cloudType);
    // 2026-10-18：**去掉 ×4.2 这个魔数**。
    // 图集 R 的现实分布约 0.25~0.75，原来乘 4.2 后 0.5 就饱和到 1 —— 图集上一点灰度差
    // 被放大成"有云/没云"的硬边界，仰视投影后就是天上那些黑白大扇形。
    // 现在先把 R 归一化到 0~1（按实测区间），再直接乘滑条：
 // coverage 的语义回到原义 = 被云遮挡的天空比例。
    weather = smoothstep(0.20, 0.90, weather);
    // 2026-10-18 改回纯乘：上一版加了 0.35 的地板（cov*(0.35+0.65w)），
    // 本意是"处处有云、只是疏密不同"，但连晴天也填满了云 —— 叠加层厚 2100 后
    // 光学厚度 ≈71，每一像素都是均匀的饱和云（诊断图 R/G 全满、无任何形状）。
    // 现在天气图真的能开出蓝天；云量滑条 = 被遮挡天空比例。
    float coverage = clamp(cfg.coverage * weather, 0.0, 1.0);
    // 覆盖率趋零时直接判空：remap 的分母就是 coverage，不挡住会出 NaN。
    if(coverage < 1.0e-4)
    {
        return 0.0;
    }
    // 早退**试过，没用**（2026-10-18）：加了 base*grad <= 1-cov 的提前 return，
    // 帧率 45.4 → 42.3，反而更慢 —— GPU 上 warp 内"有云/没云"像素并存时分支发散，
    // 省下的那次纹理采样抵不过发散代价。所以照常往下算。
    base = bevyRemap(base * grad, 1.0 - coverage, 1.0) * coverage;

    // ③ detail：worley 侵蚀（参考幅度 0.4*h，且随高度翻转一次）
    float hfbm = bevyCloudMapDetail(pos, cfg, worleyTex);
    hfbm = mix(hfbm, 1.0 - hfbm, clamp(h * 4.0, 0.0, 1.0));
    // worley 纹理的值域其实只用到 0.08~0.59（--probe 实测），直接拿来做 remap 的下限
    // 等于"永远只减掉一小截"，所以幅度怎么调都只是让云整体缩一圈。
    // 先把它拉满到 0~1，侵蚀才是"局部啃掉团块"而不是"整体缩小"。
    hfbm = smoothstep(0.15, 0.60, hfbm);
    // 幅度：0.4（参考原值）看不见，1.6 会把云打碎成孤岛，取 1.2。
    base = bevyRemap(base, hfbm * 1.7 * cfg.erosionScale * h, 1.0);
    // 只 remap 的话，detail 仅作用在"阈值附近的极窄过渡带"，云内部 base≈1 完全不受影响，
    // 所以频率调到 80 也看不出毛边。这里再让它**调制整个体积的密度**，
    // 云内部才会出现镂空/絮状（参考照片的云缘就是这么来的）。
    // 2026-10-18：hfbm 拉满后均值 0.6~0.8，`1-hfbm` 才是"侵蚀量" ——
    // 之前用 0.15+0.85*hfbm 只把密度降了 10%，所以云缘毫无变化（实测两版图像几乎一样）。
    // 现在按 (1-hfbm) 做乘性挖空，云里才会出现丝状/镂空。
    base *= mix(1.0, 1.0 - hfbm, 0.85);

    // ④ 收尾幂次：底部更紧实、顶部更松（参考 (1-h)*0.8 + 0.5）
    base = pow(clamp(base, 0.0, 1.0), (1.0 - h) * 0.8 + 0.5);

    return clamp(base * cfg.density, 0.0, 1.0);
#endif  // 死代码结束
}


float bevyNormalizedHeight(vec3 pos, BevyCloudConfig cfg)
{
    float h = cfg.topHeight - cfg.bottomHeight;
    return (length(pos) - (cfg.planetRadius + cfg.bottomHeight)) / h;
}

// clouds_compute.wgsl:117-136
//
// 2026-10-22 重写：等距步进 + 射程钳制 + cheap 密度。参考
// jiaozi158/UnityVolumetricCloudsURP（MIT, 2024）VolumetricCloudsUtilities.hlsl:500-547
// 的 EvaluateSunTransmittance。与旧版的差别，逐条列清楚以便归因：
//   1) 旧版步长按 1.3^i 倍增，12 步后单步 ~120 格、总射程 ~770 格 —— 远端那几步已经
//      整块跨过云体，等于白采。参考的做法是先在云层顶求出射程，再**等距**切分。
//   2) 射程钳到 shadowSteps × shadowStepSize（与参考 LIGHT_STEP_MAXIMAL_SIZE 同义），
//      层特别厚时步长不至于粗到漏掉整个云块。
//   3) 参考把 opticalDepth 累加完只做**一次** exp（538 行），旧版每一步一次 exp。
//   4) 密度走 cheapVersion（参考 531 行同样传 cheapVersion=true）：跳过 detail 侵蚀。
// 有意保留的差异：参考的 noiseMipOffset 靠纹理 mip 实现"越远越糊"，我们是程序化噪声，
// 没有 mip，所以这一条不搬（硬搬就是自造）。
float bevyVolumetricShadow(vec3 origin, BevyCloudConfig cfg,
    sampler2D atlasTex, vec2 atlasSize, sampler3D baseTex, sampler3D worleyTex, sampler3D shapeSampler)
{
    const int steps = max(cfg.shadowSteps, 1);
    const float stepCount = float(steps);

    // 沿太阳方向穿出云层顶的距离：origin 在壳内 ⇒ c0 < 0 ⇒ 远交点 = -b + sqrt(d)。
    // 与 bevyIntersectShell 同一套稳定写法，避免 float32 相消（§4 #11 踩过）。
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
        // 参考的 0.25 偏置：第一步不取整步，低采样数时云底更沉一点（参考 523-525 行）。
        const vec3 pos = origin + cfg.sunDir * (intervalSize * (0.25 + float(j)));
        const float h = bevyNormalizedHeight(pos, cfg);
        if(h > 1.0) break;
        const float density = bevyGetDensity(pos, h, cfg, atlasTex, atlasSize,
            baseTex, worleyTex, shapeSampler, true);
        opticalDepth += density;
    }

    // σt = 密度 × σtBase（与主 march 同一条），等距所以可以最后统一乘步长。
    const float tau = opticalDepth * cfg.density * intervalSize;
    return exp(-tau);
}

// clouds_compute.wgsl:138-146
float bevyIntersectSphere(float rayDirY, float sampleRadius, float planetRadius)
{
    // 兼容旧签名（未使用）：按"原点在海平面"的近似
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

// ---------------------------------------------------------------------------
// 2026-10-18 物理补项（参考库里没有，属 Hillaire/UE 的常规近似）：
//   1) 单次散射反照率 albedo ≈ 0.98：把"消光"和"散射"解耦，厚云不再发死黑
//   2) 多次散射：3 个 octave，每个 octave 的相位更各向同性、能量按 0.5 衰减，
//      衰减项用 exp(-σ·k) 近似"再弹射一次要穿过的光学厚度"
//   3) powder：逆光时云缘压暗（视线接近与阳光反向）
// 成本：每个采样点多 3 次 HG（各 1 次 pow），相对于 8 步阴影 march 可忽略。
// ---------------------------------------------------------------------------
float bevyHG(float rayDotSun, float g);   // 前向声明（定义在下方）

float bevyMultiScatter(float sigma, float cosTheta, float g)
{
    float total = 0.0;
    float amp = 1.0;
    float attenuation = 1.0;
    for(int octave = 0; octave < 3; ++octave)
    {
        // 每个 octave 的相位更各向同性 ⇒ g 要**减小**，且必须留在 (-1,1) 内。
        // 2026-10-18 修：原来写的是 g*(1+0.55*octave)，g=0.6 时第 2/3 层变成 1.26/1.74，
        // HG 在 g>1 时 (1-g²)<0、分母恒正 ⇒ 相位变成负值，等于从云里"减光"。
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

// clouds_compute.wgsl:175-228 —— 主 march。返回 vec4(散射, 透射率)，dist = 首个命中
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
    // inside 以"原点是否在壳内"表达：起点 < 0 < 终点 ⇒ 相机就在两层之间
    float inside = 0.0;

    // 统一处理"相机在层内 / 层外"两种情形：区间取两个交点之间、并裁掉负值。
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

    // 细节 LOD（2026-10-18，自创但是采样定理的直接推论）：
    // 侵蚀噪声的空间周期 = 1/(0.0016*baseScale*detailScale)，必须 ≥ ~3.5 个步长。
    // 否则沿射线采样每次都落在噪声的同一相位附近，整格跳变，画面表现为纵向条纹
    // （层厚 1296 + detailScale 24 时周期只有 29 格、步长 19 格，实测就是一片梳齿）。
    // 按周期约束反解：detailScale ≤ 178.6 / (baseScale * stepDistance)。
    // 量化成 1/25 台阶：如果让 detailScale 逐像素连续变化，相邻像素的噪声频率不同，
    // 屏幕上会打出同心环状摩尔纹（实测非常明显）。量化后只剩少数几档，环消失。
    // 常数 178.6 是旧侵蚀公式时代的；bevyCloudMapDetail 里多了一个 `/32`（纹理坐标），
    // 正确反解是 32 × 178.6 ≈ 5714：
    //   周期 = 32 / (0.0016*baseScale*detailScale) ≥ 3.5*stepDistance
    //   ⇒ detailScale ≤ 5714 / (baseScale*stepDistance)
    // 用旧常数会把 detailScale 从 24/80 硬钳到 6（周期 3.7 km），侵蚀退化成"整体缩小"，
    // 这就是"怎么调 frequency 都没细节"的真正原因。
    float detailLod = 5714.0 / max(cfg.baseScale * stepDistance, 1.0e-3);
    cfg.detailScale = min(cfg.detailScale, max(1.0, floor(detailLod * 2.0) * 0.5));

    float hashedOffset = bevyHash13(rayDir + fract(cfg.time));
    float dirLength = start - stepDistance * hashedOffset;

    // 相位函数的角约定（2026-10-18 修）：
    // 参考写的是 dot(ray_dir, -sun_dir)，但参考的 sun_dir 是**光线传播方向**（从太阳射向
    // 场景，bevy DirectionalLight 的 -Z）。我们的 cfg.sunDir 是**指向太阳**（体积阴影
    // march 必须这么用）。沿用参考的写法等于把前向散射接到反方向 —— 朝太阳看反而变暗。
    // 正确：光从 -sunDir 射来，云→相机的方向是 -rayDir，前向散射要求两者接近
    //   ⇒ -rayDir ≈ -sunDir ⇒ rayDir ≈ sunDir ⇒ cosθ = dot(rayDir, sunDir)。
    float rayDotSun = dot(rayDir, cfg.sunDir);
    // 2026-10-19 双 lobe 精调：后向 lobe 的权重压到 0.85 倍。
    // 原来 1:1 混合，后向分量把前向散射（朝太阳时该最亮）冲淡了，
    // 云在逆光/侧光下会整体发灰。压到 0.42 左右，受光面更集中、暗部更沉。
    // 注意：这一项按射线算一次（不随步长变），放在循环外，零额外成本。
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
        // 主 march 走完整密度（含 detail 侵蚀）；cheapVersion 只给光线步进用。
        float density = bevyGetDensity(worldPosition, h, cfg, atlasTex, atlasSize, baseTex, worleyTex, shapeSampler, false);

        if(density > 0.0)
        {
            dist = min(dist, dirLength);

            // 原来恒为 0.98，云里外一样白，所以没有体积感。
            // 低密度（云缘）偏暖白，高密度（云心/云底）压成灰蓝。
            float albedoDensity = clamp(density * 1.8, 0.0, 1.0);
            vec3 albedo3 = mix(vec3(1.00, 0.97, 0.90), vec3(0.42, 0.48, 0.56), albedoDensity);
            const float albedo = 0.98;
            float shadow = bevyVolumetricShadow(worldPosition, cfg, atlasTex, atlasSize, baseTex,
                worleyTex, shapeSampler);
 // 2026-10-19 **多散射近似**。
            // 单次散射下，厚云的云底/云内会死黑（只有 ambient 兜底），
            // 真实云里光在云滴间反复弹射，所以深处是"灰蓝发光"而不是黑。
            // 经典做法：把透过率 T 用几档更慢衰减的指数加权求和 ——
            //   T + 0.6T^0.5 + 0.36T^0.25 + 0.22T^0.125（归一化后）
            // T^0.5 就是 sqrt，T^0.25 就是 sqrt(sqrt)，全部是零成本指令。
            float msShadow = 0.47 * shadow
                + 0.28 * sqrt(shadow)
                + 0.16 * sqrt(sqrt(shadow))
                + 0.09 * sqrt(sqrt(sqrt(shadow)));
            // 保留一点对比（单散射部分），再叠加多散射的"灯光"：
            shadow = mix(shadow * shadow, msShadow, 0.50);
            // 2026-10-18：环境光也受遮挡调制。
            // 原来 ambient 无条件全额累加，等于给暗部设了一个亮地板 —— 云内部再深也亮。
            // 这是"没有暗面"的直接原因（实测：加阴影步数 8→16 完全没变化，shadow 分布正常）。
            // 2026-10-19 试过并回退：向上 ambient trace（6 步）。
            // 实测成本 **62.9 → 42.8 fps（-32%）**，而画面与不加时几乎逐像素相同。
            // 原因是当前层只有 350 格：向上走 6 步就穿出层顶，skyOd 恒接近 0 ⇒ skyVis≈1，
            // 等于白算。这条路只有在厚层（>1000 格）时才有意义，薄层下不划算。
            // 2026-10-19 **天光乘天空可见度**（这一行之前漏掉了，是"云永远不发黑"的根因）。
            // 旧写法 (0.12 + 0.88*shadow) 的下限 0.12 意味着云最深处也有 12% 天光，
            // 而天光系数 0.11 相当可观 ⇒ 云内被抬到 0.6+，自阴影再强也压不下去。
            // 现在下限降到 0.03，深处几乎只剩"多次散射的灰蓝"，暗部才立得住。
            float skyVis = 0.03 + 0.97 * clamp(shadow, 0.0, 1.0);
            vec3 ambient = mix(cfg.ambientBottom, cfg.ambientTop, h) * skyVis;
            // 单次 + 多次散射的合并相位
            float phaseTotal = scattering + bevyMultiScatter(density, rayDotSun, cfg.forwardG);
            // powder：**逆光**（朝太阳看）时云缘压暗。符号同样跟着上面的相位约定修正：
            // 朝太阳看 = dot(rayDir, sunDir) > 0（原来是 -dot，等于把 powder 接到了顺光侧）。
            // Hillaire：消光系数 σt = 密度 × σtBase（后面光照积分要用，先算）
            float sigmaT = density * cfg.density;
            // Hillaire 的 powder 用**消光**而不是裸密度：
            // 它模拟的是"云缘薄处的光在内部多次弹射后从背面透出"，
            // 物理上取决于该点的光学厚度 σt·ds，用 density 会随层厚标定漂移。
            float powder = 1.0 - exp(-sigmaT * 60.0);
            float powderWeight = clamp(dot(rayDir, cfg.sunDir), 0.0, 1.0);
            float powderTerm = mix(1.0, 0.4 + 0.6 * powder, powderWeight);
            // 2026-10-18：**竖向光照权重**（自创，但这是体积云"有体积"的关键）。
            // 散射项原来只跟 dot(rayDir, sunDir) 有关 ⇒ 云底和云顶一样亮，
            // 仰视时整片是均匀的"雪地"。真实积云光从上方来，云底明显更暗。
            // 这里按归一化高度给 0.35→1.0 的权重，底面压暗、顶面全亮。
            // 2026-10-19 **按太阳高度反转竖向光照**：
            //   · 白天太阳高 → 光从上往下 ⇒ 云底暗、云顶亮（原行为 0.45→1.0）
            //   · 夕阳太阳低 → 光近乎水平、且会被下方云海/大气反照 ⇒
            //     **云底反而被照亮**（照片里云底是金橙色，不是黑的）
            // 用 sunDir.y 判断太阳高度，lowSun=1 表示贴地平线。
            float sunElev = clamp(cfg.sunDir.y, 0.0, 1.0);
            float lowSun = 1.0 - smoothstep(0.04, 0.35, sunElev);
            // 2026-10-19 **修正方向**：夕阳时云底应该**更暗**，不是更亮。
            // 之前写 mix(0.45, 0.95, lowSun) 是想模拟"云底被云海反照"，
            // 但夕阳是逆光：朝向相机的是**背光面**，反照光远不足以抵消。
            // 现在 lowSun → 0.12，夕阳时云底直接被压暗；
            // 另外叠一层"逆光压暗"：正对太阳看时看到的是背光面，再多压一档。
            float bottomWeight = mix(0.45, 0.12, lowSun);
            float verticalLight = mix(bottomWeight, 1.0, smoothstep(0.0, 0.55, h));
            float backlit = clamp(rayDotSun, 0.0, 1.0);
            verticalLight *= mix(1.0, 1.0 - 0.55 * backlit, lowSun);
            // ===== 2026-10-19 **Hillaire 能量守恒形式** =====
            // 参考：Hillaire, "Physically Based Sky, Atmosphere and Cloud Rendering
            //       in Frostbite"（SIGGRAPH 2016）+ EGSR 2020 的多散射近似。
            // 原来是把 (ambient + 太阳项) 直接乘 density，缺了"散射系数 = 消光 × 反照率"
            // 这一层，所以实心云的亮度靠 ambient 硬撑、暗部不物理。
            // 现在：
            //   σt = density（消光，每格）      σs = σt × albedo（散射）
            //   单次散射 L1 = 太阳 × 相函数 × 向阳透过率 × powder
            //   多次散射 Lms = L1 × f/(1-f)（等比级数求和，f = 有效反照率×平均相函数）
            //   S = σs × (ambient + L1 + Lms)
            vec3 Lsingle = cfg.sunColor * phaseTotal * shadow * powderTerm;
            // ===== Hillaire 的**多次散射 octave 叠加**（SIGGRAPH 2016 §5.3）=====
            // 每一阶的消光减半 ⇒ 透过率 = T^(0.5^i)，权重也减半（a = b = 0.5^i）。
            // T^(0.5) = sqrt(T)，T^(0.25) = sqrt(sqrt(T))，全是零成本指令。
            // 之前用的 f/(1-f) 单一等比级数只有"整体提亮"的效果，
            // 没有"不同深度层看到不同散射次数"的层次。
            float T1 = clamp(shadow, 0.0, 1.0);
            float T2 = sqrt(T1);
            float T4 = sqrt(T2);
            // Hillaire 的**精确权重**：a = b = 0.5^i ⇒ 0.5 / 0.25 / 0.125。
            // 之前写成 0.5/0.3/0.2（权重和 1.0），暗处算出来反而比单次散射还大
            // （shadow=0.12 时 msOctave≈0.28 > T1=0.12）—— 这就是云内被提亮到白的原因。
            float msOctave = 0.500 * T1 + 0.250 * T2 + 0.125 * T4;
            // 强度由「多次散射强度」滑条控制（cfg.sunGlow 复用，出厂 0.45）
            vec3 Lms = cfg.sunColor * phaseTotal * powderTerm * msOctave * cfg.sunGlow;
            // ===== 2026-10-19 **天光乘天空可见度**（决定性的一项）=====
            // 原来 ambient 无条件全额加进散射 ⇒ 云底也有满满的天光 ⇒ 永远不发黑。
            // 参考照片里云底是深棕/近黑，正是因为它只看得到很少的天空。
            // 用向阳透过率 shadow 作为"上方遮挡"的近似：
            //   shadow 小（深处）⇒ skyVis 低 ⇒ 天光被压掉；
            //   shadow 大（云缘/云顶）⇒ skyVis ≈ 1，天光全额。
            // 注：skyVis 已经在上面算 ambient 时乘过，这里不能再乘（会重复压暗）
            vec3 S = sigmaT * albedo * (ambient + Lsingle + Lms) * verticalLight * albedo3;
            // ===== 2026-10-19 **逆光银边**（重新加回，带强度滑条）=====
            // 逆光时云缘（低密度处）被穿透的阳光点亮。rayDotSun^8 是窄峰，
            // 只作用于正对太阳的方向，所以不会把整体提亮。
            if(cfg.silver > 0.001)
            {
                float glow = max(rayDotSun, 0.0);
                glow *= glow; glow *= glow; glow *= glow;   // ^8（四次乘法，不用 pow）
                float edgeW = 1.0 - smoothstep(0.0, 0.45, density);
                S += cfg.sunColor * (glow * edgeW * cfg.silver * albedo3);
            }
            // 2026-10-19 **退役「逆光银边」**。
            // 它是早期为"云看着有厚度"加的非物理项（rayDotSun^8 × 边缘权重）。
            // 在 Hillaire 模型下，相函数的前向散射已经表达"逆光边缘亮"，
            // 再叠一层等于重复加亮，把暗部冲掉了（这是云偏白、缺暗部的直接原因之一）。
            // cfg.sunGlow 字段保留（存档兼容），但不再参与计算。
            // 2026-10-19 **夕阳暖色调**：太阳贴近地平线时，云体（尤其背光面）
            // 应该被染成金橙色而不是过曝纯白 —— 这是参考照片里最显著的特征。
            // lowSun 在"中性白"和"金橙"之间插值，只改色相、不改亮度。
            vec3 sunsetTint = mix(vec3(1.0), vec3(1.0, 0.68, 0.34), lowSun * 0.85);
            S *= sunsetTint;
            float deltaT = exp(-sigmaT * stepDistance);
            // 能量守恒积分：∫σs·L·T ds，sigmaS→0 时退化为 L·ds
            scatteredLight += transmittance * S * (1.0 - deltaT) / max(sigmaT * albedo, 1.0e-5);
            transmittance *= deltaT;
        }

        if(transmittance <= cfg.minTransmittance) break;
        // 2026-10-18：自适应步长试过，**帧率没有提升**（17 → 17），已回退。
        // 原因是它在同样命中率的条件下只是把远处采样间距拉大，
        // 而循环退出仍由 minTransmittance 决定 —— 总步数没变。
        dirLength += stepDistance;
    }

    // ===== 2026-10-19 **远处淡出** =====
    // 原来只有 viewDistance 硬截断（超过就不画），远处会有一条突兀的"切边"。
    // 现在从 fadeStart×viewDistance 到 viewDistance 之间线性淡出：
    // 云逐渐变透明而不是突然消失。
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

// coord: 0..1 的三维坐标（生成时用 voxel / size）
// ---------------------------------------------------------------------------
// 3D base map 生成器（2026-10-18）：替代参考库的 2D 俯视云图。
// 三个通道对应原图集的 R/G/B（覆盖度底色 / 阈值扰动 / 高度因子），但都由 3D 场给出，
// 所以从云层下方仰视时不会再出现"水平结构被投影成竖条"。
// 两个不同频率/偏移的 Perlin-Worley 叠加：低频给大团块，另一个给它阈值扰动，
// 避免所有云都有同一个"硬度"。
// ---------------------------------------------------------------------------
vec4 bevyShapeVolume(vec3 coord);   // 前向声明（定义在下方）

vec4 bevyRenderBase3D(vec3 coord)
{
    // 十轮实测的结论：覆盖度必须是 **3D 格胞场**。
    //   · 2D 图集  → 只随 XZ 变 ⇒ 仰视竖向拖笔（调制权重 w=0 时实测依然存在）
    //   · Perlin 3D → 连续场「到处都有一点」⇒ 切出连续云盖、边缘碎成毛丝
    //   · 3D tilable voronoi → 格胞边界锐利（离散云团）+ Y 轴也有格胞（无拖笔）
    // 这正是 2D 图集能出「离散云团」的同一个算子（bevyRenderAtlas 里用的就是它），
    // 只是把维度从 2D 换成 3D。
    float v = bevyTilableVoronoi(coord, 3, 2.0);        // 胞径 ~1430 格（照片里一朵云的量级）
    // v 的分布在 0.22..0.99（均值 0.72，胞界占多数），所以把过渡带压在**中位数附近**，
    // 这样 cover 才会在中位值附近均匀铺满 0..1，覆盖度滑条才有行程。
    float cover = 1.0 - smoothstep(0.62, 0.80, v);
    return vec4(cover, 0.5, 0.5, 1.0);
}

vec4 bevyShapeVolume(vec3 coord)
{
    vec3 p = coord * 4.0;
    float perlin = 0.0;
    float amp = 1.0;
    float total = 0.0;
    // 2026-10-18：octave 4 → 3。
    // 128³ 体积在 6000 格周期下只有 47 格/体素，而第 4 档的特征是 6000/32 = 187 格
    // ≈ 4 个体素 ⇒ 严重欠采样，云的表面就变成"雪堆/方块"。去掉最细一档后
    // 最细特征 375 格 ≈ 8 体素，形状能立住。
    // 2026-10-22：3 → 5 octave **试过并回退**，如实记录数据：
    //   3 oct / 56 步  锐度 2.462（基准）
    //   5 oct / 56 步  锐度 3.192  ← 涨的全是竖条纹混叠伪影，不是细节
    //   5 oct / 96 步  锐度 2.116  ← 补足采样后比基准更低
    //   5 oct /160 步  锐度 1.806
    // 原因：第 5 层 period=64 ⇒ 世界特征 8000/64 = 125 格，而 march 步长 32 格，
    // 欠采样 4 倍，只能出条纹。补步数后伪影没了、细节也被平均没了。
    // 与 HANDOFF 第十五轮同一条结论："比步长更细的 octave 采不到，加了反而更平滑"。
    // 真正能让云变碎的是**侵蚀频率**（detailScale），不是 perlin octave。
    for(int octave = 0; octave < 3; ++octave)
    {
        float pf = 4.0 * float(1 << octave);
        perlin += amp * bevyPerlin3D(p * float(1 << octave), pf);
        total += amp;
        amp *= 0.5;
    }
    perlin /= total;

    float wfbm = clamp(bevyWorleyFBM3D(coord, 4.0), 0.02, 0.98);   // 防止除以 0 → NaN
    // Perlin-Worley：把 perlin 的值域 remap 到 [worley, 1]，得到"鼓包 + 撕裂"
    float perlinWorley = bevyRemap(perlin, 1.0 - wfbm, 1.0);
    perlinWorley = clamp(perlinWorley, 0.0, 1.0);

    return vec4(perlinWorley,
        bevyWorley3D(coord, 4.0),
        bevyWorley3D(coord, 8.0),
        bevyWorley3D(coord, 16.0));
}

#endif
