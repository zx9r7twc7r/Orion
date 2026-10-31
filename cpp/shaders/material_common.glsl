#ifndef MATERIAL_COMMON_GLSL
#define MATERIAL_COMMON_GLSL

float materialHitT = 0.0;
vec3 materialRayOrigin = vec3(0.0);
vec3 materialRayDirection = vec3(0.0, 0.0, -1.0);






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

#include "transparent_profile_common.glsl"

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

#if SECTION_VERTEX_32B
struct SectionVertex32
{
    float x, y, z;
    float u, v;
    uint color;
    float materialId;
    float pad2;
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
Vertex loadSectionVertex(uint64_t addr, uint , uint index)
{
    return Vertices(addr).v[index];
}
#endif

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

layout(std430, binding = 21, set = 0) readonly buffer SectionVertexAddressBuffer {
    uvec4 vertexAddresses[];
} sectionVertexAddressBuffer;

#if 0
#endif

// Pure optical, world-space water ripples.  Integer time harmonics keep the
// field continuous at the Minecraft day wrap; high-frequency slopes fade at
// distance to avoid ray-traced/DLSS aliasing.
struct WaterWaveSurface {
    vec3 normal;
};

// Integer-free value hash.  Unlike sin-based shader hashes this is cheap on
// the closest-hit path and remains deterministic for DLSS/RR reprojection.
float rainRippleHash(vec2 p)
{
    p = fract(p * vec2(0.1031, 0.11369));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}

// One short-lived impact is assigned to each cell.  Testing the surrounding
// 3x3 cells makes rings continuous at cell boundaries.  This is evaluated
// only for the first visible water interface: no new texture, compute pass,
// particle, BLAS instance, or hardware ray is needed.
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

    const float CELL_SIZE = 0.432;
    const float RING_SPEED = 0.336;
    const float RING_FREQUENCY = 66.66667;
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

    return gradient * (0.060 * rain * nearFade);
}

WaterWaveSurface evaluateWaterWaveSurface(vec2 worldXZ, float waterY)
{
    float cameraDistance;
    vec3 surfacePos = vec3(worldXZ.x, waterY, worldXZ.y);
    float noV = abs(normalize(surfacePos - cam.viewInverse[3].xyz).y);
    float horizonFade = 1.0 - exp(-4.0 * noV);
    float projScale = float(gl_LaunchSizeEXT.y)
        / (2.0 * max(tan(cam.cameraParams.z * 0.5), 0.05));
    float waveLod = waterFftSlopeLodFromProj(surfacePos, materialHitT, projScale);
    vec2 naturalSlope = waterNaturalWaveGradient(worldXZ, cameraDistance, horizonFade, waveLod);

    float stateAndBorderMask;
    vec2 interactionSlope =
        waterInteractionSlope(worldXZ, stateAndBorderMask);
    float elevationMatch = 1.0 - smoothstep(
        0.65, 1.50, abs(waterY - cam.waterSimSurfaceAndOrigin.x)
    );
    vec2 gradient = waterBlendWaveSlopes(
        naturalSlope,
        interactionSlope * (stateAndBorderMask * elevationMatch),
        waterWaveBlendMode()
    );

    gradient += rainImpactRippleGradient(worldXZ, cameraDistance);

    WaterWaveSurface result;
    result.normal = normalize(vec3(-gradient.x, 1.0, -gradient.y));
    return result;
}

const int WATER_CLASSIFY_WAVE_OCTAVES = 1;

WaterWaveSurface evaluateWaterWaveSurfaceClassify(vec2 worldXZ, float waterY)
{
    float cameraDistance;
    vec3 surfacePos = vec3(worldXZ.x, waterY, worldXZ.y);
    float noV = abs(normalize(surfacePos - cam.viewInverse[3].xyz).y);
    float horizonFade = 1.0 - exp(-4.0 * noV);
    float projScale = float(gl_LaunchSizeEXT.y)
        / (2.0 * max(tan(cam.cameraParams.z * 0.5), 0.05));
    float waveLod = waterFftSlopeLodFromProj(surfacePos, materialHitT, projScale);
    vec2 naturalSlope =
        waterNaturalWaveGradientCapped(
            worldXZ,
            cameraDistance,
            WATER_CLASSIFY_WAVE_OCTAVES,
            horizonFade,
            waveLod
        );

    float stateAndBorderMask;
    vec2 interactionSlope =
        waterInteractionSlope(worldXZ, stateAndBorderMask);
    float elevationMatch = 1.0 - smoothstep(
        0.65, 1.50, abs(waterY - cam.waterSimSurfaceAndOrigin.x)
    );
    vec2 gradient = waterBlendWaveSlopes(
        naturalSlope,
        interactionSlope * (stateAndBorderMask * elevationMatch),
        waterWaveBlendMode()
    );

    WaterWaveSurface result;
    result.normal = normalize(vec3(-gradient.x, 1.0, -gradient.y));
    return result;
}

