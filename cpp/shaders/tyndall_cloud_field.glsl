#ifndef TYNDALL_CLOUD_FIELD_GLSL
#define TYNDALL_CLOUD_FIELD_GLSL
#ifndef TYNDALL_PLANET_RADIUS
#define TYNDALL_PLANET_RADIUS 6360000.0
#endif
const float TYNDALL_RADIUS = TYNDALL_PLANET_RADIUS;
const float TYNDALL_MAX_DISTANCE = 42000.0;

vec3 tyndallRegionHash(vec3 cell)
{
    uvec3 h = uvec3(ivec3(cell));
    h = h * uvec3(1597334677u, 3812015801u, 2798796415u);
    uint seed = h.x ^ h.y ^ h.z;
    uvec3 v = seed ^ uvec3(2246822519u, 3266489917u, 668265263u);
    v ^= v >> 16u;
    v *= 2246822519u;
    v ^= v >> 13u;
    return vec3(v & 0x00ffffffu) / 16777216.0;
}

vec3 tyndallRegion(vec3 p)
{
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(tyndallRegionHash(cell), tyndallRegionHash(cell + vec3(1,0,0)), f.x),
            mix(tyndallRegionHash(cell + vec3(0,1,0)), tyndallRegionHash(cell + vec3(1,1,0)), f.x), f.y),
        mix(mix(tyndallRegionHash(cell + vec3(0,0,1)), tyndallRegionHash(cell + vec3(1,0,1)), f.x),
            mix(tyndallRegionHash(cell + vec3(0,1,1)), tyndallRegionHash(cell + vec3(1,1,1)), f.x), f.y), f.z);
}

float tyndallTime()
{
    return max(0.0, uintBitsToFloat(cam.padding));
}

vec3 tyndallWind()
{
    return vec3(0.81915204429, 0.0, 0.57357643635) *
        cam.cloudParams1.y * tyndallTime();
}

const float TYNDALL_RAIN_CHURN_SCALE = 0.12;

float tyndallEvolution()
{
    return cam.cloudParams1.z *
        mix(1.0, TYNDALL_RAIN_CHURN_SCALE, clamp(cam.weatherParams.x, 0.0, 1.0));
}

float tyndallHeight(vec3 p)
{
    vec2 horizontal = p.xz - cam.cloudParams3.xy;
    float vertical = p.y - cam.cloudParams0.x;
    float delta = dot(horizontal, horizontal) + vertical * (2.0 * TYNDALL_RADIUS + vertical);
    float altitude = delta / (sqrt(max(TYNDALL_RADIUS * TYNDALL_RADIUS + delta, 0.0)) + TYNDALL_RADIUS);
    return altitude / max(cam.cloudParams0.y, 1.0);
}

// 距离预过滤（2026-10-01）：把几何像素脚印换算成噪声体的 mip 级别。
// createCloudNoiseResources 现在给 128^3 形状体 / 64^3 卷曲体逐级 2x 盒式
// 降采样建了完整 mip 链，远处样本改读预过滤后的低分辨率层，把"点采样未过滤
// 体素"造成的方块边缘换成平滑软影（近处 footprint≈1 体素，LOD 0，不变）。
// footprint 单位=格；frequency 单位=texcoord/格；texResolution=体素边长。
// footprint<=0（反射 / 参考天空不传脚印）返回 0，保持旧档行为。
float tyndallNoiseLod(float footprint, float frequency, float texResolution)
{
    return clamp(log2(max(footprint * frequency * texResolution, 1.0)), 0.0, 16.0);
}

#ifndef CLOUD_WISPY_EDGE_WIDTH
#define CLOUD_WISPY_EDGE_WIDTH 0.35
#endif
#ifndef CLOUD_WISPY_TOP_HEIGHT
#define CLOUD_WISPY_TOP_HEIGHT 0.45
#endif

// ── 2026-10-07 降本：region 场的预计算 ───────────────────────────────────────
// tyndallRegion 每次要 8 个 tyndallRegionHash（每个 ~6 条 uvec3 ALU）+ 一层
// trilerp。而 region 只依赖 p.xz（y 分量是写死的常量 0.37 / 0.61），周期 =
// 1/0.00012 ≈ 8333 格 / size。自阴影光 march 的四步沿太阳方向只挪 24~96 格
// ⇒ 这四步的 region 相差 < 1.2% 个周期，肉眼与逐帧抖动都看不出。
// 所以把 region 提到循环外只算一次：每命中样本省 ~8 次 region（≈400 ALU）。
// 视图 march 仍走原来的 tyndallDensity（内部照旧即时算 region），逐位不变。
struct TyndallRegions
{
    vec3 region;
    vec3 regionLarge;
};

