#ifndef LOCAL_LIGHT_GRID_COMMON_GLSL
#define LOCAL_LIGHT_GRID_COMMON_GLSL

// ---- binding 12：本地光表（与 rgen 的 binding 17 同一 buffer）----
// 记录格式与 C++ 的 GPULocalLightRecord 逐字节对应（32 字节）：
//   worldPosEmission = (blockX, blockY, blockZ, emission 0..15)
//   meta.x = typeCode（decode 用；0 = 未分类）
struct LocalLightGPU
{
    vec4 worldPosEmission;
    uvec4 meta;
};

layout(std430, binding = 12, set = 0) readonly buffer FogLocalLightBuffer
{
    uvec4 header;
    LocalLightGPU lights[];
} fogLightBuffer;
layout(std430, binding = 13, set = 0) readonly buffer FogLocalLightGridBuffer
{
    uint words[];
} fogLightGrid;

const float LOCAL_LIGHT_GRID_CELL_SIZE = 64.0;
const float LOCAL_LIGHT_MAX_RANGE = 30.0;
const float VPT_LOCAL_MAX_DISTANCE = 30.0;
const float VPT_LOCAL_RANGE_PER_EMISSION = 2.0;
const float VPT_LOCAL_FADE_START_FRACTION = 0.40;
const float LOCAL_LIGHT_ENERGY_SCALE = 0.35;

const int FOG_LIGHT_MAX = 8;

uint localLightGridHash32(ivec3 cellCoord)
{
    uint h = uint(cellCoord.x) * 0x8da6b343u;
    h ^= uint(cellCoord.y) * 0xd8163841u;
    h ^= uint(cellCoord.z) * 0xcb1ab31fu;
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return h;
}

ivec3 localLightGridCellCoord(vec3 worldPos)
{
    return ivec3(floor(worldPos / LOCAL_LIGHT_GRID_CELL_SIZE));
}

int localLightGridNeighborMinOffset(float worldComponent)
{
    float cellBase = floor(worldComponent / LOCAL_LIGHT_GRID_CELL_SIZE) *
        LOCAL_LIGHT_GRID_CELL_SIZE;
    return worldComponent - cellBase < LOCAL_LIGHT_MAX_RANGE ? -1 : 0;
}

int localLightGridNeighborMaxOffset(float worldComponent)
{
    float cellBase = floor(worldComponent / LOCAL_LIGHT_GRID_CELL_SIZE) *
        LOCAL_LIGHT_GRID_CELL_SIZE;
    return cellBase + LOCAL_LIGHT_GRID_CELL_SIZE - worldComponent <
        LOCAL_LIGHT_MAX_RANGE ? 1 : 0;
}

uint localLightGridPackedIndexBase()
{
    return 4u + fogLightGrid.words[2] * 8u;
}

bool localLightGridLookupCell(
    ivec3 cellCoord,
    out uint firstLightIndex,
    out uint lightCount)
{
    uint hashCapacity = fogLightGrid.words[2];
    if(hashCapacity == 0u)
    {
        firstLightIndex = 0u;
        lightCount = 0u;
        return false;
    }

    uint hashMask = hashCapacity - 1u;
    uint slotIndex = localLightGridHash32(cellCoord) & hashMask;

    for(uint probe = 0u; probe < hashCapacity; ++probe)
    {
        uint slotBase = 4u + slotIndex * 8u;
        if(fogLightGrid.words[slotBase + 3u] == 0u)
        {
            firstLightIndex = 0u;
            lightCount = 0u;
            return false;
        }
        if(int(fogLightGrid.words[slotBase]) == cellCoord.x &&
           int(fogLightGrid.words[slotBase + 1u]) == cellCoord.y &&
           int(fogLightGrid.words[slotBase + 2u]) == cellCoord.z)
        {
            firstLightIndex = fogLightGrid.words[slotBase + 4u];
            lightCount = fogLightGrid.words[slotBase + 5u];
            return true;
        }
        slotIndex = (slotIndex + 1u) & hashMask;
    }

    firstLightIndex = 0u;
    lightCount = 0u;
    return false;
}

// rgen:2488 起的三段范围/衰减（逐行照抄）。
float vptLocalLightRange(float emissionLevel)
{
    float userLocalMax = cam.rainWallParams1.w;
    float localMaxDistance = userLocalMax > 0.5
        ? min(VPT_LOCAL_MAX_DISTANCE, userLocalMax)
        : VPT_LOCAL_MAX_DISTANCE;
    return min(localMaxDistance, max(emissionLevel * VPT_LOCAL_RANGE_PER_EMISSION, 2.0));
}

float vptLocalLightRangeFade(float distanceToLight, float emissionLevel)
{
    float range = vptLocalLightRange(emissionLevel);
    return 1.0 - smoothstep(
        range * VPT_LOCAL_FADE_START_FRACTION,
        range,
        distanceToLight);
}

float vptLocalLightDistanceAttenuation(float distanceToLight)
{
    return 0.55 / (1.0 + 0.18 * distanceToLight +
        0.025 * distanceToLight * distanceToLight);
}

