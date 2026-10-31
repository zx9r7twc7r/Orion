#ifndef SUN_SHADOW_GLSL
#define SUN_SHADOW_GLSL

#ifndef SUN_SHADOW_Z_FADE
#define SUN_SHADOW_Z_FADE 1
#endif

const int SUN_SHADOW_RESOLUTION = 1024;
const float SUN_SHADOW_TEXEL = 1.0 / 1024.0;
const float SUN_SHADOW_CENTER_QUANTUM = 1.0 / 256.0;

const float SUN_SHADOW_EXTENT_XY = 160.0;

const float SUN_SHADOW_OUTSIDE_VISIBILITY = 0.08;
const float SUN_SHADOW_OUTSIDE_FADE_BLOCKS = 32.0;

#ifndef SUN_SHADOW_Z_EXTENT_BLOCKS
#define SUN_SHADOW_Z_EXTENT_BLOCKS 160.0
#endif
const float SUN_SHADOW_EXTENT_Z = SUN_SHADOW_Z_EXTENT_BLOCKS;

const float SUN_SHADOW_BIAS = 0.45;
const float SUN_SHADOW_BIAS_NEAR = 0.06;

const float SUN_SHADOW_ORIGIN_BIAS = 0.02;

const uint SUN_SHADOW_CULL_MASK = 0x04u;

// 光的方向：与两个体积 pass 的 sunDirection() 同一个公式（同一个
// cam.historyParams.w），并且同样在太阳落到地平线以下后翻到月亮方向。
//
// 这里用函数内常量而不是共享的 PI：各个着色器的 PI 声明位置不同，
// 引用全局 PI 会踩到"用到时还没声明"的坑。
vec3 sunShadowLightDirection()
{
    const float TWO_PI = 6.28318530718;
    float angle = fract(cam.historyParams.w) * TWO_PI;
    vec3 direction = normalize(vec3(cos(angle), 0.81915204429 * sin(angle),
        0.57357643635 * sin(angle)));
    return direction.y >= 0.0 ? direction : -direction;
}

// 正交盒中心：跟着相机，但量化到纹素尺寸以抑制移动时的"游泳"。
// 正交基随太阳缓慢转动，所以按世界轴量化不是严格的纹素对齐，
// 但足以压住绝大部分抖动。
//
// 参数化版本（2026-10-01）给**远场图**用：远场图的 extent / texel 由 CameraUBO
// 的 sunShadowFarParams 下发，不是编译期常量。近场那份（sunShadowCenter）保持
// 原样，行为逐字节不变。
vec3 sunShadowCenterFor(float extentXY, float texel)
{
    float texelWorld = 2.0 * extentXY * texel;
    vec3 cameraPosition = cam.viewInverse[3].xyz;
    return floor(cameraPosition / texelWorld) * texelWorld;
}

vec3 sunShadowCenter()
{
    return sunShadowCenterFor(SUN_SHADOW_EXTENT_XY, SUN_SHADOW_CENTER_QUANTUM);
}

// 太阳方向 → 正交基。生产者和消费者都调这一个，所以不会各算一套。
void sunShadowBasis(vec3 sunDirection, out vec3 basisRight, out vec3 basisUp)
{
    vec3 reference = abs(sunDirection.y) > 0.99
        ? vec3(1.0, 0.0, 0.0)
        : vec3(0.0, 1.0, 0.0);
    basisRight = normalize(cross(reference, sunDirection));
    basisUp = cross(sunDirection, basisRight);
}