TyndallRegions tyndallRegionsFor(vec3 p)
{
    vec3 advected = p + tyndallWind();
    float size = clamp(cam.cloudParams3.z, 0.50, 3.0);
    TyndallRegions r;
    r.region = tyndallRegion(vec3(advected.x * 0.00012 / size, 0.37, advected.z * 0.00012 / size));
    r.regionLarge = vec3(0.0);
    if(clamp(cam.cloudParams6.w, 0.0, 2.0) > 0.001)
    {
        r.regionLarge = tyndallRegion(
            vec3(advected.x * 0.000041 / size, 0.61, advected.z * 0.000041 / size));
    }
    return r;
}

// tyndallDensity 的本体：region 由调用方给。直接调用者请用下面的包装。
float tyndallDensityFromRegions(vec3 p, bool cheap, float footprint, TyndallRegions regions)
{
    if(cam.cloudParams1.w < 0.5 || abs(cam.weatherParams.z - 1.0) >= 0.25)
        return 0.0;
    float h = tyndallHeight(p);
    if(h <= 0.0 || h >= 1.0) return 0.0;

    vec3 wind = tyndallWind();
    vec3 advected = p + wind;
    float size = clamp(cam.cloudParams3.z, 0.50, 3.0);
    float distribution = clamp(cam.cloudParams3.w, 0.0, 1.0);
    vec3 region = regions.region;

    vec2 detileWarp = vec2(0.0);
    float detileStrength = clamp(cam.cloudParams6.w, 0.0, 2.0);
    if(detileStrength > 0.001)
    {
        vec3 regionLarge = regions.regionLarge;
        detileWarp = detileStrength * (
            (region.xy - 0.5) * (3200.0 * size) +
            (regionLarge.xy - 0.5) * (8200.0 * size));
    }

    float baseNoise = textureLod(cloudShapeNoise,
        vec3((advected.xz + detileWarp) * (0.0000525 / size), 0.37),
        tyndallNoiseLod(footprint, 0.0000525 / size, 128.0)).r;
    float localBase = baseNoise * min(0.125, 260.0 / max(cam.cloudParams0.y, 1.0));
    h = (h - localBase) / max(1.0 - localBase, 0.01);
    if(h <= 0.0 || h >= 1.0) return 0.0;

    if(cam.cloudParams6.z > 0.001)
    {
        float stretchCover = mix(cam.cloudParams0.z,
            min(cam.cloudParams0.z + 0.30, 0.98),
            clamp(cam.weatherParams.x, 0.0, 1.0));
        float s = 1.0 + cam.cloudParams6.z * 2.0 * stretchCover;
        h = h / (1.0 + (s - 1.0) * h);
    }

    vec3 q = (advected + vec3(0.0, -tyndallTime() * tyndallEvolution() * 22.0, 0.0)) * (0.000105 / size);
    q += (region - 0.5) * 0.65;
    const mat3 rotation = mat3(0.0, 0.8, 0.6, -0.8, 0.36, -0.48, -0.6, -0.48, 0.64);
    vec2 a = textureLod(cloudShapeNoise, q, tyndallNoiseLod(footprint, 0.000105 / size, 128.0)).rg;
    vec2 b = textureLod(cloudShapeNoise, rotation * q * 1.37 + 0.173,
        tyndallNoiseLod(footprint, 0.000105 * 1.37 / size, 128.0)).rg;
    float shape = mix(dot(a, vec2(0.85, 0.15)), dot(b, vec2(0.85, 0.15)), 0.25);
    shape = mix(shape, smoothstep(0.22, 0.78, shape), 0.45);

#ifndef CLOUD_BOTTOM_SMOOTH_HEIGHT
#define CLOUD_BOTTOM_SMOOTH_HEIGHT 0.22
#endif
#ifndef CLOUD_BOTTOM_SMOOTH_POW
#define CLOUD_BOTTOM_SMOOTH_POW 1.0
#endif
    float bottomSmoothHeight = cam.cloudShapeParams.x >= 0.0
        ? cam.cloudShapeParams.x : CLOUD_BOTTOM_SMOOTH_HEIGHT;
    float bottomSmoothPow = cam.cloudShapeParams.y > 0.0
        ? cam.cloudShapeParams.y : CLOUD_BOTTOM_SMOOTH_POW;
    float bottomBlend = 0.0;
    if(bottomSmoothHeight > 0.0)
    {
        bottomBlend = pow(clamp(1.0 - h / bottomSmoothHeight, 0.0, 1.0),
            max(bottomSmoothPow, 1.0e-3));
    }
    shape = mix(shape, 1.0, bottomBlend);
    float profileType = clamp(cam.cloudProfileParams.x +
        (region.x - 0.5) * clamp(cam.cloudProfileParams.y, 0.0, 1.0), 0.0, 1.0);
    bool profileLowerHalf = profileType < 0.5;
    float profileTypeT = profileLowerHalf ? profileType * 2.0 : (profileType - 0.5) * 2.0;
    float profileTypeLo = profileLowerHalf
        ? mix(1.00, 1.40, profileTypeT) : mix(1.40, 0.60, profileTypeT);
    float profileTypeHi = profileLowerHalf
        ? mix(1.00, 0.80, profileTypeT) : mix(0.80, 1.23, profileTypeT);
    float profileTypeCurve = profileLowerHalf
        ? mix(1.00, 0.85, profileTypeT) : mix(0.85, 0.70, profileTypeT);
    float profile = clamp(h / (clamp(cam.cloudParams4.x, 0.03, 0.30) * profileTypeLo),
        0.0, 1.0) * clamp((1.0 - h) / (0.75 * profileTypeHi), 0.0, 1.0);
#ifndef CLOUD_PROFILE_CURVE_ON
#define CLOUD_PROFILE_CURVE_ON 1
#endif
#ifndef CLOUD_PROFILE_CURVE
#define CLOUD_PROFILE_CURVE 1.0
#endif
#if CLOUD_PROFILE_CURVE_ON
    float profileCurve = cam.cloudShape2Params.z > 0.0
        ? cam.cloudShape2Params.z : CLOUD_PROFILE_CURVE;
    profile = pow(clamp(profile, 0.0, 1.0),
        max(profileCurve * profileTypeCurve, 1.0e-3));
#endif
    float rain = clamp(cam.weatherParams.x, 0.0, 1.0);
    float coverage = mix(cam.cloudParams0.z, min(cam.cloudParams0.z + 0.30, 0.98), rain);
    coverage = clamp(coverage + (region.x - 0.5) * distribution * 0.60, 0.02, 0.95);
    float density = shape * profile - (1.0 - coverage);
    if(density <= 0.0) return 0.0;

#ifndef CLOUD_COVERAGE_REMAP
#define CLOUD_COVERAGE_REMAP 1
#endif
#if CLOUD_COVERAGE_REMAP
    density = density / max(coverage, 1.0e-3);
#endif

    if(!cheap)
    {
#ifndef CLOUD_DETAIL_RANGE
#define CLOUD_DETAIL_RANGE 1
#endif
#if CLOUD_DETAIL_RANGE
        float detailWeight = 1.0 - smoothstep(60.0, 320.0, footprint);
#else
        float detailWeight = 1.0 - smoothstep(35.0, 180.0, footprint);
#endif
        float detail = 0.5;
        if(detailWeight > 0.01)
        {
            float detailScale = cam.cloudShape2Params.x > 0.0
                ? cam.cloudShape2Params.x : 1.0;
            float detailContrast = cam.cloudShape2Params.y > 0.0
                ? cam.cloudShape2Params.y : 1.0;
            float detailFreq = 0.000775 * detailScale;
            vec3 dq = (p + detileWarp.xyx * vec3(1.0, 0.0, 1.0) + wind * 2.5 +
                vec3(tyndallTime() * tyndallEvolution() * 35.0,
                tyndallTime() * tyndallEvolution() * 14.0, 0.0)) * detailFreq;
            vec3 curl = textureLod(cloudCurlNoise, dq * 0.08,
                tyndallNoiseLod(footprint, detailFreq * 0.08, 64.0)).xyz * 2.0 - 1.0;
            vec3 detailSample = textureLod(cloudShapeNoise, dq + curl * 0.08,
                tyndallNoiseLod(footprint, detailFreq, 128.0)).rba;
            detail = mix(0.5, detailSample.r, detailWeight);
            detail = clamp(0.5 + (detail - 0.5) * detailContrast, 0.0, 1.0);
            float wispyStrength = clamp(cam.cloudDetailParams.x, 0.0, 2.0);
            if(wispyStrength > 0.001)
            {
                float wispyEdgeWidth = cam.cloudDetailParams.y > 0.0
                    ? cam.cloudDetailParams.y : CLOUD_WISPY_EDGE_WIDTH;
                float wispyReach = clamp(cam.cloudDetailParams.z, 0.0, 1.0);
                float wispyTopHeight = cam.cloudDetailParams.w > 0.0
                    ? clamp(cam.cloudDetailParams.w, 0.05, 1.0) : CLOUD_WISPY_TOP_HEIGHT;
                float wispy = mix(detailSample.g, detailSample.b, clamp(h, 0.0, 1.0));
                float topWeight = smoothstep(wispyTopHeight * 0.5, wispyTopHeight, h);
                float edgeWeight = 1.0 - smoothstep(0.0,
                    max(wispyEdgeWidth, 1.0e-3), density);
                float wispyErosion = wispy * (1.0 + wispyReach);
                detail = mix(detail, wispyErosion, clamp(wispyStrength *
                    mix(edgeWeight, 1.0, topWeight), 0.0, 1.0));
            }
        }
#ifndef CLOUD_EROSION_REMAP
#define CLOUD_EROSION_REMAP 1
#endif
#ifndef CLOUD_EROSION_SCALE
#define CLOUD_EROSION_SCALE 1.0
#endif
#if CLOUD_EROSION_REMAP
        float erosion = clamp(detail * cam.cloudParams1.x * CLOUD_EROSION_SCALE *
            mix(0.75, 1.25, h) * (1.0 - bottomBlend), 0.0, 0.8);
        density = clamp((density - erosion) / max(1.0 - erosion, 1.0e-3),
            0.0, 1.0);
#else
        density -= detail * cam.cloudParams1.x * mix(0.75, 1.25, h) *
            (1.0 - clamp(density * 6.0, 0.0, 1.0)) * (1.0 - bottomBlend);
#endif
    }
    {
        const float kPi = 3.14159265;
        float covClear = clamp(cam.cloudParams0.z, 0.0, 0.95);
        float covRain = clamp(cam.cloudParams0.z + 0.30, 0.0, 0.98);
        float cov = mix(covClear, covRain, clamp(cam.weatherParams.x, 0.0, 1.0));
        float hh = clamp(h, 0.0, 1.0);
        cov *= (1.0 - hh * hh);
        float cloudBase = clamp(baseNoise - (0.65 - cov), 0.0, 1.0);
        float profile = (hh * kPi) * (hh * kPi) / (kPi * kPi);
        density = cloudBase * profile * 5.0;
    }

    return max(density, 0.0) * cam.cloudParams0.w;
}

