#version 460
#extension GL_GOOGLE_include_directive : require
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference2 : require
#extension GL_EXT_scalar_block_layout : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// LOD / 远场几何的角色表（binding 19）。DH 远场的识别由它回答，pad1 只再表示材质编号。
#include "lod_coverage.glsl"
// 2026-10-07：为用 glassVertexFactorIsFaceShade() 判"普通玻璃"（顶点色里没有注入
// 染料）而包含。与 rt_test_translucent.rchit 共用**同一份**实现与同一个开关
//（GLASS_TINT_NO_DDA），避免两份判据漂移 —— 那个文件一直就是这么包含的。
#define GLASS_TINT_NO_DDA 1
#include "glass_tint_common.glsl"

// 2026-10-20: 结构体单源化，见 hit_payload.glsl（六个 shader 共用同一份布局）。
#include "hit_payload.glsl"
layout(location = 0) rayPayloadInEXT HitPayload payload;

const uint RT_STATE_PURPOSE_MASK       = 0x7u;
const uint RT_STATE_HIT_CLASS_SHIFT    = 3u;
const uint RT_STATE_WATER_EVENT_SHIFT  = 5u;
const uint RT_STATE_GLASS_BIT          = 1u << 7u;
const uint RT_STATE_GLASS_BOTTOM_BIT   = 1u << 8u;
const uint RT_STATE_PROFILE_SHIFT      = 9u;
// 2026-10-07：普通（未染色）玻璃标记，bit 12 —— 与 rt_test_translucent.rchit 同名同值。
// 置位者见下方 t12IsGlass 分支，消费者见 rgen 的 isClearGlassHit()（"当空气处理"）。
const uint RT_STATE_CLEAR_GLASS_BIT    = 1u << 12u;
// rgen 侧同名同值（rt_test.rgen:6053）。补在这里，供本 shader 的"按射线用途分级"
// 快速路径识别 —— 与下面 RT_PURPOSE_DISTANCE_ONLY 的补法同源（见其上注释）。
const uint RT_PURPOSE_FULL             = 0u;
const uint RT_PURPOSE_CLASSIFY         = 1u;
// 2026-10-06：rgen 的 RR-6.2 主水反射 guide 射线（rgen:RT_PURPOSE_DISTANCE_ONLY）
// 一直在打本 shader 跑完整着色。补上同名常量供快速路径识别（rgen 侧定义不变）。
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

// 2026-10-23：原来这里是 5 份副本之一，已收进透明剖面共享头。
// 本 TU 不 include material_common.glsl（它依赖 rgen 侧的 payload/灯光声明），
// 所以直接引这个无依赖的小头。
#include "transparent_profile_common.glsl"
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

// =====================================================
// 2026-10-09 运动矢量（DLSS-RR）：顶点缓冲**尾部**那段"上一帧位移"。
//
// 起点 = 顶点地址 + 表项的 .z（字节偏移，见 GPUSectionVertexAddress）；每顶点
// 3 个 float，与顶点向量**逐顶点同序**，所以第 i 个顶点的位移就是
// motion[i*3 .. i*3+2]。
//
// ⚠ 段里存的是**相机相对**位移（prev − cur），不是世界位移：Java 只能量到相机
//   相对的量。本文件在读到它之后、写进 payload 之前，用 UBO 的 viewInverse
//   平移列把 Δcam 补上（见下方那一段），所以 payload 里出去的是世界位移。
//
// ★ 刻意声明成 `float motion[]` 而不是 `vec3 motion[]`：vec3 数组在不同
//   block layout 下的 stride 有 12/16 两种可能，而这里必须**恰好 12 字节**
//   （C++ 侧就是 12B/顶点）。用 float[] + 手动乘 3 把这件事钉死，不依赖
//   scalar layout 对 vec3 的处理细节。
// =====================================================
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

