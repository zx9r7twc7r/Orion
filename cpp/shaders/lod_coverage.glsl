#ifndef LOD_COVERAGE_GLSL
#define LOD_COVERAGE_GLSL

#define LOD_ROLE_TERRAIN  0u
#define LOD_ROLE_VOXY_LOD 1u
#define LOD_ROLE_DH_FAR   2u

#define LOD_COVERAGE_SLOT_LIMIT (1u << 16)

layout(std430, binding = 19, set = 0) readonly buffer LodRoles {
    uint roles[];
} lodRoles;

layout(std430, binding = 20, set = 0) readonly buffer LodCoverage {
    ivec4 grid;
    uint words[];
} lodCoverage;

// 该实例的角色。越界（不该发生）当近场地形处理，最坏结果只是不去重。
uint lodInstanceRole()
{
    const uint slot =
        uint(gl_InstanceCustomIndexEXT) + uint(gl_GeometryIndexEXT);

    if(slot >= LOD_COVERAGE_SLOT_LIMIT)
    {
        return LOD_ROLE_TERRAIN;
    }

    return lodRoles.roles[slot];
}

// 该 chunk 柱的 8 bit 标志。网格外返回 0（= 未就绪 ⇒ 远场保持可见）。
uint lodColumnFlags(ivec2 column)
{
    if(lodCoverage.grid.z <= 0 || lodCoverage.grid.w <= 0)
    {
        return 0u;
    }

    const ivec2 relative = column - lodCoverage.grid.xy;

    if(relative.x < 0 || relative.x >= lodCoverage.grid.z ||
       relative.y < 0 || relative.y >= lodCoverage.grid.w)
    {
        return 0u;
    }

    const uint index =
        uint(relative.y) * uint(lodCoverage.grid.z) + uint(relative.x);

    return (lodCoverage.words[index / 4u] >> ((index % 4u) * 8u)) & 255u;
}

bool lodColumnNearReady(ivec2 column)
{
    return (lodColumnFlags(column) & 1u) != 0u;
}

// 远场命中是否只是近场几何的冗余表示 ⇒ 应当 ignoreIntersectionEXT。
// 只对 DH 远场成立；Voxy LOD 与近场地形一律返回 false（Voxy 行为不变）。
bool lodHitIsRedundant(uint role, vec3 hitWorldPosition)
{
    if(role != LOD_ROLE_DH_FAR)
    {
        return false;
    }

    const ivec2 column = ivec2(floor(hitWorldPosition.xz / 16.0));

    return lodColumnNearReady(column);
}

#endif
