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
    // 2026-10-25 阶段 C：介质状态机（替换面序奇偶）。
    // glassInside > 0.5 => 当前位于该半透明体内部；glassEntryT 是进入面的
    // gl_HitTEXT（-1 = 尚无），用于「上一块的出射面被裁」的区间判据。
    float glassInside;
    float glassEntryT;
    // 2026-10-25 阶段 D（Remix 同构的单条射线透射）：
    // 主射线透射在遍历中把"相机打到的第一片之后"的每一片玻璃的薄片透射乘在这里。
    // 只有 RT_PURPOSE_PRIMARY_TRANSMISSION 会写它，其余 purpose 保持 1.0。
    vec3 transmittanceAccum;
    // 2026-10-26（alpha blend 加性合成，对齐 RTX Remix alpha_blend_surface）：
    //     sum_{i>=1} tint_i * a_i * PI_{j<i}(1 - a_j)
    // 即"链内每一层自己的 alpha blend 贡献"。与 transmittanceAccum 一起构成
    // Remix 那条合成的确定性等价值（推导见 glass_chain_common.glsl 顶部）。
    // 只有 RT_PURPOSE_PRIMARY_TRANSMISSION 会写它，其余 purpose 保持 0。
    vec3 glassSurfaceWeightAccum;
    // 2026-10-26：alpha blend 的源 alpha，无条件 = 贴图 texel alpha x 顶点色 alpha
    // （Remix-MC ATLAS_TRANSLUCENT_BLEND = textureTimesVertexColor(),
    //   BlockTextureManager.java:64）。与 glassAlpha 分开：
    //   glassAlpha 仍表示"玻璃覆盖率"，只在顶点 materialId 命中玻璃族时取贴图 alpha；
    //   未打 marker 的玻璃面 glassAlpha 会是 1.0，但本字段恒为 texture x vertex。
    float glassBlendAlpha;
};

#endif // HIT_PAYLOAD_GLSL
