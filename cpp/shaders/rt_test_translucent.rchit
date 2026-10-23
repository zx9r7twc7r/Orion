#version 460
// 2026-10-21：延迟材质已是唯一模式（见文档 §13），本 TU 只写命中身份。
#if !defined(RT_DEFERRED_MATERIAL) && !defined(RT_BUILD_PRODUCTION_MODE)
#define RT_DEFERRED_MATERIAL 1
#endif
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference2 : require
#extension GL_EXT_scalar_block_layout : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// The pane transmission filter is single-sourced in glass_tint_common.glsl so
// this camera-side glass path, the two Any-Hit sheet models and the voxel DDA
// arm of the same stained-glass model cannot drift apart again.
// GLASS_TINT_NO_DDA keeps the voxel march out of this translation unit: its
// callee vptReadSourceVoxel() is defined only by the raygen-family shaders.
#extension GL_GOOGLE_include_directive : require
#define GLASS_TINT_NO_DDA 1
#include "glass_tint_common.glsl"
// LOD / 远场几何的角色表（binding 19）。DH 远场的识别由它回答，pad1 只再表示材质编号。
#include "lod_coverage.glsl"

// 2026-10-20: 结构体单源化，见 hit_payload.glsl（六个 shader 共用同一份布局）。
#include "hit_payload.glsl"
#ifdef RT_DEFERRED_MATERIAL
layout(location = 0) rayPayloadInEXT HitIdentity payload;
#else
layout(location = 0) rayPayloadInEXT HitPayload payload;
#endif

const uint RT_STATE_PURPOSE_MASK       = 0x7u;
const uint RT_STATE_HIT_CLASS_SHIFT    = 3u;
const uint RT_STATE_WATER_EVENT_SHIFT  = 5u;
const uint RT_STATE_GLASS_BIT          = 1u << 7u;
const uint RT_STATE_GLASS_BOTTOM_BIT   = 1u << 8u;
const uint RT_STATE_PROFILE_SHIFT      = 9u;
// 2026-10-07（用户要求）：**普通（未染色）玻璃**标记 —— bit 12（0..11 已用满）。
// 置位者：本文件下方 t12IsGlass 分支（顶点色判"没注入染料"）。消费者：rgen 的
// isClearGlassHit()，语义是"这片玻璃当空气"：不染色、不打镜面反射、不加太阳高光，
// 光直接穿过去。回退走 rgen 侧的 GI_CLEAR_GLASS_AS_AIR=0。
const uint RT_STATE_CLEAR_GLASS_BIT    = 1u << 12u;
const uint RT_PURPOSE_CLASSIFY         = 1u;
// 2026-10-06：rgen 的 RR-6.2 主水反射 guide 射线（RT_PURPOSE_DISTANCE_ONLY）
// 也会命中本组（玻璃 / voxy-DH LOD 水）。补上同名常量供快速路径识别。
const uint RT_PURPOSE_DISTANCE_ONLY    = 2u;
const uint RT_PURPOSE_PASSTHROUGH      = 4u;
const uint RT_PURPOSE_WATER_MEDIUM     = 6u;
const uint RT_PURPOSE_PRIMARY_SKY      = 7u;
const uint RT_HIT_OPAQUE               = 0u;
const uint RT_HIT_WATER                = 1u;
const uint RT_HIT_TRANSLUCENT          = 2u;
const uint RT_WATER_NONE               = 0u;
const uint RT_WATER_AIR_TO_WATER       = 1u;
const uint RT_WATER_WATER_TO_AIR       = 2u;

uint getRayPurpose(uint state)
{
    return state & RT_STATE_PURPOSE_MASK;
}

uint makeHitState(
    uint purpose,
    uint hitClass,
    uint waterEvent,
    bool glass,
    bool glassBottom,
    uint transparentProfile
)
{
    return
        (purpose & RT_STATE_PURPOSE_MASK) |
        ((hitClass & 0x3u) << RT_STATE_HIT_CLASS_SHIFT) |
        ((waterEvent & 0x3u) << RT_STATE_WATER_EVENT_SHIFT) |
        (glass ? RT_STATE_GLASS_BIT : 0u) |
        (glassBottom ? RT_STATE_GLASS_BOTTOM_BIT : 0u) |
        ((transparentProfile & 0x7u) << RT_STATE_PROFILE_SHIFT);
}

uint getTransparentProfileFromRawMaterialId(float materialId)
{
    return uint(
        round(
            clamp(fract(materialId) * 10.0, 0.0, 4.0)
        )
    );
}
hitAttributeEXT vec2 attribs;

struct Vertex {
    float x, y, z;
    float u, v;
    float r, g, b, a;
    float materialId;
    float pad1;
    float pad2;
};

layout(buffer_reference, scalar) buffer Vertices {
    Vertex v[];
};

// =====================================================
// 2026-10-21 地形 32B 顶点（与 C++ 的 SectionVertex 逐字节对应）
//
// 本 TU 自带 Vertex/Vertices 且不 include material_common.glsl ⇒ 这里单独持一份
// 解包器；内容与 material_common.glsl:89-135 一致，改那边必须同步改这里。
//
// 判别方式：顶点地址表项（binding 21）的 .w = 该段顶点步长（32 或 48）。
// 解包后仍返回老的 48B Vertex ⇒ 下游 materialId/pad1/pad2 使用点零改动。
// 编译期开关必须与 C++ 的 SECTION_VERTEX_32B_ENABLED 一致。
// =====================================================
#if SECTION_VERTEX_32B
struct SectionVertex32
{
    float x, y, z;
    float u, v;
    uint color;      // RGBA8: r | g<<8 | b<<16 | a<<24
    float materialId;
    float pad2;      // pad2 + (pad1 > 0 ? 64 : 0)
};

layout(buffer_reference, scalar) buffer SectionVertices32 {
    SectionVertex32 v[];
};

