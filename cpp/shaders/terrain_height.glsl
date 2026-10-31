#ifndef TERRAIN_HEIGHT_GLSL
#define TERRAIN_HEIGHT_GLSL

const int TERRAIN_HEIGHT_RESOLUTION = 512;
const float TERRAIN_HEIGHT_TEXEL = 1.0 / 512.0;

const float TERRAIN_HEIGHT_EXTENT_XY = 256.0;

const float TERRAIN_HEIGHT_OUTSIDE_FADE_BLOCKS = 32.0;

const float TERRAIN_HEIGHT_WORLD_TOP = 320.0;
const float TERRAIN_HEIGHT_WORLD_BOTTOM = -64.0;
const float TERRAIN_HEIGHT_MARGIN = 32.0;

const float TERRAIN_HEIGHT_EMPTY = -1.0e30;

const uint TERRAIN_HEIGHT_CULL_MASK = 0x04u;

const float TERRAIN_HEIGHT_ORIGIN_BIAS = 0.02;

// 网格中心（XZ）：跟着相机，但量化到纹素尺寸以抑制移动时的"游泳"
// （与太阳阴影图同招）。竖直方向不需要中心 —— 探测范围是世界绝对的。
vec2 terrainHeightCenterXZ()
{
    const float TEXEL_WORLD = 2.0 * TERRAIN_HEIGHT_EXTENT_XY * TERRAIN_HEIGHT_TEXEL;
    vec2 cameraXZ = cam.viewInverse[3].xz;
    return floor(cameraXZ / TEXEL_WORLD) * TEXEL_WORLD;
}

// 探测射线的起点高度与长度。所有纹素共用（只依赖相机 Y），所以调用方
// 可以在循环外算一次。
void terrainHeightProbeRange(out float originY, out float maxDistance)
{
    float cameraY = cam.viewInverse[3].y;
    originY = max(cameraY, TERRAIN_HEIGHT_WORLD_TOP) + TERRAIN_HEIGHT_MARGIN;
    maxDistance = originY -
        (min(cameraY, TERRAIN_HEIGHT_WORLD_BOTTOM) - TERRAIN_HEIGHT_MARGIN);
}

#ifdef TERRAIN_HEIGHT_CONSUMER
vec2 terrainHeightUv(vec2 worldXZ)
{
    return (worldXZ - terrainHeightCenterXZ()) /
        (2.0 * TERRAIN_HEIGHT_EXTENT_XY) + 0.5;
}

// 给定世界 XZ 的地面高度，并给出离盒边界的距离（盒内 = 0，单位格）。
// 该列无地面（虚空/打空）时返回 TERRAIN_HEIGHT_EMPTY。
float terrainGroundHeight(vec2 worldXZ, out float outsideDistance)
{
    vec2 uv = terrainHeightUv(worldXZ);
    vec2 outside = max(-uv, uv - 1.0) * (2.0 * TERRAIN_HEIGHT_EXTENT_XY);
    outsideDistance = max(max(outside.x, outside.y), 0.0);
    if(outsideDistance > 0.0)
    {
        return TERRAIN_HEIGHT_EMPTY;
    }
    return texture(terrainHeightMap, uv).r;
}
#endif
#ifdef TERRAIN_HEIGHT_PRODUCER
float terrainHeightTrace(vec3 origin, vec3 direction, float maxDistance)
{
    rayQueryEXT query;
    rayQueryInitializeEXT(
        query,
        sceneTlas,
        gl_RayFlagsTerminateOnFirstHitEXT |
            gl_RayFlagsSkipClosestHitShaderEXT,
        TERRAIN_HEIGHT_CULL_MASK,
        origin,
        TERRAIN_HEIGHT_ORIGIN_BIAS,
        direction,
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
#endif