// 原入口：region 即时算。早退刻意放在 region 之前 —— 视图 march 的绝大多数
// 调用是落在云层外的空样本探测，不该为一次 region 付费（与改动前逐位同序）。
float tyndallDensity(vec3 p, bool cheap, float footprint)
{
    if(cam.cloudParams1.w < 0.5 || abs(cam.weatherParams.z - 1.0) >= 0.25)
        return 0.0;
    float h = tyndallHeight(p);
    if(h <= 0.0 || h >= 1.0) return 0.0;
    return tyndallDensityFromRegions(p, cheap, footprint, tyndallRegionsFor(p));
}

bool tyndallSphereRoots(vec3 p, vec3 direction, float radius, out vec2 roots)
{
    vec2 xz = p.xz - cam.cloudParams3.xy;
    float y = p.y - cam.cloudParams0.x + (TYNDALL_RADIUS - radius);
    float b = dot(vec3(xz.x, y + radius, xz.y), direction);
    float c = dot(xz, xz) + y * (2.0 * radius + y);
    float discriminant = b * b - c;
    if(discriminant < 0.0) return false;
    float q = -b - (b >= 0.0 ? 1.0 : -1.0) * sqrt(discriminant);
    float other = abs(q) > 1.0e-5 ? c / q : -b;
    roots = vec2(min(q, other), max(q, other));
    return true;
}