// 生产者用：某个纹素对应的射线起点（盒的"上风侧"边界上）。
//
// =====================================================
// 2026-10-10 修正：起点必须落在**朝太阳**那一端，不是背太阳端
// =====================================================
// 这里原来是 `- sunDirection * SUN_SHADOW_EXTENT_Z`。sunDirection 是
// sunShadowLightDirection()，一个 y ≥ 0 的**朝太阳**单位向量，于是 "-" 把起点
// 放到了背太阳端（地下），射线朝太阳方向（向上）打。整套数学随之变成一个
// 镜像：stored 变成"从地下一路向上遇到的**最低**遮挡体距离"，而
// `depth < stored` 的含义就成了"采样点比最低遮挡体还低"—— 即**地下可见、
// 地面与空中的一切判为遮挡**。这正是 `rt_test.rgen` 里"远场图对表面采样点恒判
// 全遮挡"的根因（那次把它归给"远场图"并只停用了 rgen 的消费点，但同一份数学
// 也在近场图里，服务着体积光/雾）。
//
// 标准 shadow map 的做法是**从光源侧朝场景**打射线：起点在太阳端、方向朝下，
// stored = 沿太阳方向遇到的**最高**遮挡体距离，`depth < stored` = 采样点比最高
// 遮挡体更靠近太阳 = 中间没有东西挡着 = 受光。所以起点取 "+"。
// 配合消费者把 depth 改成从太阳端量起（见 sunShadowVisibilityIn），本文件回到
// 标准形式，体积光"穿墙"（背光侧被判可见）随之消失。
vec3 sunShadowRayOrigin(vec2 uv, vec3 sunDirection, vec3 center)
{
    vec3 basisRight;
    vec3 basisUp;
    sunShadowBasis(sunDirection, basisRight, basisUp);
    vec2 offset = (uv * 2.0 - 1.0) * SUN_SHADOW_EXTENT_XY;
    return center +
        basisRight * offset.x +
        basisUp * offset.y +
        sunDirection * SUN_SHADOW_EXTENT_Z;
}

vec3 sunShadowRayOriginFar(vec2 uv, vec3 sunDirection, vec3 center,
    float extentXY, float extentZ)
{
    vec3 basisRight;
    vec3 basisUp;
    sunShadowBasis(sunDirection, basisRight, basisUp);
    vec2 offset = (uv * 2.0 - 1.0) * extentXY;
    return center +
        basisRight * offset.x +
        basisUp * offset.y +
        sunDirection * extentZ;
}

#ifdef SUN_SHADOW_PRODUCER
float sunShadowTraceDepth(vec3 origin, vec3 sunDirection, float maxDistance)
{
    rayQueryEXT query;
    rayQueryInitializeEXT(
        query,
        sceneTlas,
        gl_RayFlagsTerminateOnFirstHitEXT |
            gl_RayFlagsSkipClosestHitShaderEXT,
        SUN_SHADOW_CULL_MASK,
        origin,
        SUN_SHADOW_ORIGIN_BIAS,
        sunDirection,
        maxDistance);
    while(rayQueryProceedEXT(query)) {}
    if(rayQueryGetIntersectionTypeEXT(query, true) ==
        gl_RayQueryCommittedIntersectionNoneEXT)
    {
        return maxDistance;
    }
    return rayQueryGetIntersectionTEXT(query, true);
}
#endif
#ifndef SUN_SHADOW_FRAME_SEED
#define SUN_SHADOW_FRAME_SEED cam.frameCount
#endif
vec2 sunShadowJitter(int sampleIndex)
{
    uint hash = uint(SUN_SHADOW_FRAME_SEED) * 747796405u +
        uint(sampleIndex) * 2246822519u + 2891336453u;
    hash ^= hash >> 16;
    hash *= 0x7feb352du;
    hash ^= hash >> 15;
    hash *= 0x846ca68bu;
    hash ^= hash >> 16;
    return vec2(float(hash & 0xffffu), float((hash >> 16) & 0xffffu)) /
        65535.0 - 0.5;
}

#ifdef SUN_SHADOW_CONSUMER
float sunShadowVisibilityIn(
    sampler2D shadowMap,
    vec3 position,
    vec3 sunDirection,
    int sampleIndex,
    float extentXY,
    float extentZ,
    float texel,
    float centerQuantum,
    float outsideVisibility,
    float bias,
    out float outsideFade01)
{
    vec3 basisRight;
    vec3 basisUp;
    sunShadowBasis(sunDirection, basisRight, basisUp);
    vec3 local = position - sunShadowCenterFor(extentXY, centerQuantum);
    vec2 uv = vec2(dot(local, basisRight), dot(local, basisUp)) /
        (2.0 * extentXY) + 0.5;
    float outsideDistance = max(
        max(-uv.x, uv.x - 1.0),
        max(-uv.y, uv.y - 1.0)) * (2.0 * extentXY);
#if SUN_SHADOW_Z_FADE
    float depthSigned = dot(local, sunDirection);
    outsideDistance = max(outsideDistance,
        max(-depthSigned - extentZ,
            depthSigned - extentZ));
#endif
    if(outsideDistance > 0.0)
    {
        outsideFade01 = smoothstep(
            0.0, SUN_SHADOW_OUTSIDE_FADE_BLOCKS, outsideDistance);
        return mix(
            1.0,
            outsideVisibility,
            outsideFade01);
    }
    outsideFade01 = 0.0;
    float depth = extentZ - dot(local, sunDirection);
    vec2 sampleUv = uv + sunShadowJitter(sampleIndex) * texel;
#ifdef SUN_SHADOW_PCF_TAPS_4
    float lit = 0.0;
    for(int tapY = 0; tapY < 2; ++tapY)
    {
        for(int tapX = 0; tapX < 2; ++tapX)
        {
            vec2 tapUv = sampleUv +
                (vec2(float(tapX), float(tapY)) - 0.5) * texel;
            lit += depth < texture(shadowMap, tapUv).r + bias ? 1.0 : 0.0;
        }
    }
    return lit * 0.25;
#else
    float stored = texture(shadowMap, sampleUv).r;
    return depth < stored + bias ? 1.0 : 0.0;
#endif
}