Vertex loadSectionVertex(uint64_t addr, uint stride, uint index)
{
    if(stride == 32u)
    {
        const SectionVertex32 packed = SectionVertices32(addr).v[index];

        Vertex unpacked;
        unpacked.x = packed.x;
        unpacked.y = packed.y;
        unpacked.z = packed.z;
        unpacked.u = packed.u;
        unpacked.v = packed.v;
        unpacked.r = float(packed.color & 0xFFu) / 255.0;
        unpacked.g = float((packed.color >> 8) & 0xFFu) / 255.0;
        unpacked.b = float((packed.color >> 16) & 0xFFu) / 255.0;
        unpacked.a = float((packed.color >> 24) & 0xFFu) / 255.0;
        unpacked.materialId = packed.materialId;

        const bool rainPuddle = packed.pad2 >= 64.0;
        unpacked.pad1 = rainPuddle ? 0.25 : 0.0;
        unpacked.pad2 = rainPuddle ? (packed.pad2 - 64.0) : packed.pad2;

        return unpacked;
    }

    return Vertices(addr).v[index];
}
#else
Vertex loadSectionVertex(uint64_t addr, uint /*stride*/, uint index)
{
    return Vertices(addr).v[index];
}
#endif

// One entry per TLAS Section instance, selected by
// VkAccelerationStructureInstanceKHR::instanceCustomIndex.
// 2026-10-09：表项 8B → 16B（uvec4），与 C++ 侧 GPUSectionVertexAddress
// 及 rt_test.rchit 逐字节一致。.xy = 顶点地址，.z = 位移段字节偏移。
layout(std430, binding = 21, set = 0) readonly buffer SectionVertexAddressBuffer {
    uvec4 vertexAddresses[];
} sectionVertexAddressBuffer;
layout(binding = 2, set = 0) uniform CameraProperties
{
    #include "camera_ubo.glsl"
} cam;

// 2026-09-30 去重复：中细波与主表同步换成非周期噪声波场
// （water_noise_wave.glsl 不依赖 cam/binding，只吃 waveTime）。
#include "water_noise_wave.glsl"

// Binding 33 is a two-layer RG16F persistent height/velocity field.  The
// current readable layer is selected through the UBO, so no descriptor is
// rewritten while frames are in flight.
layout(binding = 33, set = 0) uniform sampler2DArray waterSimulationState;

// Pure optical, world-space water ripples.  Integer time harmonics keep the
// field continuous at the Minecraft day wrap; high-frequency slopes fade at
// distance to avoid ray-traced/DLSS aliasing.
struct WaterWaveSurface {
    vec3 normal;
};

// One short-lived impact per deterministic world-space cell.  This is the
// same analytic field as the opaque/water closest-hit path: it adds no
// particle instances, textures, compute dispatches, or hardware rays.
float rainRippleHash(vec2 p)
{
    p = fract(p * vec2(0.1031, 0.11369));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}

vec2 rainImpactRippleGradient(vec2 worldXZ, float cameraDistance)
{
    float rain = abs(cam.weatherParams.z - 1.0) < 0.25
        ? clamp(cam.weatherParams.x, 0.0, 1.0)
        : 0.0;
    if(rain <= 0.01 || getRayPurpose(payload.state) != RT_PURPOSE_PRIMARY_SKY)
    {
        return vec2(0.0);
    }

    float nearFade = 1.0 - smoothstep(28.0, 72.0, cameraDistance);
    if(nearFade <= 0.0)
    {
        return vec2(0.0);
    }

    // Keep the opaque and translucent water paths bit-for-bit aligned:
    // every rain-impact dimension is 40% smaller than the dense field.
    const float CELL_SIZE = 0.432;
    const float RING_SPEED = 0.336;
    const float RING_FREQUENCY = 66.66667;
    // Match the opaque/water path: native steady-clock time, not frame rate.
    float time = uintBitsToFloat(cam.padding);
    vec2 baseCell = floor(worldXZ / CELL_SIZE);
    vec2 gradient = vec2(0.0);

    for(int y = -1; y <= 1; ++y)
    {
        for(int x = -1; x <= 1; ++x)
        {
            vec2 cell = baseCell + vec2(float(x), float(y));
            float seedX = rainRippleHash(cell + vec2(17.0, 43.0));
            float seedZ = rainRippleHash(cell + vec2(71.0, 29.0));
            float age = fract(time * mix(0.42, 1.08, rain) +
                rainRippleHash(cell + vec2(113.0, 7.0)));
            vec2 impact = (cell + vec2(seedX, seedZ)) * CELL_SIZE;
            vec2 delta = worldXZ - impact;
            float distanceToImpact = length(delta);
            float radius = age * RING_SPEED;
            float envelope = (1.0 - smoothstep(0.52, 1.0, age)) *
                (1.0 - smoothstep(0.12, 0.204, distanceToImpact));
            if(envelope <= 0.0 || distanceToImpact <= 1.0e-4)
            {
                continue;
            }

            float slope = cos((distanceToImpact - radius) * RING_FREQUENCY);
            gradient += normalize(delta) * slope * envelope;
        }
    }

    // Match the confirmed visible ground-ripple amplitude.
    return gradient * (0.060 * rain * nearFade);
}