#ifndef END_ORB_ENABLE
#define END_ORB_ENABLE 1
#endif






layout(binding = 3, set = 0) uniform sampler2D blockAtlas;
layout(binding = 36, set = 0) uniform sampler2D entityAtlases[64];
layout(binding = 63, set = 0) uniform sampler2D entityNormalAtlases[64];
layout(binding = 64, set = 0) uniform sampler2D entitySpecularAtlases[64];
layout(binding = 37, set = 0) uniform sampler2D particleAtlases[3];
layout(binding = 40, set = 0) uniform sampler2D rainStreakAtlas;
layout(binding = 45, set = 0) uniform sampler2D voxyLodAtlas;
layout(binding = 49, set = 0) uniform sampler2D dhBlockRatioAtlas;

vec4 sampleEntityAtlas(uint textureSlot, vec2 uv)
{
    switch(textureSlot)
    {
        case 1u: return textureLod(entityAtlases[0], uv, 0.0);
        case 2u: return textureLod(entityAtlases[1], uv, 0.0);
        case 3u: return textureLod(entityAtlases[2], uv, 0.0);
        case 4u: return textureLod(entityAtlases[3], uv, 0.0);
        case 5u: return textureLod(entityAtlases[4], uv, 0.0);
        case 6u: return textureLod(entityAtlases[5], uv, 0.0);
        case 7u: return textureLod(entityAtlases[6], uv, 0.0);
        case 8u: return textureLod(entityAtlases[7], uv, 0.0);
        case 9u: return textureLod(entityAtlases[8], uv, 0.0);
        case 10u: return textureLod(entityAtlases[9], uv, 0.0);
        case 11u: return textureLod(entityAtlases[10], uv, 0.0);
        case 12u: return textureLod(entityAtlases[11], uv, 0.0);
        case 13u: return textureLod(entityAtlases[12], uv, 0.0);
        case 14u: return textureLod(entityAtlases[13], uv, 0.0);
        case 15u: return textureLod(entityAtlases[14], uv, 0.0);
        case 16u: return textureLod(entityAtlases[15], uv, 0.0);
        case 17u: return textureLod(entityAtlases[16], uv, 0.0);
        case 18u: return textureLod(entityAtlases[17], uv, 0.0);
        case 19u: return textureLod(entityAtlases[18], uv, 0.0);
        case 20u: return textureLod(entityAtlases[19], uv, 0.0);
        case 21u: return textureLod(entityAtlases[20], uv, 0.0);
        case 22u: return textureLod(entityAtlases[21], uv, 0.0);
        case 23u: return textureLod(entityAtlases[22], uv, 0.0);
        case 24u: return textureLod(entityAtlases[23], uv, 0.0);
        case 25u: return textureLod(entityAtlases[24], uv, 0.0);
        case 26u: return textureLod(entityAtlases[25], uv, 0.0);
        case 27u: return textureLod(entityAtlases[26], uv, 0.0);
        case 28u: return textureLod(entityAtlases[27], uv, 0.0);
        case 29u: return textureLod(entityAtlases[28], uv, 0.0);
        case 30u: return textureLod(entityAtlases[29], uv, 0.0);
        case 31u: return textureLod(entityAtlases[30], uv, 0.0);
        case 32u: return textureLod(entityAtlases[31], uv, 0.0);
        case 33u: return textureLod(entityAtlases[32], uv, 0.0);
        case 34u: return textureLod(entityAtlases[33], uv, 0.0);
        case 35u: return textureLod(entityAtlases[34], uv, 0.0);
        case 36u: return textureLod(entityAtlases[35], uv, 0.0);
        case 37u: return textureLod(entityAtlases[36], uv, 0.0);
        case 38u: return textureLod(entityAtlases[37], uv, 0.0);
        case 39u: return textureLod(entityAtlases[38], uv, 0.0);
        case 40u: return textureLod(entityAtlases[39], uv, 0.0);
        case 41u: return textureLod(entityAtlases[40], uv, 0.0);
        case 42u: return textureLod(entityAtlases[41], uv, 0.0);
        case 43u: return textureLod(entityAtlases[42], uv, 0.0);
        case 44u: return textureLod(entityAtlases[43], uv, 0.0);
        case 45u: return textureLod(entityAtlases[44], uv, 0.0);
        case 46u: return textureLod(entityAtlases[45], uv, 0.0);
        case 47u: return textureLod(entityAtlases[46], uv, 0.0);
        case 48u: return textureLod(entityAtlases[47], uv, 0.0);
        case 49u: return textureLod(entityAtlases[48], uv, 0.0);
        case 50u: return textureLod(entityAtlases[49], uv, 0.0);
        case 51u: return textureLod(entityAtlases[50], uv, 0.0);
        case 52u: return textureLod(entityAtlases[51], uv, 0.0);
        case 53u: return textureLod(entityAtlases[52], uv, 0.0);
        case 54u: return textureLod(entityAtlases[53], uv, 0.0);
        case 55u: return textureLod(entityAtlases[54], uv, 0.0);
        case 56u: return textureLod(entityAtlases[55], uv, 0.0);
        case 57u: return textureLod(entityAtlases[56], uv, 0.0);
        case 58u: return textureLod(entityAtlases[57], uv, 0.0);
        case 59u: return textureLod(entityAtlases[58], uv, 0.0);
        case 60u: return textureLod(entityAtlases[59], uv, 0.0);
        case 61u: return textureLod(entityAtlases[60], uv, 0.0);
        case 62u: return textureLod(entityAtlases[61], uv, 0.0);
        case 63u: return textureLod(entityAtlases[62], uv, 0.0);
        case 64u: return textureLod(entityAtlases[63], uv, 0.0);
        default: return vec4(1.0);
    }
}

