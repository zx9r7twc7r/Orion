// LOD / 远场几何的识别与去重（Vista 模型）。
//
// 取代两套隐式暗号：
//   1. 顶点 pad1 < -2.5 曾被用来判"这是 DH 远场"——现在 pad1 只再表示
//      "DH 材质编号"（pad1 = -(3 + EDhApiBlockMaterial)），是不是远场由
//      binding 19 的角色表回答；
//   2. TLAS instance mask 的位特判曾被用来做远场去重——现在由 binding 20
//      的每 chunk 柱覆盖位图回答。
//
// 主机端对应实现见 VulkanCore.cpp 的 sectionRoleOf / writeSectionRoleSlot /
// rebuildLodCoverage，以及 createSectionVertexAddressTable 里两张 buffer 的
// 创建与 updateSectionVertexAddressDescriptor 里的写入。
//
// ⚠ 跨语言耦合（改一处必须同时改另一处）：
//   - LOD_ROLE_* 三个常量 ↔ VulkanCore.cpp 的 SECTION_ROLE_*；
//   - binding 20 的 header 布局（ivec4）↔ VulkanCore.cpp 的 LodCoverageHeader；
//   - LOD_COVERAGE_SLOT_LIMIT ↔ VulkanCore.cpp 的 SECTION_VERTEX_ADDRESS_SLOT_LIMIT。
#ifndef LOD_COVERAGE_GLSL
#define LOD_COVERAGE_GLSL

#define LOD_ROLE_TERRAIN  0u
#define LOD_ROLE_VOXY_LOD 1u
#define LOD_ROLE_DH_FAR   2u

// 与主机 SECTION_VERTEX_ADDRESS_SLOT_LIMIT (1u << 16) 一致。
#define LOD_COVERAGE_SLOT_LIMIT (1u << 16)

// 每实例几何角色表，按 gl_InstanceCustomIndexEXT（== vertexAddressSlot）索引。
// 同一 slot 可被回收给别的 Section，所以每次重建都整表重写，不能只写一次。
layout(std430, binding = 19, set = 0) readonly buffer LodRoles {
    uint roles[];
} lodRoles;

// 每 chunk 柱近场覆盖位图：每柱 8 bit，仅 bit0（近场已就绪）在用。
//   grid.x = 网格左上角 chunkX，grid.y = 左上角 chunkZ，
//   grid.z = sizeX，grid.w = sizeZ。
// grid.z <= 0 表示主机没有发布覆盖数据（近场为空），此时一律不去重。
layout(std430, binding = 20, set = 0) readonly buffer LodCoverage {
    ivec4 grid;
    uint words[];
} lodCoverage;

// 该实例的角色。越界（不该发生）当近场地形处理，最坏结果只是不去重。
uint lodInstanceRole()
{
    // 2026-10-29：角色表也按"资源槽位"取（实例基址 + 几何序号）。
    // 一 geometry/实例时 gl_GeometryIndexEXT == 0 ⇒ 与改动前逐位相同。
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

    // 16 格一个 chunk 柱。DH 远场顶点已是世界坐标（实例变换为单位阵），
    // 所以命中点的世界 xz 直接给出它落在哪一柱。
    const ivec2 column = ivec2(floor(hitWorldPosition.xz / 16.0));

    return lodColumnNearReady(column);
}

#endif