WaterWaveSurface evaluateWaterWaveSurface(vec2 worldXZ, float waterY)
{
    const float TAU = 6.28318530718;
    // historyParams.w is the celestial day phase, not the optical wave clock.
    // Bound seconds before this path's 113x micro band to prevent temporal
    // aliasing while preserving a real, monotonic water animation.
    const float WATER_OPTICAL_TIME_SCALE = 0.025;
    // 「波速」滑杆（waterStyleParams1.x，0..3，出厂 1.0），与
    // water_wave_field.glsl 主表同一约定：只缩放自然波带的时间相位，
    // 交互波场（纹理模拟）不缩放。拖动瞬间相位整体跳一次，属预期。
    float phase = uintBitsToFloat(cam.padding) * WATER_OPTICAL_TIME_SCALE *
        clamp(cam.waterStyleParams1.x, 0.0, 3.0);
    vec3 cameraPosition =
        (cam.viewInverse * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    float cameraDistance = length(worldXZ - cameraPosition.xz);

    // 2026-09-30 细碎化（与 water_wave_field.glsl 主表同一轮改）：
    // d0（波长 ~17.5 格、无衰减）是这副本里"大波"的主力，压到耳语级；
    // 能量移到 d2-d4（λ2.7/1.34/0.71 格）。大尺度 warp 从 ±1.10 格压到
    // ±0.40 —— 位移相对 2.7 格的细波太大时会把细碎波纹拉成长波浪条纹，
    // 正是截图里那种"大波"观感的另一半来源。
    // Long swell is now a whisper; increasingly fine ripples are smoothly
    // band-limited in the distance rather than abruptly removed.
    float mediumFade = 1.0 - smoothstep(48.0, 112.0, cameraDistance);
    float fineFade = 1.0 - smoothstep(18.0, 56.0, cameraDistance);
    float microFade = 1.0 - smoothstep(8.0, 28.0, cameraDistance);

    const vec2 d0 = vec2(0.8320503, 0.5547002);
    const vec2 d1 = vec2(-0.4472136, 0.8944272);
    const vec2 d2 = vec2(0.9701425, -0.2425356);
    const vec2 d3 = vec2(0.3162278, 0.9486833);
    const vec2 d4 = vec2(-0.9805807, 0.1961161);

    // Slow large-scale domain warp.  Without it these five plane waves keep
    // their crest lines parallel forever, which reads as visible tiling.
    // 2026-09-30 去重复：与主表同步加中尺度档（λ≈22 格、弯曲 ~8°），
    // 把本副本主导的 λ6.8/2.7 格波带的平行波峰列弯成不规则拼块。
    vec2 warpedXZ = worldXZ +
        vec2(
            sin(dot(worldXZ, d3) * 0.21 + phase * 7.0),
            cos(dot(worldXZ, d4) * 0.17 - phase * 5.0)
        ) * 0.40 +
        vec2(0.30, -0.40) *
            sin(dot(worldXZ, vec2(0.1961161, -0.9805807)) * 0.283 +
                phase * 2.0);

    float a0 = dot(warpedXZ, d0) * 0.36 + phase * 19.0;
    float a1 = dot(warpedXZ, d1) * 0.92 - phase * 37.0;
    float a2 = dot(warpedXZ, d2) * 2.35 + phase * 61.0;
    float a3 = dot(warpedXZ, d3) * 4.70 - phase * 89.0;
    float a4 = dot(warpedXZ, d4) * 8.90 + phase * 113.0;

    WaterWaveSurface result;
    // 2026-09-30 去重复 v2：d2-d4（λ2.7/1.34/0.71 格平面波）换成与主表
    // 同一套非周期噪声波场 —— 平面波每 λ 格精确重复，噪声波每个波峰
    // 形状都不同。waveTime 约定：主表 waveTime = 时钟×0.025×3×波速，
    // 本函数的 phase = 时钟×0.025×波速，故传 phase×3 对齐。
    vec2 gradient =
        d0 * (0.0060 * 0.36 * cos(a0)) +
        d1 * (0.0120 * 0.92 * cos(a1) * mediumFade) +
        waterNoiseWaveGradient(warpedXZ, phase * 3.0);
    // Water style wave dial: matches the opaque closest-hit path and the
    // caustic pass.  Interaction/rain gradients below stay unscaled.
    gradient *= clamp(cam.waterStyleParams0.x, 0.0, 2.0);

    // Persistent Eclipse-style interaction field.  The physical water plane
    // is unchanged; only its optical normal receives the stored height slope.
    // This is gated to the actual water elevation reported for the player so
    // vertically separated ponds cannot share one disturbance.
    float waterSurfaceY = cam.waterSimSurfaceAndOrigin.x;
    float elevationMatch = 1.0 - smoothstep(
        0.65, 1.50, abs(waterY - waterSurfaceY)
    );
    float worldTexel = cam.waterSimSurfaceAndOrigin.w;
    vec2 simulationOrigin = cam.waterSimSurfaceAndOrigin.yz;
    vec2 simulationUV = (worldXZ - simulationOrigin) /
        (worldTexel * 512.0);
    vec2 simulationBorder = smoothstep(
        vec2(0.0), vec2(0.025), simulationUV
    ) * (1.0 - smoothstep(
        vec2(0.975), vec2(1.0), simulationUV
    ));
    float simulationValid =
        float(cam.waterSimState.y != 0u) *
        elevationMatch * simulationBorder.x * simulationBorder.y;

    if(simulationValid > 0.0 && worldTexel > 0.0)
    {
        float stateLayer = float(cam.waterSimState.x);
        vec2 uvStep = vec2(1.0 / 512.0);
        float heightLeft = texture(
            waterSimulationState,
            vec3(simulationUV - vec2(uvStep.x, 0.0), stateLayer)
        ).r;
        float heightRight = texture(
            waterSimulationState,
            vec3(simulationUV + vec2(uvStep.x, 0.0), stateLayer)
        ).r;
        float heightDown = texture(
            waterSimulationState,
            vec3(simulationUV - vec2(0.0, uvStep.y), stateLayer)
        ).r;
        float heightUp = texture(
            waterSimulationState,
            vec3(simulationUV + vec2(0.0, uvStep.y), stateLayer)
        ).r;

        // The simulation stores height in abstract pressure units.  This
        // conversion affects only the optical slope and was chosen to keep
        // entry rings and movement wakes clearly visible without moving the
        // static BLAS water surface.
        vec2 interactionGradient = vec2(
            heightRight - heightLeft,
            heightUp - heightDown
        ) * (0.19 / max(worldTexel, 1.0e-4));
        gradient += interactionGradient * simulationValid;
    }

    // Rain reaches every visible water elevation while the persistent RG16F
    // simulation remains reserved for the player's entry, exit, and wake.
    gradient += rainImpactRippleGradient(worldXZ, cameraDistance);

    result.normal = normalize(vec3(-gradient.x, 1.0, -gradient.y));
    return result;
}

// Must remain bit-for-bit equivalent to rgen's continuous celestial-light
// functions so POM self-shadowing and direct lighting cannot diverge.
// 2026-10-07 末地光源球开关（定义见 sky_model_common.glsl，本文件不 include
// 它，提前兜底；重复定义同值合法）。
#ifndef END_ORB_ENABLE
#define END_ORB_ENABLE 1
#endif

vec3 getTimeDrivenSunDirection()
{
    const float TWO_PI = 6.28318530718;
    vec3 horizontalAzimuth =
        normalize(
            vec3(0.5, 0.0, 0.3)
        );
    float dayAngle =
        fract(cam.historyParams.w) * TWO_PI;

#if END_ORB_ENABLE
    // 末地光源球：固定天顶（只自转、不公转）（endOrbDirection，见
    // sky_model_common.glsl；本文件不 include 那个头，字面量内联）。
    // ⚠ 六个方向副本必须同一向量，否则可见球与直射光方向分家。
    if(abs(cam.weatherParams.z - 3.0) < 0.25)
    {
        // 2026-10-18：光源球固定在末地天顶（只自转、不公转）。
        return vec3(0.0, 1.0, 0.0);
    }
#endif

    return normalize(
        horizontalAzimuth * cos(dayAngle) +
        vec3(0.0, sin(dayAngle), 0.0)
    );
}

const float TIME_DRIVEN_SUN_NOON_SCALE = 1.0;
const float TIME_DRIVEN_MOON_NOON_SCALE = 0.05;

float getTimeDrivenSunElevation()
{
    const float TWO_PI = 6.28318530718;
#if END_ORB_ENABLE
    // 末地恒"白天"（光源球固定天顶），与 rgen/rmiss 同一份判据。
    if(abs(cam.weatherParams.z - 3.0) < 0.25)
    {
        return 1.0;
    }
#endif
    return sin(fract(cam.historyParams.w) * TWO_PI);
}

float getTimeDrivenSkyLightRadianceScale()
{
    float elevation = getTimeDrivenSunElevation();
    return elevation >= 0.0
        ? elevation * TIME_DRIVEN_SUN_NOON_SCALE
        : -elevation * TIME_DRIVEN_MOON_NOON_SCALE;
}

vec3 getTimeDrivenSkyLightDirection()
{
    return getTimeDrivenSunElevation() >= 0.0
        ? getTimeDrivenSunDirection()
        : -getTimeDrivenSunDirection();
}

layout(binding = 3, set = 0) uniform sampler2D blockAtlas;
layout(binding = 4, set = 0) uniform sampler2D normalAtlas;
layout(binding = 5, set = 0) uniform sampler2D specularAtlas;
layout(binding = 45, set = 0) uniform sampler2D voxyLodAtlas;
// DH 自己的 blockRatioAtlas（= DH 着色器里的 uBlockAtlas）。图集没就绪时 C++ 会把它
// 回落到原版方块图集，所以采样前必须确认顶点 tileId != 0。
layout(binding = 49, set = 0) uniform sampler2D dhBlockRatioAtlas;

// =====================================================
// Voxy LOD analytical mip sampling (v11)
//
//   cellPx = cellW * screenH / (2 tan(fov/2) * dist) * facing
//   mip    = clamp(log2(16 / cellPx), 0, 4)
//
// facing = |dot(normal, rayDir)| reproduces the hardware
// max-derivative mip choice at grazing angles.  Mip 4 is the
// hard cap: below it a tile is sub-texel and neighbouring
// tiles would bleed.  The sample UV is inset-clamped to the
// owning tile so filtering never crosses a tile border.
//
// pad2 = bridgeMip + shade/256; the bridge mip is superseded
// by the estimate above, shade stays the light modulation.
// pad2 < 0 keeps the pre-modulated vertex-colour fallback.
//
// Colour space: this pipeline shades with gamma-encoded values.
// Near terrain is texelFetch(blockAtlas) * interpColor with no
// decode anywhere (VulkanCore uploadChunk writes byte/255 raw),
// and rr_sdr_post applies the single global pow(1/2.2).  Voxy
// must use the same convention or the LOD/vanilla seam shows a
// brightness step, so the atlas sample is NOT decoded here; the
// bridge pre-encodes its vertex bytes to cancel the sRGB decode
// that appendVoxyLodQuads applies, leaving tint in gamma space.
// =====================================================
// v15: 256 -> 1024 (bridge tiles 16 -> 64 per axis, capacity 255 -> 4095).
// VOXY_TILE_PIXELS stays 16 so texel density is unchanged; only the number of
// addressable tiles grows.  Must track ATLAS_SIZE in the voxy bridge.
const float VOXY_ATLAS_SIZE = 1024.0;
const float VOXY_TILE_PIXELS = 16.0;

// Disabled: decoding the atlas sample to linear is physically right but
// inconsistent with every other material in this pipeline, which shades in
// gamma space.  Kept as a future interface option for a linear-space pass.
#if 0
vec3 voxySrgbToLinear(vec3 srgb)
{
    vec3 high = pow((srgb + 0.055) * (1.0 / 1.055), vec3(2.4));
    vec3 low = srgb * (1.0 / 12.92);
    return mix(high, low, lessThanEqual(srgb, vec3(0.04045)));
}
#endif

float voxyLodMip(vec3 pos0, vec3 pos1, vec3 geoNormal)
{
    // Both quad triangles expose the cell edge length: the first
    // triangle's v0->v1 is a square side, the second's is the
    // diagonal whose largest |component| equals the same side.
    vec3 edge = abs(pos1 - pos0);
    float cellW = max(edge.x, max(edge.y, edge.z));
    float projScale = float(gl_LaunchSizeEXT.y)
        / (2.0 * max(tan(cam.cameraParams.z * 0.5), 0.05));
    float facing = abs(dot(geoNormal, gl_WorldRayDirectionEXT));
    float cellPx = cellW * projScale * max(facing, 0.02)
        / max(gl_HitTEXT, 1.0e-3);
    return clamp(
        log2(VOXY_TILE_PIXELS / max(cellPx, 1.0e-4)),
        0.0,
        4.0
    );
}

vec2 voxyTileSafeUV(vec2 uv, vec2 uv0, vec2 uv1, vec2 uv2, float mip)
{
    // Encoded corner UVs are mip0 texel centres; recover the true
    // tile origin, then inset by half a texel of the selected mip
    // (ceil keeps the coarser trilinear level inside the tile).
    vec2 tileOrigin = min(uv0, min(uv1, uv2)) - vec2(0.5 / VOXY_ATLAS_SIZE);
    float inset = exp2(ceil(mip)) * (0.5 / VOXY_ATLAS_SIZE);
    vec2 tileSpan = vec2(VOXY_TILE_PIXELS / VOXY_ATLAS_SIZE);
    return clamp(uv, tileOrigin + inset, tileOrigin + tileSpan - inset);
}

vec3 voxyLodAlbedo(float mipShade, vec2 uv, vec2 uv0, vec2 uv1, vec2 uv2,
    float mip, vec3 tintOrFallback)
{
    if(mipShade < 0.0)
    {
        return tintOrFallback;
    }
    float shade = mipShade - floor(mipShade);
    vec2 safeUV = voxyTileSafeUV(uv, uv0, uv1, uv2, mip);
    return textureLod(voxyLodAtlas, safeUV, mip).rgb * tintOrFallback * shade;
}

// =====================================================
// DH far field: per-block texture (binding 49 = DH 的 blockRatioAtlas)
//
// 与 rt_test.rchit 里那份逐字一致（DH 水走 alpha 桶，命中在这里）。
// 顶点字节 6-9 = (textureTileId, normalIndex)。按 DH 的 terrain/blaze/frag.fsh 复现：
//   tileOrigin = vec2(id % 256, id / 256) * 16
//   uv         = (tileOrigin + blockFaceUv() * 16) / atlasSize
//   albedo     = mix(顶点色, clamp(顶点色 * tile.rgb * 2), tile.a)
// =====================================================
// Same derivation as voxyLodMip, but the cell is always exactly one block:
// once the UV is per-block the mip must follow the block's on-screen size,
// not the quad's span, otherwise the outer ring's large cells sample an
// over-coarse mip and the texture dissolves into a blur.
float dhBlockMip(vec3 hitPos, vec3 normal)
{
    return voxyLodMip(hitPos, hitPos + vec3(1.0), normal);
}

vec2 dhFaceUv(vec3 hitPos, float normalIndex)
{
    vec3 pos = fract(hitPos);
    if(normalIndex < 0.5) return vec2(pos.x, 1.0 - pos.z);        // down
    if(normalIndex < 1.5) return vec2(pos.x, pos.z);              // up
    if(normalIndex < 2.5) return vec2(1.0 - pos.x, 1.0 - pos.y);  // north
    if(normalIndex < 3.5) return vec2(pos.x, 1.0 - pos.y);        // south
    if(normalIndex < 4.5) return vec2(pos.z, 1.0 - pos.y);        // west
    return vec2(1.0 - pos.z, 1.0 - pos.y);                        // east
}

// DH far-field fake-texture grit.  Identical to rt_test.rchit's copy; see the
// long comment there for the derivation from DH's terrain/blaze/frag.fsh, the
// two deliberate deviations (world-anchored grid, real geometric normal), and
// why the strength/steps are deliberately above DH's own defaults.
float dhNoiseRand(float co)
{
    return fract(sin(co * 91.3458) * 47453.5453);
}

float dhNoiseRand(vec2 co)
{
    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

float dhNoiseRand(vec3 co)
{
    return dhNoiseRand(co.xy + dhNoiseRand(co.z));
}

vec3 dhFakeTextureNoise(vec3 albedo, vec3 hitPos, vec3 geoNormal)
{
    const float DH_NOISE_INTENSITY = 0.18;
    const float DH_NOISE_STEPS = 8.0;
    const float DH_NOISE_DROPOFF = 1024.0;

    vec3 fixedPos = hitPos + normalize(geoNormal) * 0.001;
    vec3 grid = floor(fixedPos * DH_NOISE_STEPS) / DH_NOISE_STEPS;

    float luminance = (albedo.r + albedo.g + albedo.b) * (1.0 / 3.0);
    float amplification =
        (1.0 - pow(luminance * 2.0 - 1.0, 2.0)) * DH_NOISE_INTENSITY;

    float randomValue =
        dhNoiseRand(grid) * 2.0 * amplification - amplification;
    vec3 newColor = clamp(albedo + (1.0 - albedo) * randomValue, 0.0, 1.0);

    float viewDistance = length(hitPos - cam.viewInverse[3].xyz);
    float distanceFade = min(viewDistance / DH_NOISE_DROPOFF, 1.0);
    return mix(newColor, albedo, distanceFade);
}

// DH 平价色补偿：DH 的平面色是**线性空间**的贴图均值（再编码回 sRGB），而近场
// 平均出来的是 sRGB 均值；encode 是凹函数 ⇒ DH 那半边永不更暗，反差越大的贴图
// 差得越多。完整推导见 rt_test.rchit 的同名注释。
//
// ⚠ 2026-10-01：偏差正比于合并层级（越远越亮越灰），所以补偿必须**随距离爬升**；
// 而且旧写法只在 tileId == 0 的平价色分支里生效，图集接上后整段失效。现在两条
// 分支统一补偿。常量含义与调法见 rt_test.rchit，两边必须保持一致。
const float DH_LOD_NEAR_GAIN = 0.85;
const float DH_LOD_FAR_GAIN = 0.65;
const float DH_LOD_NEAR_SAT = 1.0;
const float DH_LOD_FAR_SAT = 1.45;
const float DH_LOD_NEAR_BLOCKS = 96.0;
const float DH_LOD_FAR_BLOCKS = 768.0;

vec3 dhLodColorCompensation(vec3 color, vec3 hitPos)
{
    float viewDistance = length(hitPos - cam.viewInverse[3].xyz);
    float t = clamp(
        (viewDistance - DH_LOD_NEAR_BLOCKS) /
        max(DH_LOD_FAR_BLOCKS - DH_LOD_NEAR_BLOCKS, 1.0),
        0.0,
        1.0);
    vec3 gained = color * mix(DH_LOD_NEAR_GAIN, DH_LOD_FAR_GAIN, t);
    // 增益只压亮度、动不了彩度（线性平均把每通道往均值拉的后果），另补一步加饱和。
    // 取值与理由见 rt_test.rchit 的 dhLodColorCompensation。
    float luma = dot(gained, vec3(0.2126, 0.7152, 0.0722));
    return clamp(
        mix(vec3(luma), gained, mix(DH_LOD_NEAR_SAT, DH_LOD_FAR_SAT, t)),
        0.0,
        1.0);
}

vec3 dhLodAlbedo(float tileId, float normalIndex, vec3 hitPos, vec3 geoNormal,
    vec3 vertexColor)
{
    // tileId 0 = DH 自己的"无纹理"约定，同时也是"图集还没就绪"的占位。
    // DH 对这类方块补一层假纹理噪声（applyNoise）。
    if(tileId < 0.5)
    {
        return dhLodColorCompensation(
            dhFakeTextureNoise(vertexColor, hitPos, geoNormal), hitPos);
    }
    float mip = clamp(dhBlockMip(hitPos, geoNormal), 0.0, 4.0);
    ivec2 atlasSize = textureSize(dhBlockRatioAtlas, 0);
    vec2 tileOrigin = vec2(mod(tileId, 256.0), floor(tileId / 256.0)) * 16.0;
    vec2 uv = (tileOrigin + dhFaceUv(hitPos, normalIndex) * 16.0) / vec2(atlasSize);
    vec4 tile = textureLod(dhBlockRatioAtlas, uv, mip);
    vec3 tinted = clamp(vertexColor * (tile.rgb * 2.0), 0.0, 1.0);
    return dhLodColorCompensation(
        mix(vertexColor, tinted, tile.a), hitPos);
}

// =====================================================
// DH far field: baked block light (DH vertex byte 13)
// =====================================================
// Java 侧把每一段的 (skyLight << 4) | blockLight 打进 DH 顶点字节 13，C++ 的
// appendDhLodQuads 原样搬进 Vertex.a ⇒ 这里从 interpColor.a 解回来。一个 quad 的
// 四顶点同值，重心插值精确，不需要新 attribute、不动 payload 结构。
//
// 为什么需要它：DH 远场在 MC 视距之外，我们的本地光（ReSTIR DI / VPT 体素光）
// 在那片区域**没有数据**，于是夜里远场只剩 ambient + GI ⇒ 村庄/街灯那一带全黑。
// blockLight 是唯一知道"那里有灯"的信息源。
//
// ⭐ 用法：作为**加性自发光**写进 payload.emission，刻意**不**乘进 albedo。
//   1) 乘 albedo = 把光照二次计入（ambient / 直射 / GI 已经各自乘过一次），而且
//      会把 16 级量化台阶烙在几何上 —— voxy 的 shade 就是这么被关掉的；
//   2) emission 是加性项，且会作为命中辐射进 GI ⇒ 远场街区顺带照亮自己的邻居，
//      与近场本地光的行为一致。
// skyLight 刻意不参与：天空/太阳本来由 shadow ray + GI 实时算，再乘一次就是双重
// 计光。"屋檐下的暗部"属于 RTAO/GI 的活，不该由这里补。
const float DH_BAKED_BLOCK_GAIN = 0.25;
const vec3 DH_BAKED_BLOCK_TINT = vec3(1.0, 0.82, 0.60);

float dhBlockLightLevel(float packedLight)
{
    float code = floor(clamp(packedLight, 0.0, 1.0) * 255.0 + 0.5);
    return mod(code, 16.0);
}

vec3 dhBakedBlockLight(vec3 albedo, float packedLight)
{
    float level = dhBlockLightLevel(packedLight) * (1.0 / 15.0);
    // 三次方曲线：0..4 级几乎不出光，12..15 级明确发亮（对齐"火把一两格内"的观感）。
    return albedo * DH_BAKED_BLOCK_TINT * (DH_BAKED_BLOCK_GAIN * level * level * level);
}

// The bridge files leaves, glass and ice in the same translucent
// material bucket.  Distinguish them by the whole-tile average
// alpha at mip 4 (one texel per tile): a fully covered tile is a
// real glass sheet, anything with holes is cutout foliage that
// must shade as opaque.  Sampling the tile average rather than the
// hit point keeps the decision constant across the whole quad, so
// material never flickers pixel to pixel.
bool voxyTileIsFullyCovered(vec2 uv0, vec2 uv1, vec2 uv2)
{
    vec2 tileOrigin = min(uv0, min(uv1, uv2)) - vec2(0.5 / VOXY_ATLAS_SIZE);
    vec2 tileCentre = tileOrigin
        + vec2((VOXY_TILE_PIXELS * 0.5) / VOXY_ATLAS_SIZE);
    return textureLod(voxyLodAtlas, tileCentre, 4.0).a >= 0.985;
}


// =====================================================
// RT Local Lights - L3 descriptor reservation
//
// Bound for ClosestHit as well so the future local-light shading
// stage can use the same binding 17 without another descriptor
// layout migration.
// =====================================================

struct LocalLightGPU
{
    vec4 worldPosEmission;
    uvec4 meta;
};

layout(std430, binding = 17, set = 0) readonly buffer LocalLightBuffer
{
    uvec4 header;
    LocalLightGPU lights[];
} localLightBuffer;


// =====================================================
// LabPBR 1.3
// Atlas-safe Parallax Occlusion Mapping
//
// Quality:
//     12 steps = near perpendicular
//     32 steps = grazing angle
//
// _n.a:
//     1.0 = zero depth
//     0.0 = 25% block depth
// =====================================================

float sampleLabPBRDepth(
    vec2 uv
)
{
    return
        1.0 -
        clamp(
            textureLod(
                normalAtlas,
                uv,
                0.0
            ).a,
            0.0,
            1.0
        );
}


// =====================================================
// POM sprite boundary
//
// A Minecraft quad owns one independent height field.  Do not wrap or clamp
// a traversed POM position after it leaves this sprite: retain the last valid
// in-sprite sample instead.  This prevents opposite-edge height data from
// appearing as a grazing-angle displacement or self-shadow blocker.
// =====================================================

bool isPOMSpriteUVInBounds(
    vec2 uv,
    vec2 safeMin,
    vec2 safeMax
)
{
    return
        all(greaterThanEqual(uv, safeMin)) &&
        all(lessThanEqual(uv, safeMax));
}


float sampleLabPBRDepthInSprite(vec2 uv)
{

    // PARALLAX_MODE == 1 semantics: sample exactly one atlas
    // texel.  Do not let bilinear filtering smooth the height field.
    ivec2 atlasSize = textureSize(normalAtlas, 0);
    ivec2 texel = ivec2(floor(uv * vec2(atlasSize)));

    return 1.0 - clamp(
        texelFetch(normalAtlas, texel, 0).a,
        0.0,
        1.0
    );
}


vec2 snapPOMSpriteUVToTexelCenter(
    vec2 uv,
    vec2 safeMin,
    vec2 safeMax
)
{
    ivec2 atlasSize = textureSize(normalAtlas, 0);
    vec2 texel = floor(uv * vec2(atlasSize));
    return clamp(
        (texel + vec2(0.5)) / vec2(atlasSize),
        safeMin,
        safeMax
    );
}


float getPOMDepthScale()
{
    // CameraUBO.historyParamsPadding0 -> historyParams.z
    return
        clamp(
            cam.historyParams.z,
            0.0,
            0.20
        );
}


struct ParallaxHit
{
    vec2 uv;

    float depth01;
    float borderScale;

    float depthWorld;
    float rayTravelWorld;

    vec3 worldPosition;

    bool valid;
};


ParallaxHit traceParallax(
    vec2 baseUV,
    vec2 spriteMin,
    vec2 safeMin,
    vec2 safeMax,
    vec2 spriteSpan,
    mat3 tbn,
    vec3 worldRayDirection
)
{
    float pomDepthScale =
        getPOMDepthScale();


    // 0.00 is a real "POM off" value.
    // Avoid the entire 32-96 step march and self-shadow path.
    if(pomDepthScale <= 1.0e-5)
    {
        ParallaxHit result;

        result.uv =
            baseUV;

        result.depth01 =
            0.0;

        result.borderScale =
            1.0;

        result.depthWorld =
            0.0;

        result.rayTravelWorld =
            0.0;

        result.worldPosition =
            gl_WorldRayOriginEXT +
            gl_WorldRayDirectionEXT *
            gl_HitTEXT;

        result.valid =
            false;

        return result;
    }


    // =====================================================
    // POM-C
    // Stable view-space setup.
    // =====================================================

    // Ray direction points toward the surface.
    // POM needs surface -> ray origin.
    vec3 viewDirTS =
        normalize(
            transpose(tbn) *
            (-worldRayDirection)
        );


    // Make the tangent-space view vector front-facing.
    // Keep this behavior identical for primary / reflection / GI.
    if(viewDirTS.z < 0.0)
    {
        viewDirTS =
            -viewDirTS;
    }


    float viewZ =
        clamp(
            viewDirTS.z,
            0.0,
            1.0
        );


    // =====================================================
    // POM-C1
    // Extreme-grazing safety.
    //
    // Old code divided by max(viewZ, 1e-4), which allowed
    // extremely large UV movement when the ray became almost
    // parallel to the surface.  Only the final few degrees are
    // attenuated here; normal viewing angles remain unchanged.
    // =====================================================

    const float POM_GRAZING_MIN_Z =
        0.04;

    const float POM_GRAZING_FULL_Z =
        0.10;


    float grazingFade =
        smoothstep(
            POM_GRAZING_MIN_Z,
            POM_GRAZING_FULL_Z,
            viewZ
        );


    float stableViewZ =
        max(
            viewZ,
            POM_GRAZING_MIN_Z
        );


    float facing =
        viewZ;


    // =====================================================
    // Pixel POM keeps the confirmed continuous 32 -> 48 step range.
    // The loop count is fixed at 48; only the per-ray depth increment varies.
    // =====================================================

    float layerDensity =
        mix(
            48.0,
            32.0,
            facing
        );


    layerDensity =
        clamp(
            layerDensity,
            32.0,
            48.0
        );


    float layerStep =
        1.0 /
        layerDensity;


    // LabPBR displacement in atlas UV space.
    vec2 totalUVOffset =
        (
            viewDirTS.xy /
            stableViewZ
        ) *
        spriteSpan *
        pomDepthScale *
        grazingFade;


    vec2 currentUV =
        baseUV;


    float currentLayerDepth =
        0.0;


    float currentMapDepth =
        sampleLabPBRDepthInSprite(
            currentUV
        );


    // Absolute reconstruction is retained; traversal stops before leaving
    // the current Minecraft sprite.

    for(
        int i = 0;
        i < 48;
        ++i
    )
    {
        if(
            currentLayerDepth >=
            currentMapDepth
        )
        {
            break;
        }


        if(currentLayerDepth >= 1.0)
        {
            break;
        }


        float nextLayerDepth =
            min(
                currentLayerDepth +
                    layerStep,
                1.0
            );


        vec2 nextUV =
            baseUV -
            totalUVOffset *
            nextLayerDepth;

        // Do not wrap, clamp or blend a traversal position outside the
        // owning sprite.  Keep currentUV/currentMapDepth, the last valid
        // height-field point on this compiled Minecraft quad.
        if(!isPOMSpriteUVInBounds(nextUV, safeMin, safeMax))
        {
            break;
        }

currentUV =
    nextUV;

currentLayerDepth =
    nextLayerDepth;

currentMapDepth =
    sampleLabPBRDepthInSprite(
        currentUV
    );

    }


    ParallaxHit result;


    // The march never writes an out-of-sprite position, so no wrapping or
    // corrective edge clamp is required here.
    result.borderScale = 1.0;
    result.uv = snapPOMSpriteUVToTexelCenter(
        currentUV,
        safeMin,
        safeMax
    );


    // Use the actual height of the final selected texel.  The previous
    // quantized march layer made the POM self-shadow jump whenever the
    // view crossed a 32..48-step boundary.
    result.depth01 =
        currentMapDepth *
        grazingFade;


    result.depthWorld =
        result.depth01 *
        pomDepthScale;


    result.rayTravelWorld =
        result.depthWorld /
        stableViewZ;


    vec3 originalWorldHit =
        gl_WorldRayOriginEXT +
        gl_WorldRayDirectionEXT *
        gl_HitTEXT;


    result.worldPosition =
        originalWorldHit +
        normalize(
            worldRayDirection
        ) *
        result.rayTravelWorld;


    result.valid =
        true;


    return result;
}


// =====================================================
// POM-B
// Height-field self-shadow toward the current fixed sun.
// 4-8 height samples, no additional ray tracing.
// =====================================================

float tracePOMSelfShadow(
    vec2 hitUV,
    float hitDepth01,
    vec2 spriteMin,
    vec2 safeMin,
    vec2 safeMax,
    vec2 spriteSpan,
    mat3 tbn,
    vec3 worldLightDirection
)
{
    float pomDepthScale =
        getPOMDepthScale();


    if(
        hitDepth01 <= 0.01 ||
        pomDepthScale <= 1.0e-5
    )
    {
        return 1.0;
    }


    vec3 lightDirTS =
        normalize(
            transpose(tbn) *
            normalize(worldLightDirection)
        );


    // Light behind / almost parallel to the geometric plane:
    // leave direct-light rejection to the normal/NdotL path.
    if(lightDirTS.z <= 1.0e-3)
    {
        return 1.0;
    }


    float facing =
        clamp(
            lightDirTS.z,
            0.0,
            1.0
        );


    int sampleCount =
        int(
            round(
                mix(
                    8.0,
                    4.0,
                    facing
                )
            )
        );


    sampleCount =
        clamp(
            sampleCount,
            4,
            8
        );


    float lightZ =
        max(
            lightDirTS.z,
            0.05
        );


    // =====================================================
    // 2026-10-19 自阴影判据改为**归一化高度差**（与 rt_test.rchit 同口径，
    // `parallaxShadow *= saturate(diff * 100.0 + 0.5)`），不再乘
    // pomDepthScale。
    //
    // 旧档按世界深度 0.03 格判定满遮蔽，而 pomDepthScale 默认 0.03 ⇒
    // 需要 100% 归一化高度差；实际浮雕高度差 ~0.1 时遮蔽仅 ~3%，等于没有。
    // 这里取 0.02（参考是 0.005，放宽 4 倍以适配 4~8 步采样）。
    // 调强 = 改小；调弱 = 改大。采样数不变，性能与旧档同量级。
    // =====================================================
    const float SELF_SHADOW_FULL_NORMALIZED_DEPTH =
        0.02;


    float maxBlockDepthNormalized =
        0.0;


    for(int i = 1; i <= 8; ++i)
    {
        if(i > sampleCount)
        {
            break;
        }


        // Exclude both endpoints to avoid immediately
        // self-testing the exact POM hit or the base plane.
        float progress =
            float(i) /
            float(sampleCount + 1);


        float depthRise =
            hitDepth01 *
            progress;


        float rayDepth =
            hitDepth01 -
            depthRise;


        vec2 sampleUV =
            hitUV +
            (
                lightDirTS.xy /
                lightZ
            ) *
            spriteSpan *
            pomDepthScale *
            depthRise;


        // A light ray leaving the owning sprite has no in-sprite height
        // blocker.  Do not wrap it to the opposite edge.
        if(!isPOMSpriteUVInBounds(sampleUV, safeMin, safeMax))
        {
            break;
        }

        float blockerDepth =
            sampleLabPBRDepthInSprite(
                sampleUV
            );


        // Positive means the light ray is underneath a
        // neighbouring height-field surface and is blocked.
        // 归一化偏置 0.012 → 0.004（同 rt_test.rchit：旧偏置吃掉阈值 60%）。
        float blockDepth =
            rayDepth -
            blockerDepth -
            0.004;


        float blockDepthNormalized =
            max(
                blockDepth,
                0.0
            );


        maxBlockDepthNormalized =
            max(
                maxBlockDepthNormalized,
                blockDepthNormalized
            );


        // Early-out once the selected POM hit produces enough
        // normalized height occlusion.
        if(
            maxBlockDepthNormalized >=
            SELF_SHADOW_FULL_NORMALIZED_DEPTH
        )
        {
            return 0.0;
        }
    }


    return
        1.0 -
        smoothstep(
            0.0,
            SELF_SHADOW_FULL_NORMALIZED_DEPTH,
            maxBlockDepthNormalized
        );
}


// =============================================================================
// 2026-10-20 deferred material (rollback entry: RT_DEFERRED_MATERIAL)
//   0 = old path: the pane material is evaluated here (same body, moved into
//       material_resolve_glass.glsl).
//   1 = identity only (plus the pomSelfShadow=1 glass marker); ray generation
//       evaluates the pane material after the ray is done.
// =============================================================================
// Motion segment reader.  material_resolve_glass.glsl 需要它；本 TU 原本没有这份
// 声明（运动矢量由 rt_test.rchit 负责），搬过来的共享体要自己算 prevOffset。
layout(buffer_reference, scalar) buffer VertexMotions {
    float motion[];
};

vec3 readVertexMotion(VertexMotions motions, uint vertexIndex)
{
    const uint base = vertexIndex * 3u;

    return vec3(
        motions.motion[base + 0u],
        motions.motion[base + 1u],
        motions.motion[base + 2u]
    );
}

// 共享解析体只认 materialLodRole()（lodInstanceRole() 读的是命中着色器内建，
// rgen 没有），本 TU 直接转调原生实现。
uint materialLodRole(uint instanceIndex)
{
    return lodInstanceRole();
}

// 共享解析体写入它；本 TU 的材质 helper 仍直接读 gl_HitTEXT，这里只是让共享体
// 在两个 TU（本文件 / rt_test.rgen）里都能编译。
//
// 2026-10-20：与 rt_test.rchit 同一个漏项 —— materialRayOrigin / materialRayDirection
// 没声明，导致**不定义 RT_DEFERRED_MATERIAL 时本文件编译不过**
//（material_resolve_glass.glsl:37 undeclared）。三个量都在解算体开头写入。
float materialHitT = 0.0;
vec3 materialRayOrigin = vec3(0.0);
vec3 materialRayDirection = vec3(0.0, 0.0, -1.0);

// 延迟模式下命中着色器不调用材质体（它在 rgen 里跑），共享体只引用旧布局的
// payload 字段，因此只在旧模式下编译。
#ifndef RT_DEFERRED_MATERIAL
#include "material_resolve_glass.glsl"
#endif

void main()
{
#ifdef RT_DEFERRED_MATERIAL
    payload.hitDistance = gl_HitTEXT;
    payload.instanceIndex = uint(gl_InstanceCustomIndexEXT);
    payload.primitiveIndex = uint(gl_PrimitiveID);
    payload.baryX = attribs.x;
    payload.baryY = attribs.y;
    // Mark the owner of this hit for the ray-generation dispatcher.
    payload.state = (payload.state & 0x7u) | HIT_IDENTITY_GLASS_BIT;
#else
    // 2026-10-21 根因修复（同 rt_test.rchit 的说明）：生产模式必须先把命中身份与
    // hitT 播种进 ray payload，否则 resolveHitMaterialGlass() 会拿陈旧身份索引
    // sectionVertexAddressBuffer（无边界检查）⇒ 垃圾指针 ⇒ GPU 挂死。
    payload.hitDistance = gl_HitTEXT;
    payload.normal = vec3(
        float(gl_InstanceCustomIndexEXT),
        float(gl_PrimitiveID),
        attribs.x
    );
    payload.roughness = attribs.y;
    resolveHitMaterialGlass(
        gl_WorldRayOriginEXT,
        gl_WorldRayDirectionEXT,
        vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y)
    );
#endif
}