// One entry per TLAS Section instance, selected by
// VkAccelerationStructureInstanceKHR::instanceCustomIndex.
//
// 2026-10-09 运动矢量：表项 8B → **16B**（uvec4）。四个 lane：
//   .xy = 顶点缓冲的 64 位设备地址（原样）
//   .z  = 该 Section 顶点缓冲里"上一帧位移"段（**相机相对**）的**字节偏移**
//         （0 = 没有；closest-hit 补 Δcam 之后才是世界位移）
//   .w  = 位移段里的 float 个数（顶点数 × 3），仅供调试核对
// ⚠ 必须与 VulkanCore.cpp 的 GPUSectionVertexAddress 保持逐字节一致；这里改成
//   uvec2 会让 CPU 侧按 16B 步长写的地址被半个 entry 错位读走（整片几何塌掉）。
layout(std430, binding = 21, set = 0) readonly buffer SectionVertexAddressBuffer {
    uvec4 vertexAddresses[];
} sectionVertexAddressBuffer;
layout(binding = 2, set = 0) uniform CameraProperties
{
    #include "camera_ubo.glsl"
} cam;

// The natural wave bands, the persistent interaction field and the binding-33
// sampler all live in the shared include.  rt_test.rgen includes the same file
// for its analytic caustics, so the caustic pattern cannot drift away from the
// visible water normal again.
//
// 2026-10-13 退役：路线 A（WATER_WAVES_NOISETEX）曾在 closest-hit 里把
// cloudShapeNoise 当主波带采样，故这里补过一份 binding 38 声明。该后端已被
// afl getwaves 取代（用户否决噪声贴图），本声明随之失去调用点 ⇒ 屏蔽保留。
// 复活步骤：删掉下面这对 #if 0 / #endif，并把 C++ 里 binding 38 的 stageFlags
// 加回 CLOSEST_HIT（见 VulkanCore.cpp 的 b[36]）。
#if 0
layout(binding = 38, set = 0) uniform sampler3D cloudShapeNoise;
#endif // 0 (退役的路线 A closest-hit 采样)
#include "water_wave_field.glsl"

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

    // Keep every rain-impact dimension 40% smaller than the prior dense
    // field; the 3x3 analytic neighbourhood and ray budget are unchanged.
    const float CELL_SIZE = 0.432;
    const float RING_SPEED = 0.336;
    const float RING_FREQUENCY = 66.66667;
    // CameraUBO.padding is the native steady-clock bit pattern.  Keep rain
    // impacts independent of render FPS and Minecraft day-time progression.
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
    float cameraDistance;
    vec3 surfacePos = vec3(worldXZ.x, waterY, worldXZ.y);
    float noV = abs(normalize(surfacePos - cam.viewInverse[3].xyz).y);
    float horizonFade = 1.0 - exp(-4.0 * noV);
    // 2026-10-18 方案 A：斜率图 mip 级由屏幕足迹定（仿 voxyLodMip），显式传给采样。
    float projScale = float(gl_LaunchSizeEXT.y)
        / (2.0 * max(tan(cam.cameraParams.z * 0.5), 0.05));
    float waveLod = waterFftSlopeLodFromProj(surfacePos, gl_HitTEXT, projScale);
    vec2 naturalSlope = waterNaturalWaveGradient(worldXZ, cameraDistance, horizonFade, waveLod);

    // Persistent Eclipse-style interaction field.  The physical water plane
    // is unchanged; only its optical normal receives the stored height slope.
    // This is gated to the actual water elevation reported for the player so
    // vertically separated ponds cannot share one disturbance.
    float stateAndBorderMask;
    vec2 interactionSlope =
        waterInteractionSlope(worldXZ, stateAndBorderMask);
    float elevationMatch = 1.0 - smoothstep(
        0.65, 1.50, abs(waterY - cam.waterSimSurfaceAndOrigin.x)
    );
    // 2026-10-02 参考重构（HPWater）：自然波与交互场改用参考的"XZ 相加 / Y 相乘"
    // 混合（water_wave_field.glsl 的 waterBlendWaveSlopes，斜率空间的精确等价式）。
    // 混合模式为 0 时逐位回旧档（直接相加）。
    vec2 gradient = waterBlendWaveSlopes(
        naturalSlope,
        interactionSlope * (stateAndBorderMask * elevationMatch),
        waterWaveBlendMode()
    );

    // Rain uses the analytic field above instead of injecting the local
    // player-water simulation.  That makes visible water at any elevation
    // respond while the player is on land, and leaves the 512x512 simulation
    // reserved for actual player entry/exit and movement wakes.
    // （雨滴是高频细节，保持加性、不参与波面混合 —— 参考里没有对应项。）
    gradient += rainImpactRippleGradient(worldXZ, cameraDistance);

    WaterWaveSurface result;
    result.normal = normalize(vec3(-gradient.x, 1.0, -gradient.y));
    return result;
}

