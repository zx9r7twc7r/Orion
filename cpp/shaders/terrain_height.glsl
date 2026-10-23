// 地形高度场（2026-09-30）。生产者 rt_terrain_height.comp 与消费者共用这一份
// 网格约定，所以"写进图的位置"和"查询时的位置"结构上不可能对不上。
//
// 当前消费者：**无**。2026-10-19 B 方案后，体积雾改用参考的双高度带剖面，
// 空气体积光退役，两者都不再调用 terrainGroundHeight()。volumetric_clouds*.comp
// 仍保留 `TERRAIN_HEIGHT_CONSUMER` include 与 sampler 绑定，只为旧档兼容/后续复用；
// 下面这段动机描述的是已退役的旧档贴地板雾。
//
// 为什么要它（历史）
// ----------
// 旧档体积雾的"地面"是一个固定世界 Y 的薄板（fogHeightProfile(worldY)，
// 板面在 cloudParams7.z ± 厚度/2）。世界一起伏它就失效：相机在谷底、板面在
// 头顶 ⇒ 雾飘在天上；板子埋进山坡 ⇒ 坡面反而没雾。真高度场给每个 XZ 列一个
// 地面高度，雾的剖面改成"离**该列**地面多高"，团雾于是自动贴着山谷、坡面、
// 悬崖走。雨天不需要另一套判据：它只是把同一个权重乘得更狠（旧档见
// volumetric_fog.glsl 的 fogRainMistWeight，该函数已随旧档删除）。
//
// 为什么能这么便宜
// ----------------
// 与太阳阴影图同构：每帧一次、与窗口分辨率和 march 步数都无关。每纹素一条
// 向下的 ray query，共 26.2 万条。cull mask 用 0x04 这一位是有讲究的
// （见 rebuildWorldGeometry 的 mask 表）：ALPHA 桶（玻璃/树叶/植物）被清掉了
// 这一位、本地玩家代理（0x02）本来就没有 —— 所以向下打到的就是**实心地面**，
// 不会把树冠或自己的替身当成地面。
//
// 视角外怎么办
// ------------
// 网格只罩相机周围 ±TERRAIN_HEIGHT_EXTENT_XY。盒外没有地面数据，消费者按
// "出盒多远"把权重平滑压到 0（TERRAIN_HEIGHT_OUTSIDE_FADE_BLOCKS）。
//
// 这里刻意**不**退回旧的"世界 Y 薄板"：那会让远场的雾量取决于盒边界那一圈
// 的地形高度，而边界是跟着相机走的 —— 走两步边界列就换了一批，远场雾会跟着
// 抽动。压到 0 则是稳定的"远场没有地面雾"。出厂视距 224 < 256，本来就在盒内，
// 所以这条路径在默认设置下根本走不到。
//
// 调用方需要声明
// --------------
// 本 include 的两个"端"函数各依赖一个外部符号，而 GLSL 会把整个翻译单元都
// 编译一遍 —— 不声明就用不上，会直接报 `undeclared identifier`。所以用模式
// 宏把它们分开，调用方在 `#include` **之前**选一个：
//
//   生产者（rt_terrain_height.comp）：`#define TERRAIN_HEIGHT_PRODUCER 1`
//     layout(set = 0, binding = 1) uniform accelerationStructureEXT sceneTlas;
//     layout(set = 0, binding = 2, r32f) uniform image2D terrainHeightImage;
//
//   消费者（当前无实际调用；volumetric_clouds*.comp 仍按此模式 include 兼容）：
//     `#define TERRAIN_HEIGHT_CONSUMER 1`
//     layout(set = 0, binding = N) uniform sampler2D terrainHeightMap;
//
// 两侧都要有 CameraProperties 块里的 `viewInverse`。
#ifndef TERRAIN_HEIGHT_GLSL
#define TERRAIN_HEIGHT_GLSL

// 定尺，不随窗口/分辨率变化，所以不进任何重建路径。
//
// 2026-09-30（贴地薄雾）：256² → 512²。网格覆盖面不变（仍 ±256），所以纹素从
// 2 格缩到 1 格。原因是新增的贴地薄雾层只有一格厚，而它的底面就是这一列的地面
// 高度 —— 2 格的纹素会让薄雾在半个格子里埋进地形、在另半个格子里飘起来。
// 代价是射线数从 6.5 万涨到 26.2 万，仍是每帧固定、与窗口分辨率和 march 步数
// 无关的一笔开销。
const int TERRAIN_HEIGHT_RESOLUTION = 512;
const float TERRAIN_HEIGHT_TEXEL = 1.0 / 512.0;

// 正交盒半宽（格）。雾 march 最远到 viewDistance（出厂 224）。±256 罩住出厂
// 视距，纹素 1 格。
const float TERRAIN_HEIGHT_EXTENT_XY = 256.0;

// 出盒后的淡出带（格）。盒边界不是世界里的任何东西，硬切会在相机周围切出
// 一圈"雾消失"的环；32 格足够软。
const float TERRAIN_HEIGHT_OUTSIDE_FADE_BLOCKS = 32.0;

// 竖直探测范围：从"相机之上，或世界最高建筑高度之上"一直打到"相机之下，
// 或世界最低点之下"。
//
// 两头都取 max/min 而不是只看相机：相机可以远高于地形（鞘翅、旁观），那时
// 一条固定长度的相机相对射线会够不到地面，整片区域退化成"没有地面数据"。
// 世界建造范围是 y ∈ [-64, 320]，所以两端各自兜到那里再留 32 格余量。
const float TERRAIN_HEIGHT_WORLD_TOP = 320.0;
const float TERRAIN_HEIGHT_WORLD_BOTTOM = -64.0;
const float TERRAIN_HEIGHT_MARGIN = 32.0;

// "这一列没有地面"：虚空，或者射线什么都没打到。用 -1e30 而不是 0 ——
// 0 是一个合法高度，不能拿来当哨兵。
const float TERRAIN_HEIGHT_EMPTY = -1.0e30;

// 挡体积光的实例位。见 sun_shadow.glsl 的 SUN_SHADOW_CULL_MASK 与
// rebuildWorldGeometry 的 mask 表。
const uint TERRAIN_HEIGHT_CULL_MASK = 0x04u;

// 射线的起点抬离量，避免起点正好贴在某个三角形上。
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

// 消费端用：世界 XZ → 高度图 uv。
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
#endif // TERRAIN_HEIGHT_CONSUMER

// 生产端用：沿 direction 打到第一个命中的距离；打不到返回 maxDistance。
#ifdef TERRAIN_HEIGHT_PRODUCER
float terrainHeightTrace(vec3 origin, vec3 direction, float maxDistance)
{
    rayQueryEXT query;
    rayQueryInitializeEXT(
        query,
        sceneTlas,
        // 只问"路上有没有东西"，所以第一个候选命中就停，也永远不调 closest-hit
        // 着色器。与太阳阴影图同为硬件能做的最便宜的射线。
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
#endif // TERRAIN_HEIGHT_PRODUCER
#endif