// 2026-10-09：实体 LabPBR 一次取两图（`_n` + `_s`）。与 sampleEntityAtlas 同样
// 必须静态索引（本设备没开 descriptor indexing），所以是 switch 而不是动态下标。
// 槽 0（雨/粒子/非实体）与越界槽走 default ⇒ 返回中性值 ⇒ 调用方判为"无贴图"。
void sampleEntityPbr(
    uint textureSlot,
    vec2 uv,
    out vec4 normalTexel,
    out vec4 specularTexel
)
{
    switch(textureSlot)
    {
        case 1u: normalTexel = textureLod(entityNormalAtlases[0], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[0], uv, 0.0); return;
        case 2u: normalTexel = textureLod(entityNormalAtlases[1], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[1], uv, 0.0); return;
        case 3u: normalTexel = textureLod(entityNormalAtlases[2], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[2], uv, 0.0); return;
        case 4u: normalTexel = textureLod(entityNormalAtlases[3], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[3], uv, 0.0); return;
        case 5u: normalTexel = textureLod(entityNormalAtlases[4], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[4], uv, 0.0); return;
        case 6u: normalTexel = textureLod(entityNormalAtlases[5], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[5], uv, 0.0); return;
        case 7u: normalTexel = textureLod(entityNormalAtlases[6], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[6], uv, 0.0); return;
        case 8u: normalTexel = textureLod(entityNormalAtlases[7], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[7], uv, 0.0); return;
        case 9u: normalTexel = textureLod(entityNormalAtlases[8], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[8], uv, 0.0); return;
        case 10u: normalTexel = textureLod(entityNormalAtlases[9], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[9], uv, 0.0); return;
        case 11u: normalTexel = textureLod(entityNormalAtlases[10], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[10], uv, 0.0); return;
        case 12u: normalTexel = textureLod(entityNormalAtlases[11], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[11], uv, 0.0); return;
        case 13u: normalTexel = textureLod(entityNormalAtlases[12], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[12], uv, 0.0); return;
        case 14u: normalTexel = textureLod(entityNormalAtlases[13], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[13], uv, 0.0); return;
        case 15u: normalTexel = textureLod(entityNormalAtlases[14], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[14], uv, 0.0); return;
        case 16u: normalTexel = textureLod(entityNormalAtlases[15], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[15], uv, 0.0); return;
        case 17u: normalTexel = textureLod(entityNormalAtlases[16], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[16], uv, 0.0); return;
        case 18u: normalTexel = textureLod(entityNormalAtlases[17], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[17], uv, 0.0); return;
        case 19u: normalTexel = textureLod(entityNormalAtlases[18], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[18], uv, 0.0); return;
        case 20u: normalTexel = textureLod(entityNormalAtlases[19], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[19], uv, 0.0); return;
        case 21u: normalTexel = textureLod(entityNormalAtlases[20], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[20], uv, 0.0); return;
        case 22u: normalTexel = textureLod(entityNormalAtlases[21], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[21], uv, 0.0); return;
        case 23u: normalTexel = textureLod(entityNormalAtlases[22], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[22], uv, 0.0); return;
        case 24u: normalTexel = textureLod(entityNormalAtlases[23], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[23], uv, 0.0); return;
        case 25u: normalTexel = textureLod(entityNormalAtlases[24], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[24], uv, 0.0); return;
        case 26u: normalTexel = textureLod(entityNormalAtlases[25], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[25], uv, 0.0); return;
        case 27u: normalTexel = textureLod(entityNormalAtlases[26], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[26], uv, 0.0); return;
        case 28u: normalTexel = textureLod(entityNormalAtlases[27], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[27], uv, 0.0); return;
        case 29u: normalTexel = textureLod(entityNormalAtlases[28], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[28], uv, 0.0); return;
        case 30u: normalTexel = textureLod(entityNormalAtlases[29], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[29], uv, 0.0); return;
        case 31u: normalTexel = textureLod(entityNormalAtlases[30], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[30], uv, 0.0); return;
        case 32u: normalTexel = textureLod(entityNormalAtlases[31], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[31], uv, 0.0); return;
        case 33u: normalTexel = textureLod(entityNormalAtlases[32], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[32], uv, 0.0); return;
        case 34u: normalTexel = textureLod(entityNormalAtlases[33], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[33], uv, 0.0); return;
        case 35u: normalTexel = textureLod(entityNormalAtlases[34], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[34], uv, 0.0); return;
        case 36u: normalTexel = textureLod(entityNormalAtlases[35], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[35], uv, 0.0); return;
        case 37u: normalTexel = textureLod(entityNormalAtlases[36], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[36], uv, 0.0); return;
        case 38u: normalTexel = textureLod(entityNormalAtlases[37], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[37], uv, 0.0); return;
        case 39u: normalTexel = textureLod(entityNormalAtlases[38], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[38], uv, 0.0); return;
        case 40u: normalTexel = textureLod(entityNormalAtlases[39], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[39], uv, 0.0); return;
        case 41u: normalTexel = textureLod(entityNormalAtlases[40], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[40], uv, 0.0); return;
        case 42u: normalTexel = textureLod(entityNormalAtlases[41], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[41], uv, 0.0); return;
        case 43u: normalTexel = textureLod(entityNormalAtlases[42], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[42], uv, 0.0); return;
        case 44u: normalTexel = textureLod(entityNormalAtlases[43], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[43], uv, 0.0); return;
        case 45u: normalTexel = textureLod(entityNormalAtlases[44], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[44], uv, 0.0); return;
        case 46u: normalTexel = textureLod(entityNormalAtlases[45], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[45], uv, 0.0); return;
        case 47u: normalTexel = textureLod(entityNormalAtlases[46], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[46], uv, 0.0); return;
        case 48u: normalTexel = textureLod(entityNormalAtlases[47], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[47], uv, 0.0); return;
        case 49u: normalTexel = textureLod(entityNormalAtlases[48], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[48], uv, 0.0); return;
        case 50u: normalTexel = textureLod(entityNormalAtlases[49], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[49], uv, 0.0); return;
        case 51u: normalTexel = textureLod(entityNormalAtlases[50], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[50], uv, 0.0); return;
        case 52u: normalTexel = textureLod(entityNormalAtlases[51], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[51], uv, 0.0); return;
        case 53u: normalTexel = textureLod(entityNormalAtlases[52], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[52], uv, 0.0); return;
        case 54u: normalTexel = textureLod(entityNormalAtlases[53], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[53], uv, 0.0); return;
        case 55u: normalTexel = textureLod(entityNormalAtlases[54], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[54], uv, 0.0); return;
        case 56u: normalTexel = textureLod(entityNormalAtlases[55], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[55], uv, 0.0); return;
        case 57u: normalTexel = textureLod(entityNormalAtlases[56], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[56], uv, 0.0); return;
        case 58u: normalTexel = textureLod(entityNormalAtlases[57], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[57], uv, 0.0); return;
        case 59u: normalTexel = textureLod(entityNormalAtlases[58], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[58], uv, 0.0); return;
        case 60u: normalTexel = textureLod(entityNormalAtlases[59], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[59], uv, 0.0); return;
        case 61u: normalTexel = textureLod(entityNormalAtlases[60], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[60], uv, 0.0); return;
        case 62u: normalTexel = textureLod(entityNormalAtlases[61], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[61], uv, 0.0); return;
        case 63u: normalTexel = textureLod(entityNormalAtlases[62], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[62], uv, 0.0); return;
        case 64u: normalTexel = textureLod(entityNormalAtlases[63], uv, 0.0); specularTexel = textureLod(entitySpecularAtlases[63], uv, 0.0); return;
        default:
            normalTexel = vec4(0.0, 0.0, 0.0, 1.0);
            specularTexel = vec4(0.0, 0.0, 0.0, 1.0);
            return;
    }
}