// 2026-10-06 近水掉帧降本：CLASSIFY 射线专用的降本波面。classify 只消费界面
// 法线做能量 march（太阳透射 / 水面反射太阳），第 3/4 八度（≈4.4/2.3 格波长）
// 在 Beer-Lambert 积分里不可见 ⇒ Seascape 封顶（只保留基波，折射方向对透射
// 能量的影响是角度级的、远低于噪声底）。交互场保留（玩家尾流对透射/反射太阳
// 仍有贡献，按水面高程门控，与完整路径同一口径）；雨纹本来就被
// rainImpactRippleGradient 的 purpose 检查跳过，这里不再调用。可见水面着色仍走
// evaluateWaterWaveSurface，观感不变。
// 回退：把这个常量改回 4（= 完整波面）。
const int WATER_CLASSIFY_WAVE_OCTAVES = 1;

WaterWaveSurface evaluateWaterWaveSurfaceClassify(vec2 worldXZ, float waterY)
{
    float cameraDistance;
    vec3 surfacePos = vec3(worldXZ.x, waterY, worldXZ.y);
    float noV = abs(normalize(surfacePos - cam.viewInverse[3].xyz).y);
    float horizonFade = 1.0 - exp(-4.0 * noV);
    float projScale = float(gl_LaunchSizeEXT.y)
        / (2.0 * max(tan(cam.cameraParams.z * 0.5), 0.05));
    float waveLod = waterFftSlopeLodFromProj(surfacePos, gl_HitTEXT, projScale);
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
// 2026-10-06：实体纹理槽 16 → 32。容量 16 时槽表常年满载，一帧内新出现的纹理
// **必然**拿不到槽而被丢弃（第一人称手、末地传送门就是这么消失的）。最后一项
// 留给本地玩家身体代理，普通实体纹理用 1..(N-1)。
// 2026-10-13：32 → 64。必须与 VulkanCore.cpp 的 ENTITY_RT_TEXTURE_SLOT_COUNT
// 及 EntityRtCapture.MAX_TEXTURE_SLOTS 同步，并且下面的 switch 必须补满 N 个
// case（本设备没开 descriptor indexing，只能静态索引）。
layout(binding = 36, set = 0) uniform sampler2D entityAtlases[64];
// 2026-10-09：实体 LabPBR（`_n`/`_s`）。与 entityAtlases 同槽、同 UV（Java 按
// "同一 Identifier 加 _n/_s" 查找），C++ 侧与 binding 36 同构：每槽一个 view。
// 没有贴图的槽由 C++ 填中性 1×1 兜底（normal=(0,0,0,1)、specular=(0,0,0,1)），
// 于是下面的内容判据自动判"无贴图"而不需要额外的标志位。
layout(binding = 63, set = 0) uniform sampler2D entityNormalAtlases[64];
layout(binding = 64, set = 0) uniform sampler2D entitySpecularAtlases[64];
layout(binding = 37, set = 0) uniform sampler2D particleAtlases[3];
layout(binding = 40, set = 0) uniform sampler2D rainStreakAtlas;
layout(binding = 45, set = 0) uniform sampler2D voxyLodAtlas;
// DH 自己的 blockRatioAtlas（= DH 着色器里的 uBlockAtlas），16px tile、256 个/行。
// 图集没就绪时 C++ 会把它回落到原版方块图集，所以采样前必须确认顶点 tileId != 0
// （Java 侧只在真句柄推送成功后才写 tileId）。
layout(binding = 49, set = 0) uniform sampler2D dhBlockRatioAtlas;

vec4 sampleEntityAtlas(uint textureSlot, vec2 uv)
{
    // Keep every sampler operand statically indexed.  The current device is
    // created by Java without descriptor-indexing feature negotiation, while
    // closest-hit invocations naturally select different entity textures.
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

// 2026-10-03（用户要求）：雨可见度统一 2× 增益（rgen 里同名常量，两边必须一起改）。
// 这里作用于雨条本体：albedo ×2 ⇒ 雨条辐射度 ×2（radiance = albedo × 天空辐照度）。
const float RAIN_VISIBILITY_GAIN = 2.0;

vec4 sampleRainStreakAtlas(vec2 uv)
{
    return textureLod(rainStreakAtlas, uv, 0.0);
}

// =====================================================
// Voxy LOD atlas sampling (v11)
//
// The bridge bakes one 16x16 tile per face texture into a
// 256x256 atlas (9-level mip chain; a tile stays isolated
// down to mip 4 = one texel) and emits one quad per LOD cell
// face in world block coordinates.  Voxy's rasterizer picks
// the mip from screen-space UV derivatives; a hit shader has
// none, so texel density is reconstructed analytically:
//
//   cellPx = cellW * screenH / (2 tan(fov/2) * dist) * facing
//   mip    = clamp(log2(16 / cellPx), 0, 4)
//
// facing = |dot(normal, rayDir)| reproduces the hardware
// max-derivative mip choice at grazing angles (stable
// distance terrain instead of shimmer).  Mip 4 is the hard
// cap: below it a tile is sub-texel and neighbouring tiles
// would bleed.  The sample UV is inset-clamped to the owning
// tile so bilinear/trilinear filtering never crosses a tile
// border at any mip.
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
    if(normalIndex < 0.5) return vec2(pos.x, 1.0 - pos.z);        // down
    if(normalIndex < 1.5) return vec2(pos.x, pos.z);              // up
    if(normalIndex < 2.5) return vec2(1.0 - pos.x, 1.0 - pos.y);  // north
    if(normalIndex < 3.5) return vec2(pos.x, 1.0 - pos.y);        // south
    if(normalIndex < 4.5) return vec2(pos.z, 1.0 - pos.y);        // west
    return vec2(1.0 - pos.z, 1.0 - pos.y);                        // east
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
    // -(2x-1)^2+1：越暗或越亮的地方噪声越弱，中间调最强。
    float amplification =
        (1.0 - pow(luminance * 2.0 - 1.0, 2.0)) * DH_NOISE_INTENSITY;

    float randomValue =
        dhNoiseRand(grid) * 2.0 * amplification - amplification;
    vec3 newColor = clamp(albedo + (1.0 - albedo) * randomValue, 0.0, 1.0);

    // 越远越淡（DH: min(viewDist / dropoff, 1)）。viewDist 是到相机的距离。
    float viewDistance = length(hitPos - cam.viewInverse[3].xyz);
    float distanceFade = min(viewDistance / DH_NOISE_DROPOFF, 1.0);
    return mix(newColor, albedo, distanceFade);
}

// =====================================================
// DH 平价色补偿（"LOD 那一片为什么亮一点"）
// =====================================================
// DH 顶点色的那个"平面色"是**在线性空间求的平均**：ClientBlockStateColorCache
// .calculateColorFromTexture 用 srgbToLinearTable 把每个不透明 texel 转线性、
// 加权求和，再用 linearToSrgb 编码回 8 位（见其字节码）。
// 而近场是**逐 texel 采样贴图**、把 sRGB 字节当线性用，于是在 LOD 接缝处被
// 降噪器平均出来的就是"同一个面所有 texel 的 sRGB 均值"。
// encode 是凹函数 ⇒ encode(E[L]) ≥ E[encode(L)]：
//   DH 的平面色 **永不比** 同一批 texel 的 sRGB 均値暗，贴图反差越大差得越多
//   （树叶/石砖 >> 纯色方块）。这就是接缝两侧"同一块地，远的那半分更亮"的直接来源。
//
// 我们没有贴图分布，无法精确反演，只能按典型方块贴图的统计差补一个增益：
//   反差中等的贴图（草/泥土）实测比值 ≈ 0.93，反差大的（树叶/圆石）≈ 0.81~0.84，
//   取中间偏保守的 0.85。
// ⚠ 2026-10-01：这个偏差**正比于合并层级**。一个 LOD quad 越粗，它平均掉的
//   texel 越多，encode(E[L]) - E[encode(L)] 越大 ⇒ 越远越亮越灰。所以补偿不能
//   是一个常数，必须随距离爬升。
//
// 实测（2026-10-01 夕阳，DH 远场草地）：远场 ≈ RGB(185,220,198)，Y=0.82、S=0.15；
// 同屏近场 ≈ RGB(81,102,88) Y=0.38 与 RGB(54,60,34) Y=0.22 —— 亮 2~3 倍。
//
// 旧写法只在 tileId == 0 的平价色分支里乘一个 0.85。一旦 DH 的 blockRatioAtlas
// 接上（publishTextures=true + 重启），顶点全部带真 tileId ⇒ 那段补偿**整段
// 失效**，亮度爬升就回来了。现在把补偿提到两条分支**之后**统一做。
//
// DH_LOD_NEAR_GAIN 沿用旧值 0.85（近场档的档位不变），DH_LOD_FAR_GAIN 是最粗层
// 的档位。刻意不压到与近场同亮 —— 远处本来就该淡一点，这里只削掉大部分爬升。
// 观感偏亮就调小 DH_LOD_FAR_GAIN，偏暗就调大；要整体关掉把两个都设回 1.0。
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
    // ⚠ 增益是**乘法**，只能压亮度，动不了彩度。而线性空间平均除了抬亮，还会把
    //   每个通道往均值拉 ⇒ 饱和度一起被吃掉（实测远场草地 S=0.15，近场 S=0.21，
    //   比 0.71）。所以必须另补一步：以亮度为轴等比放大彩度。
    //   远端 1.45 ≈ 0.21/0.15 的倒数；近场档 1.0 = 不动，保证近场逐字节不变。
    float luma = dot(gained, vec3(0.2126, 0.7152, 0.0722));
    return clamp(
        mix(vec3(luma), gained, mix(DH_LOD_NEAR_SAT, DH_LOD_FAR_SAT, t)),
        0.0,
        1.0);
}

