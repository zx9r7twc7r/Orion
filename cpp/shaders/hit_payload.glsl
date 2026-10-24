// =============================================================================
// Shared ray payload (location = 0) for every shader of the RT world pipeline.
//
// 2026-10-20: single source of truth (was six hand-synced copies).
//
// 2026-10-23: 延迟材质（RT_DEFERRED_MATERIAL）整段删除后只剩一种 payload ——
//   材质在命中着色器里求完，20 dwords / 80 B 全字段都要跨 traversal 保留。
//   曾与该路径并存的 48 B「identity only」版本（结构体 HitIdentity 与宏
//   HIT_IDENTITY_GLASS_BIT）随之删除。
//
// 注：当前没有任何字段是"只为延迟路径存在"的，但也没有做瘦身 —— 缩小 payload
//     是独立的收益项，不与本次删除混在一起做。
// =============================================================================
#ifndef HIT_PAYLOAD_GLSL
#define HIT_PAYLOAD_GLSL

struct HitPayload {
    float hitDistance;
    vec3 normal;
    vec3 albedo;
    uint state;
    float roughness;
    float f0Metal;
    vec3 emission;
    float parallaxRayTravel;
    float pomSelfShadow;
    float pomMicroOcclusion;
    float glassAlpha;
    float prevOffsetX;
    float prevOffsetY;
    float prevOffsetZ;
    // 2026-10-24（半透明接缝重置 + 穿层预算拆分，见
    // HANDOFF-2026-10-24-GLASS-AIR-NOISE.md §2 与 TRANSLUCENT-SEAM §4/§5.2）：
    // lastGlassFaceT 记录同一条射线上一个被接受半透明面的 gl_HitTEXT（-1 = 尚无），
    // 面距 > 1.5 格 ⇒ 中间有整格面被裁 ⇒ 面序在新区域从 0 重计；
    // glassFaceBudget 是穿层预算计数，**不随面序重置**（parallaxRayTravel 只剩
    // 面序一个用途，双用途就此拆开）。仅 rahit 玻璃分支读写，rgen 的 6 处
    // RT_PURPOSE_GLASS_VISIBILITY 播种点负责清零。
    float lastGlassFaceT;
    float glassFaceBudget;
};

#endif // HIT_PAYLOAD_GLSL