vec4 sampleRTAlbedo(uint textureSlot, vec2 uv)
{
    return textureSlot == 0u
        ? textureLod(blockAtlas, uv, 0.0)
        : sampleEntityAtlas(textureSlot, uv);
}

vec4 sampleParticleAtlas(uint textureSlot, vec2 uv)
{
    switch(textureSlot)
    {
        case 1u: return textureLod(particleAtlases[0], uv, 0.0);
        case 2u: return textureLod(particleAtlases[1], uv, 0.0);
        case 3u: return textureLod(particleAtlases[2], uv, 0.0);
        default: return vec4(0.0);
    }
}


vec4 sampleRainStreakAtlas(vec2 uv)
{
    return textureLod(rainStreakAtlas, uv, 0.0);
}

const float VOXY_ATLAS_SIZE = 1024.0;
const float VOXY_TILE_PIXELS = 16.0;

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
    vec3 edge = abs(pos1 - pos0);
    float cellW = max(edge.x, max(edge.y, edge.z));
    float projScale = float(gl_LaunchSizeEXT.y)
        / (2.0 * max(tan(cam.cameraParams.z * 0.5), 0.05));
    float facing = abs(dot(geoNormal, materialRayDirection));
    float cellPx = cellW * projScale * max(facing, 0.02)
        / max(materialHitT, 1.0e-3);
    return clamp(
        log2(VOXY_TILE_PIXELS / max(cellPx, 1.0e-4)),
        0.0,
        4.0
    );
}