bool tyndallInterval(vec3 origin, vec3 direction, out float entry, out float exitDistance)
{
    vec2 outer;
    if(!tyndallSphereRoots(origin, direction, TYNDALL_RADIUS + cam.cloudParams0.y, outer))
        return false;
    entry = max(outer.x, 0.0);
    exitDistance = min(outer.y, TYNDALL_MAX_DISTANCE);
    vec2 inner;
    if(tyndallSphereRoots(origin, direction, TYNDALL_RADIUS, inner) && inner.y > entry)
    {
        if(inner.x > entry) exitDistance = min(exitDistance, inner.x);
        else entry = max(entry, inner.y);
    }
    exitDistance = min(exitDistance, entry + 18000.0);
    return exitDistance > entry + 0.01;
}

#ifndef TYNDALL_FAR_DISTANCE
#define TYNDALL_FAR_DISTANCE 420000.0
#endif
bool tyndallIntervalFar(vec3 origin, vec3 direction, out float entry, out float exitDistance)
{
    vec2 outer;
    if(!tyndallSphereRoots(origin, direction, TYNDALL_RADIUS + cam.cloudParams0.y, outer))
        return false;
    entry = max(outer.x, 0.0);
    exitDistance = min(outer.y, TYNDALL_FAR_DISTANCE);
    vec2 inner;
    if(tyndallSphereRoots(origin, direction, TYNDALL_RADIUS, inner) && inner.y > entry)
    {
        if(inner.x > entry) exitDistance = min(exitDistance, inner.x);
        else entry = max(entry, inner.y);
    }
    return exitDistance > entry + 0.01;
}
#if 0
// A single planar intersection and two filtered noise reads, no volume march.
// cloudParams6 = enabled, distance above volume top, coverage, brightness.
vec4 sampleHighCloud(vec3 origin, vec3 direction, out float distance)
{
    distance = 1000000.0;
    if(cam.cloudParams6.x < 0.5 || cam.cloudParams6.z <= 0.0 ||
       abs(cam.weatherParams.z - 1.0) >= 0.25 || abs(direction.y) < 0.005)
        return vec4(0, 0, 0, 1);
    float planeHeight = cam.cloudParams0.x + cam.cloudParams0.y + cam.cloudParams6.y;
    float t = (planeHeight - origin.y) / direction.y;
    if(t <= 0.0 || t > 120000.0) return vec4(0, 0, 0, 1);
    vec2 p = (origin + direction * t + tyndallWind() * 0.6).xz;
    float a = textureLod(cloudShapeNoise, vec3(p * vec2(0.000035, 0.00009), 0.29), 0.0).r;
    float b = textureLod(cloudShapeNoise, vec3(p * 0.00014 + a * 0.1, 0.71), 0.0).r;
    float shape = a * 0.75 + b * 0.25;
    float threshold = mix(0.80, 0.15, cam.cloudParams6.z);
    float opacity = smoothstep(threshold, threshold + 0.18, shape) * 0.60;
    opacity *= 1.0 - smoothstep(70000.0, 120000.0, t);
    if(opacity < 0.001) return vec4(0, 0, 0, 1);
    distance = t;
    float elevation = sin(fract(cam.historyParams.w) * 6.2831853) * 0.819152;
    vec3 tint = mix(vec3(1.0, 0.66, 0.40), vec3(0.91, 0.95, 1.0),
                    smoothstep(0.0, 0.5, elevation));
    float daylight = mix(0.012, 1.0, smoothstep(-0.10, 0.15, elevation));
    float fire = (1.0 - smoothstep(0.04, 0.22, elevation)) * 1.8;
    vec3 color = (tint + vec3(1.0, 0.12, 0.015) * fire) * daylight * cam.cloudParams6.w *
                 mix(1.0, 0.55, clamp(cam.weatherParams.x, 0.0, 1.0));
    return vec4(color * opacity, 1.0 - opacity);
}
#endif

#ifndef C3_CLOUD_SHADOW_GATE_ON
#define C3_CLOUD_SHADOW_GATE_ON 1
#endif
const float TYNDALL_SHADOW_DENSITY_GATE = 0.45;

float tyndallShadowGate(float density)
{
#if C3_CLOUD_SHADOW_GATE_ON
    return max(density - TYNDALL_SHADOW_DENSITY_GATE, 0.0) /
        (1.0 - TYNDALL_SHADOW_DENSITY_GATE);
#else
    return density;
#endif
}
#endif