#ifndef SUN_SHADOW_NO_NEAR_MAP
#ifdef SUN_SHADOW_FAR_MAP
float sunShadowFarVisibility(vec3 position, vec3 sunDirection, int sampleIndex);
#endif
float sunShadowVisibility(vec3 position, vec3 sunDirection, int sampleIndex)
{
    float outsideFade = 0.0;
    float nearVis = sunShadowVisibilityIn(sunShadowMap, position, sunDirection, sampleIndex,
        SUN_SHADOW_EXTENT_XY, SUN_SHADOW_EXTENT_Z, SUN_SHADOW_TEXEL,
        SUN_SHADOW_CENTER_QUANTUM,
        SUN_SHADOW_OUTSIDE_VISIBILITY, SUN_SHADOW_BIAS_NEAR, outsideFade);
#ifdef SUN_SHADOW_FAR_MAP
    if(outsideFade > 0.0)
    {
        return mix(
            nearVis,
            sunShadowFarVisibility(position, sunDirection, sampleIndex),
            outsideFade);
    }
#endif
    return nearVis;
}
#endif

const float SUN_SHADOW_FAR_OUTSIDE_VISIBILITY = 1.0;

#ifdef SUN_SHADOW_FAR_MAP
float sunShadowFarVisibility(vec3 position, vec3 sunDirection, int sampleIndex)
{
    float ignoredOutsideFade;
    return sunShadowVisibilityIn(sunShadowMapFar, position, sunDirection, sampleIndex,
        cam.sunShadowFarParams.x, cam.sunShadowFarParams.y, cam.sunShadowFarParams.z,
        cam.sunShadowFarParams.z,
        SUN_SHADOW_FAR_OUTSIDE_VISIBILITY, SUN_SHADOW_BIAS, ignoredOutsideFade);
}
#endif

#if defined(SUN_SHADOW_FOG_UNION) && defined(SUN_SHADOW_FAR_MAP) && \
    !defined(SUN_SHADOW_NO_NEAR_MAP)
float sunShadowFogVisibility(vec3 position, vec3 sunDirection, int sampleIndex)
{
    float nearFade = 0.0;
    float visibility = sunShadowVisibilityIn(
        sunShadowMap, position, sunDirection, sampleIndex,
        SUN_SHADOW_EXTENT_XY, SUN_SHADOW_EXTENT_Z, SUN_SHADOW_TEXEL,
        SUN_SHADOW_CENTER_QUANTUM,
        SUN_SHADOW_FAR_OUTSIDE_VISIBILITY,
        SUN_SHADOW_BIAS_NEAR, nearFade);

    float noDataFade = nearFade;
    if(cam.sunShadowFarParams.w > 0.5)
    {
        float farFade = 0.0;
        visibility = min(visibility, sunShadowVisibilityIn(
            sunShadowMapFar, position, sunDirection, sampleIndex,
            cam.sunShadowFarParams.x, cam.sunShadowFarParams.y,
            cam.sunShadowFarParams.z, cam.sunShadowFarParams.z,
            SUN_SHADOW_FAR_OUTSIDE_VISIBILITY,
            SUN_SHADOW_BIAS, farFade));
        noDataFade = min(nearFade, farFade);
    }

    return mix(visibility, SUN_SHADOW_OUTSIDE_VISIBILITY, noDataFade);
}
#endif
#endif
#endif