vec2 voxyTileSafeUV(vec2 uv, vec2 uv0, vec2 uv1, vec2 uv2, float mip)
{
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
// 顶点字节 6-9 带的是 (textureTileId, normalIndex)（见 VulkanCore.cpp:
// appendDhLodQuads）。这里按 DH 自己的 terrain/blaze/frag.fsh 复现：
//   tileOrigin = vec2(id % 256, id / 256) * 16
//   uv         = (tileOrigin + blockFaceUv() * 16) / atlasSize
//   albedo     = mix(顶点色, clamp(顶点色 * tile.rgb * 2), tile.a)
// blockFaceUv 用命中点的方块小数位置 + normalIndex（0..5 = down/up/north/
// south/west/east），于是侧墙也永远是"一格一张贴图"，不会被拉成帘幕 ——
// 这替换掉了早期那版"把顶点自带的一格 tile 铺满整个 quad"的写法。
//
// mip：tile 是 16px，正好对应一个方块面 ⇒ 用 dhBlockMip（方块在屏幕上的
// 大小）而不是 quad 的跨度，否则外圈大格会采到过粗的 mip 糊成一片。
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
    if(normalIndex < 0.5) return vec2(pos.x, 1.0 - pos.z);
    if(normalIndex < 1.5) return vec2(pos.x, pos.z);
    if(normalIndex < 2.5) return vec2(1.0 - pos.x, 1.0 - pos.y);
    if(normalIndex < 3.5) return vec2(pos.x, 1.0 - pos.y);
    if(normalIndex < 4.5) return vec2(pos.z, 1.0 - pos.y);
    return vec2(1.0 - pos.z, 1.0 - pos.y);
}
// =====================================================
// DH far field: fake-texture "grit" (DH's own applyNoise)
// =====================================================
// 照 .tmp 里 DH 的 terrain/blaze/frag.fsh 逐行复现：只对 tileId == 0 的
// **无纹理**方块施加 hash 噪声假纹理（noiseDropoff=1024，取自
//  Config$Client$Advanced$Graphics$NoiseTexture 的 javap，enableNoiseTexture 默认 true）。
//
// ⚠ 强度/步数是**刻意超出 DH 默认值**的（DH: noiseSteps=4 / noiseIntensity=0.05）。
//   原因：DH 只对"粗到没纹理的层"用噪声——细层（d6~d8）走它的 blockRatioAtlas 真纹理。
//   而我们这边 rendererMode=DISABLED ⇒ DH 的图集永远不创建 ⇒ 我方顶点全部 tileId=0，
//   连近处细层也落进这条无纹理分支。照搬 0.05 会让近处平得像塑料，故调高。
//   图集一旦接上（见 DhTextureHandles），这两项应当调回 DH 默认值。
//
// 与 DH 的两处刻意偏差：
//   1) 噪声格点锚在**世界坐标**而不是 section 局部坐标。DH 用 section 局部坐标
//      ⇒ 每个 section 一个噪声相位，跨 section 的假纹理对不上；锚世界网格则连成
//      一片，观感更统一（且不会随 section 边界闪）。
//   2) rchit 没有 dFdx/dFdy，DH 用它们重建面法线再沿法线推 0.001 只是为了修正
//      vPos 的浮点误差 —— 这里已经有真实几何法线，直接用它做同样的偏移。
//   DH 还会乘 fragColor.a，但 DH LOD 顶点色的 a 恒为 1，故省略（见 vert.vsh）。
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