// rgen:8573 decodeLocalLightColor()（逐行照抄；发光色表是 Minecraft 光照等级
// 分类的结果，与 Java 的 classifyLocalLightType 成对）。
vec3 decodeLocalLightColor(uint typeCode)
{
    if(typeCode == 0u) return vec3(1.00, 0.90, 0.75);
    if(typeCode == 1u) return vec3(1.00, 0.62, 0.28);
    if(typeCode == 2u) return vec3(0.25, 0.72, 1.00);
    if(typeCode == 3u) return vec3(1.00, 0.18, 0.08);
    if(typeCode == 4u) return vec3(1.00, 0.34, 0.08);
    if(typeCode == 5u) return vec3(1.00, 0.82, 0.52);
    if(typeCode == 6u) return vec3(0.70, 0.28, 1.00);
    return vec3(1.00, 0.90, 0.75);
}
// rgen:1318 localLightIntensity()：用户滑杆「本地光源照射强度」，主机写的是
// intensity + 1.0，这里减 1 还原；车道没下发过（0）时退回 1.0。
float localLightIntensity()
{
    float user = uintBitsToFloat(cam.historyPadding1) - 1.0;
    return (user >= 0.0 && user <= 16.0) ? user : 1.0;
}

// 把 gatherPos 所在网格邻域（1 到 8 个 cell）里的灯收进固定数组。
// posEmission[i].w = emission 等级（0..15，未缩放）。
//
// 2026-10-26：本函数改为**转调** fogGatherLocalLightsIndexed()（多输出全局灯索引），
// 保持原签名不变 ⇒ 体积云 v2~v5 的调用点不需要改。索引版的存在理由见下方注释。
// 前置声明：索引版定义在本文件末尾（转调者在前，GLSL 需要先见到原型）。
int fogGatherLocalLightsIndexed(
    vec3 gatherPos,
    out vec4 posEmission[FOG_LIGHT_MAX],
    out uint typeCode[FOG_LIGHT_MAX],
    out uint globalIndex[FOG_LIGHT_MAX]);

int fogGatherLocalLights(
    vec3 gatherPos,
    out vec4 posEmission[FOG_LIGHT_MAX],
    out uint typeCode[FOG_LIGHT_MAX])
{
    uint unusedGlobalIndex[FOG_LIGHT_MAX];

    return fogGatherLocalLightsIndexed(gatherPos, posEmission, typeCode, unusedGlobalIndex);
}

// 与 fogGatherLocalLights 同序同口径，额外输出每盏灯在**灯表里的全局下标**。
//
// 为什么需要它：ReSTIR 的 reservoir 要跨帧（temporal）和跨 froxel（spatial）传递
// 选中样本的身份。若只存"本次收集数组里的第几个"，那个下标只对产生它的 froxel
// 有意义 —— 换一个 froxel 或换一帧就指向另一盏灯，合并结果无意义，integrate
// 阶段也无法据此查回灯的辐亮度。全局下标是唯一稳定的身份。
int fogGatherLocalLightsIndexed(
    vec3 gatherPos,
    out vec4 posEmission[FOG_LIGHT_MAX],
    out uint typeCode[FOG_LIGHT_MAX],
    out uint globalIndex[FOG_LIGHT_MAX])
{
    uint lightCount = fogLightBuffer.header.x;
    if(lightCount == 0u || fogLightGrid.words[2] == 0u)
    {
        return 0;
    }

    ivec3 centerCell = localLightGridCellCoord(gatherPos);
    uint packedIndexBase = localLightGridPackedIndexBase();

    int minCellX = localLightGridNeighborMinOffset(gatherPos.x);
    int maxCellX = localLightGridNeighborMaxOffset(gatherPos.x);
    int minCellY = localLightGridNeighborMinOffset(gatherPos.y);
    int maxCellY = localLightGridNeighborMaxOffset(gatherPos.y);
    int minCellZ = localLightGridNeighborMinOffset(gatherPos.z);
    int maxCellZ = localLightGridNeighborMaxOffset(gatherPos.z);

    int gathered = 0;
    for(int cellZ = minCellZ; cellZ <= maxCellZ; ++cellZ)
    {
        for(int cellY = minCellY; cellY <= maxCellY; ++cellY)
        {
            for(int cellX = minCellX; cellX <= maxCellX; ++cellX)
            {
                uint firstLightIndex = 0u;
                uint cellLightCount = 0u;
                if(!localLightGridLookupCell(
                    centerCell + ivec3(cellX, cellY, cellZ),
                    firstLightIndex,
                    cellLightCount))
                {
                    continue;
                }

                for(uint candidateOffset = 0u;
                    candidateOffset < cellLightCount;
                    ++candidateOffset)
                {
                    uint lightIndex = fogLightGrid.words[
                        packedIndexBase + firstLightIndex + candidateOffset];
                    if(lightIndex >= lightCount)
                    {
                        continue;
                    }
                    LocalLightGPU candidate = fogLightBuffer.lights[lightIndex];
                    posEmission[gathered] = candidate.worldPosEmission;
                    typeCode[gathered] = candidate.meta.x;
                    globalIndex[gathered] = lightIndex;
                    ++gathered;
                    if(gathered >= FOG_LIGHT_MAX)
                    {
                        return gathered;
                    }
                }
            }
        }
    }

    return gathered;
}

#endif