vec3 dhLodAlbedo(float tileId, float normalIndex, vec3 hitPos, vec3 geoNormal,
    vec3 vertexColor)
{
    // tileId 0 = DH 自己的"无纹理"约定，同时也是"图集还没就绪"的占位
    // （Java 侧只在真句柄推送成功后才写 tileId，见 DhTextureHandles.isPublished）。
    // DH 对这类方块补一层假纹理噪声（applyNoise），远场因此才有颗粒感。
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

// v10 fixed-mip sampling (bridge selected mip = LOD ring level):
// superseded by the analytical density estimate above.  Disabled,
// kept as a future interface option.
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


    // LabPBR `_n.a == 0` means "no height data"; we remap it to the
    // top of the field (`sh += saturate(1 - sh*1e20)`).  Read as maximum depth
    // it pushed flat regions to the bottom of the height field.
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


// =====================================================
// PARALLAX_MODE == 2 height sampling
// (BilinearHeightSample).
//
// Nearest-texel sampling snapped the POM hit to whole atlas texels, which
// stair-stepped the relief and broke the displaced albedo up with it.  A
// bilinear read of the same height field keeps the surface continuous.
//
// The 2x2 footprint is clamped to the owning sprite's texel range, so bilinear
// still cannot read the neighbouring atlas cell -- the seam the sprite guards
// exist to prevent.
// =====================================================

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

    // 2026-10-18 用户开关：锐利档退回单纹素采样，
    // 默认平滑档继续走下面的双线性。
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


// =====================================================
// POM sprite tiling
//
// A Minecraft block face spans exactly one sprite period, so the sprite IS the
// tile: the neighbouring block of the same material carries this identical
// sprite, and its relief is already present inside these bounds.  Wrapping a
// traversal position periodically therefore continues the height field across
// a block edge with no neighbour lookup at all.
//
// The returned point is always strictly inside the sprite period -- fract()
// yields [0,1) -- so it can never land on the far edge and fetch the next
// atlas cell.
//
// 1 = wrap (default).  The previous rule stopped the march at the sprite edge
//     and kept the last in-sprite sample, which left a real displacement step
//     at every edge shared by two same-material blocks.  Set to 0 to restore
//     it: the guarded branches below then compile back to the old behaviour.
// =====================================================

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
    // Texel fetch floors this coordinate, so hold it off the far sprite edge.
    // For an already in-bounds uv this is identical to the direct fetch.
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

#if POM_TILE_SPRITE
    // A block face spans exactly one sprite period, so one tile of tile-space
    // offset is "at most the immediately adjacent block".  Without this cap a
    // grazing-angle traversal reaches two or more tiles -- the offset in tiles
    // is |viewDirTS.xy / stableViewZ| * pomDepthScale, which passes 1.0 from
    // about 0.2 block depth at 0.1 view Z -- and would then fetch relief from
    // blocks that are nowhere near the hit.  That runaway is exactly what the
    // sprite confinement used to prevent; this reproduces the bound while
    // still allowing the single tile of continuation a block edge needs.
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

#if POM_TILE_SPRITE
        // Continue the traversal into the next tile of the same sprite rather
        // than stopping at the block edge.  The neighbouring same-material
        // block owns this identical sprite, so this is its relief too, and the
        // now shared height field is what removes the displacement step.
        nextUV =
            wrapPOMSpriteUV(
                nextUV,
                spriteMin,
                spriteSpan
            );
#else
        // Do not wrap, clamp or blend a traversal position outside the
        // owning sprite.  Keep currentUV/currentMapDepth, the last valid
        // height-field point on this compiled Minecraft quad.
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


    // Keep the continuous final POM coordinate.  Snapping the result to the
    // centre of the outer texel made neighbouring block faces select visibly
    // different lighting samples and exposed a bright seam.
    result.uv =
        clamp(
            currentUV,
            safeMin,
            safeMax
        );


    // Only the normal/self-shadow terms converge in the outermost atlas
    // texel.  POM UV displacement, depth and world-space ray travel remain
    // untouched, so this seam guard cannot pull the surface back to the
    // original triangle plane.
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


    float lightZ =
        max(
            lightDirTS.z,
            0.05
        );


    // =====================================================
    // 2026-10-14 掠射角自阴影（实机症状："日落把视差浮雕点亮"）
    // =====================================================
    // 取证：正午（仰角大）浮雕自阴影正常，日落（仰角 ~7.5°）整片消失。
    //
    // 机制：march 的水平步幅 = lightDirTS.xy / lightZ * pomDepthScale（见下面的
    // selfShadowTileOffset）—— **太阳越低步幅越大**。而样本数是固定 4~8 个 ⇒
    // 掠射时相邻样本在贴图上的水平间隔可达 0.1 格以上（≈2 texel），直接**跨过**
    // 浮雕的凸起 ⇒ blockDepth 一路判不到 ⇒ 返回 1.0（毫无自遮蔽）⇒ 浮雕在日落
    // 被压平点亮。正午 lightZ ≈ 1 ⇒ 同一段路密得多 ⇒ 正常。
    //
    // 修法：让采样密度跟着 1/lightZ 一起涨（上限 4 倍）。**射程不变、只把同一段
    // 路走细**，所以遮蔽的**位置**不会被改变，只有"抓不抓得到凸起"变了。
    //
    // ⚠ 正午 lightZ ≥ 0.25 ⇒ boost = 1 ⇒ 采样数与旧档**逐位相同**（不动正午画面）。
    //   boost = 0.25 / lightZ：15° 起才 > 1，7.5° 时约 1.9，2.9° 封顶 4 倍。
    // 回退：把 grazingBoost 恒置 1.0 即逐位旧档。
    // =====================================================
    float grazingBoost =
        clamp(
            0.25 / lightZ,
            1.0,
            4.0
        );


    // 2026-10-14：掠射角下的样本上限（旧档固定 8 —— 那正是日落丢自阴影的原因，
    // 见上面 grazingBoost 的取证）。24 与 4 倍密度上限配对，成本只在低太阳角发生。
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


    // =====================================================
    // 2026-10-19 自阴影判据改为**归一化高度差**：
    // `parallaxShadow *= saturate(diff * 100.0 + 0.5)`，不再乘
    // pomDepthScale。
    //
    // 为什么改：旧档按**世界深度**判定满遮蔽（0.03 格），而 pomDepthScale
    // 默认只有 0.03 ⇒ 需要 100% 的归一化高度差才够；实测浮雕高度差约 0.1
    // 时 blockDepthWorld≈0.003，smoothstep(0,0.03,0.003)≈0.03 ⇒ 遮蔽只有
    // 3%，等于没有自阴影（画面表现：凸起没有接触暗边、整体发平）。
    //
    // 数值口径：参考在 diff≈0.005 就满遮蔽；这里取 0.02 —— 比参考软 4 倍，
    // 避免 4~8 步（掠射上限 24）在 0.005 下产生成片黑斑/噪点。
    // 调强 = 改小；调弱 = 改大。**不增加任何采样**：只换比较口径，
    // 采样数、早退时机与旧档同量级（早退更早 ⇒ 实际略省）。
    // =====================================================
    const float SELF_SHADOW_FULL_NORMALIZED_DEPTH =
        0.02;


    float maxBlockDepthNormalized =
        0.0;


    // Hoisted from the loop and, under POM_TILE_SPRITE, held to the same one
    // tile bound as the main traversal so the blocker search cannot reach
    // height data from blocks that are not adjacent to the hit either.
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
            selfShadowTileOffset *
            spriteSpan *
            depthRise;


#if POM_TILE_SPRITE
        // The height field tiles with the sprite, so the blocking relief of
        // the neighbouring same-material block is the wrapped sample here.
        // Without this the self-shadow still broke at the block edge even
        // though the traversal above no longer did.
        float blockerDepth =
            sampleLabPBRDepthTiled(
                sampleUV,
                spriteMin,
                spriteSpan,
                safeMin,
                safeMax
            );
#else
        // A light ray leaving the owning sprite has no in-sprite height
        // blocker.  Do not wrap it to the opposite edge.
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


        // Positive means the light ray is underneath a
        // neighbouring height-field surface and is blocked.
        //
        // 2026-10-19: 归一化偏置从 0.012 降到 0.004。旧偏置会直接吃掉
        // 0.02 阈值里的 60%，浅浮雕（高度差 <0.012）依然完全无阴影；
        // 保留一点偏置是为了压住双线性高度场与 march 层的数值抖动。
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
// 2026-10-23：延迟材质整段删除后，本 TU 是唯一的材质求值点——命中着色器通过
// resolveHitMaterial() 就地求完整个材质。
// =============================================================================
// 材质体（共享体在 material_resolve.glsl）。放在这里，确保 include 点之前
// 它调用的每个 helper 都已声明。
//
// LOD role indirection: lod_coverage.glsl 的 lodInstanceRole() 读 gl_InstanceCustomIndexEXT，
// 那是命中着色器的内建；rgen 没有它。共享体因此只认 materialLodRole()，本 TU 直接转调
// 原生实现（参数与内建同值，逐位等价）。
uint materialLodRole(uint instanceIndex)
{
    return lodInstanceRole();
}

// 共享解析体写入它；本 TU 的材质 helper 仍直接读 gl_HitTEXT，这里只是让共享体
// 能编译。三个全局量都在 resolveHitMaterial() 开头被写入，初值只为满足声明，
// 不参与求值。
float materialHitT = 0.0;
vec3 materialRayOrigin = vec3(0.0);
vec3 materialRayDirection = vec3(0.0, 0.0, -1.0);

#include "material_resolve.glsl"

void main()
{
    // 2026-10-21 根因修复（生产模式崩溃的真正原因）：
    // resolveHitMaterial() 的 miss 判定读 payload.hitDistance，取命中身份读
    // payload.normal.x/.y/.z 与 payload.roughness（见 material_resolve.glsl:41-63）。
    // 重构这段播种时丢了 ⇒ 生产模式拿**上一条射线的陈旧值**去索引
    // sectionVertexAddressBuffer.vertexAddresses[instanceIndex]（无边界检查）：
    // 越界读到垃圾指针，再被 Vertices(rawAddr) 当 buffer_reference 解引用 ⇒
    // GPU 挂死，"Item in hand" 的 RT 注入 5 秒等不到信号量。
    // 延迟档由派发器播种（rt_test.rgen:18041-18042），所以只有生产模式崩。
    payload.hitDistance = gl_HitTEXT;
    payload.normal = vec3(
        float(gl_InstanceCustomIndexEXT),
        float(gl_PrimitiveID),
        attribs.x
    );
    payload.roughness = attribs.y;
    resolveHitMaterial(gl_WorldRayOriginEXT, gl_WorldRayDirectionEXT);
}