const float DH_LOD_NEAR_GAIN = 0.85;
const float DH_LOD_FAR_GAIN = 0.65;
const float DH_LOD_NEAR_SAT = 1.0;
const float DH_LOD_FAR_SAT = 1.45;
const float DH_LOD_NEAR_BLOCKS = 96.0;
const float DH_LOD_FAR_BLOCKS = 768.0;

// 两个距离端点之间线性过渡。补偿量本就正比于"合并了多少 texel"，而合并量在
// LOD 层级上是台阶式涨的，所以这里用直的，不用 smoothstep。
vec3 dhLodColorCompensation(vec3 color, vec3 hitPos)
{
    float viewDistance = length(hitPos - cam.viewInverse[3].xyz);
    float t = clamp(
        (viewDistance - DH_LOD_NEAR_BLOCKS) /
        max(DH_LOD_FAR_BLOCKS - DH_LOD_NEAR_BLOCKS, 1.0),
        0.0,
        1.0);
    vec3 gained = color * mix(DH_LOD_NEAR_GAIN, DH_LOD_FAR_GAIN, t);
    float luma = dot(gained, vec3(0.2126, 0.7152, 0.0722));
    return clamp(
        mix(vec3(luma), gained, mix(DH_LOD_NEAR_SAT, DH_LOD_FAR_SAT, t)),
        0.0,
        1.0);
}

vec3 dhLodAlbedo(float tileId, float normalIndex, vec3 hitPos, vec3 geoNormal,
    vec3 vertexColor)
{
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
    return albedo * DH_BAKED_BLOCK_TINT * (DH_BAKED_BLOCK_GAIN * level * level * level);
}

#if 0
vec3 voxyLodAlbedo(float mipShade, vec2 uv, vec3 fallback)
{
    if(mipShade < 0.0)
    {
        return fallback;
    }
    float mip = floor(mipShade);
    float shade = mipShade - mip;
    return textureLod(voxyLodAtlas, uv, mip).rgb * shade;
}
#endif
layout(binding = 4, set = 0) uniform sampler2D normalAtlas;
layout(binding = 5, set = 0) uniform sampler2D specularAtlas;







float sampleLabPBRDepth(
    vec2 uv
)
{
    float a =
        clamp(
            textureLod(
                normalAtlas,
                uv,
                0.0
            ).a,
            0.0,
            1.0
        );


    a +=
        clamp(
            1.0 - a * 1.0e20,
            0.0,
            1.0
        );


    return
        1.0 -
        a;
}



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


// LabPBR `_n.a` as a height, with the "no height data" remap:
// alpha 0 is treated as the top of the field, not the bottom.
float pomHeightAlpha(ivec2 texel, ivec2 lo, ivec2 hi)
{
    texel = clamp(texel, lo, hi);

    float a =
        clamp(
            texelFetch(normalAtlas, texel, 0).a,
            0.0,
            1.0
        );

    return
        a +
        clamp(
            1.0 - a * 1.0e20,
            0.0,
            1.0
        );
}



// 2026-10-18 视差高度场采样开关（设置界面按钮，CameraUBO.pomParams.x）：
//   0（默认 / 旧 DLL）＝ 平滑：双线性 2x2 足迹；
//   > 0.5               ＝ 锐利：单纹素 texelFetch。
// 只切换高度场采样；位移 / 深度 / 自阴影 / 边界哨兵口径都不变。
bool pomSmoothSampling()
{
    return cam.pomParams.x <= 0.5;
}


float sampleLabPBRDepthInSprite(
    vec2 uv,
    vec2 safeMin,
    vec2 safeMax
)
{
    vec2 atlasSizeF =
        vec2(textureSize(normalAtlas, 0));

    vec2 texelSpace =
        uv * atlasSizeF - 0.5;

    ivec2 baseTexel =
        ivec2(floor(texelSpace));

    vec2 f =
        texelSpace - vec2(baseTexel);

    ivec2 lo =
        ivec2(floor(safeMin * atlasSizeF + 1.0e-4));

    ivec2 hi =
        ivec2(ceil(safeMax * atlasSizeF - 1.0e-4)) - ivec2(1);

    hi =
        max(hi, lo);

    if(!pomSmoothSampling())
    {
        ivec2 sharpTexel =
            ivec2(floor(uv * atlasSizeF));

        return
            1.0 -
            pomHeightAlpha(sharpTexel, lo, hi);
    }

    float a00 =
        pomHeightAlpha(baseTexel + ivec2(0, 0), lo, hi);

    float a10 =
        pomHeightAlpha(baseTexel + ivec2(1, 0), lo, hi);

    float a01 =
        pomHeightAlpha(baseTexel + ivec2(0, 1), lo, hi);

    float a11 =
        pomHeightAlpha(baseTexel + ivec2(1, 1), lo, hi);

    float a =
        mix(
            mix(a00, a10, f.x),
            mix(a01, a11, f.x),
            f.y
        );

    return
        1.0 -
        a;
}



#define POM_TILE_SPRITE 1

vec2 wrapPOMSpriteUV(
    vec2 uv,
    vec2 spriteMin,
    vec2 spriteSpan
)
{
    vec2 local =
        (uv - spriteMin) /
        max(spriteSpan, vec2(1.0e-8));

    local -=
        floor(local);

    return
        spriteMin +
        local *
        spriteSpan;
}


float sampleLabPBRDepthTiled(
    vec2 uv,
    vec2 spriteMin,
    vec2 spriteSpan,
    vec2 safeMin,
    vec2 safeMax
)
{
    return
        sampleLabPBRDepthInSprite(
            min(
                wrapPOMSpriteUV(uv, spriteMin, spriteSpan),
                safeMax
            ),
            safeMin,
            safeMax
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
            materialRayOrigin +
            materialRayDirection *
            materialHitT;

        result.valid =
            false;

        return result;
    }



    vec3 viewDirTS =
        normalize(
            transpose(tbn) *
            (-worldRayDirection)
        );


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


    vec2 totalUVOffset =
        (
            viewDirTS.xy /
            stableViewZ
        ) *
        spriteSpan *
        pomDepthScale *
        grazingFade;

#if POM_TILE_SPRITE
    vec2 totalTileOffset =
        totalUVOffset /
        max(spriteSpan, vec2(1.0e-8));

    totalTileOffset =
        sign(totalTileOffset) *
        min(
            abs(totalTileOffset),
            vec2(1.0)
        );

    totalUVOffset =
        totalTileOffset *
        spriteSpan;
#endif


    vec2 currentUV =
        baseUV;


    float currentLayerDepth =
        0.0;


    float currentMapDepth =
        sampleLabPBRDepthInSprite(
            currentUV,
            safeMin,
            safeMax
        );



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

#if POM_TILE_SPRITE
        nextUV =
            wrapPOMSpriteUV(
                nextUV,
                spriteMin,
                spriteSpan
            );
#else
        if(!isPOMSpriteUVInBounds(nextUV, safeMin, safeMax))
        {
            break;
        }
#endif

currentUV =
    nextUV;

currentLayerDepth =
    nextLayerDepth;

currentMapDepth =
    sampleLabPBRDepthTiled(
        currentUV,
        spriteMin,
        spriteSpan,
        safeMin,
        safeMax
    );

    }


    ParallaxHit result;


    result.uv =
        clamp(
            currentUV,
            safeMin,
            safeMax
        );


    vec2 pomAtlasSize =
        max(
            vec2(textureSize(normalAtlas, 0)),
            vec2(1.0)
        );

    vec2 edgeDistanceTexels =
        min(
            (result.uv - safeMin) * pomAtlasSize,
            (safeMax - result.uv) * pomAtlasSize
        );

    float nearestEdgeTexels =
        max(
            min(edgeDistanceTexels.x, edgeDistanceTexels.y),
            0.0
        );

    result.borderScale =
        smoothstep(
            0.0,
            2.0,
            nearestEdgeTexels
        );


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
        materialRayOrigin +
        materialRayDirection *
        materialHitT;


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


    float lightZ =
        max(
            lightDirTS.z,
            0.05
        );


    float grazingBoost =
        clamp(
            0.25 / lightZ,
            1.0,
            4.0
        );


    const int VL_POM_SELF_SHADOW_MAX_SAMPLES = 24;


    int sampleCount =
        int(
            round(
                mix(
                    8.0,
                    4.0,
                    facing
                ) *
                grazingBoost
            )
        );


    sampleCount =
        clamp(
            sampleCount,
            4,
            VL_POM_SELF_SHADOW_MAX_SAMPLES
        );


    const float SELF_SHADOW_FULL_NORMALIZED_DEPTH =
        0.02;


    float maxBlockDepthNormalized =
        0.0;


    vec2 selfShadowTileOffset =
        (
            lightDirTS.xy /
            lightZ
        ) *
        pomDepthScale;

#if POM_TILE_SPRITE
    selfShadowTileOffset =
        sign(selfShadowTileOffset) *
        min(
            abs(selfShadowTileOffset),
            vec2(1.0)
        );
#endif


    for(int i = 1; i <= VL_POM_SELF_SHADOW_MAX_SAMPLES; ++i)
    {
        if(i > sampleCount)
        {
            break;
        }


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
            selfShadowTileOffset *
            spriteSpan *
            depthRise;


#if POM_TILE_SPRITE
        float blockerDepth =
            sampleLabPBRDepthTiled(
                sampleUV,
                spriteMin,
                spriteSpan,
                safeMin,
                safeMax
            );
#else
        if(!isPOMSpriteUVInBounds(sampleUV, safeMin, safeMax))
        {
            break;
        }

        float blockerDepth =
            sampleLabPBRDepthInSprite(
                sampleUV,
                safeMin,
                safeMax
            );
#endif


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



// Voxy translucent tile test: a fully covered tile is a real glass sheet, anything
// with holes is cutout foliage that shades as opaque.  Extracted from
// rt_test_translucent.rchit (2026-10-20) so the glass material body can run in ray
// generation too; needs VOXY_ATLAS_SIZE / VOXY_TILE_PIXELS / voxyLodAtlas, all
// already declared above.
bool voxyTileIsFullyCovered(vec2 uv0, vec2 uv1, vec2 uv2)
{
    vec2 tileOrigin = min(uv0, min(uv1, uv2)) - vec2(0.5 / VOXY_ATLAS_SIZE);
    vec2 tileCentre = tileOrigin
        + vec2((VOXY_TILE_PIXELS * 0.5) / VOXY_ATLAS_SIZE);
    return textureLod(voxyLodAtlas, tileCentre, 4.0).a >= 0.985;
}


#endif
