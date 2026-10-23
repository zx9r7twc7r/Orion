// 太阳阴影图 —— 生产者 rt_sun_shadow.comp 与两个消费者
// （volumetric_light.comp 的光柱 march、volumetric_clouds.comp 的雾 march）
// 共用这一份正交基与投影，所以"写进阴影图的位置"和"查询时的位置"
// 结构上不可能对不上。
//
// **格式契约**：存的是"沿太阳方向的首个遮挡体距离"（深度），不是 0/1 可见性。
// 改成二值会让"一根光柱既穿窗台墙又穿窗户"整列判黑。改格式必须同时改生产者
// 与两个消费者，否则位置对得上、语义对不上。
//
// 调用方需要声明
// --------------
// 本 include 里的两个"端"函数各依赖一个外部符号，而 GLSL 会把整个翻译单元
// 都编译一遍 —— 不声明就用不上，会直接报 `undeclared identifier`。所以用模式
// 宏把它们分开，调用方在 `#include` **之前**选一个：
//
//   生产者（rt_sun_shadow.comp）：`#define SUN_SHADOW_PRODUCER 1`
//     layout(set = 0, binding = 1) uniform accelerationStructureEXT sceneTlas;
//     layout(set = 0, binding = 2, r32f) uniform image2D sunShadowImage;
//
//   消费者（体积光 / 体积雾）：`#define SUN_SHADOW_CONSUMER 1`
//     layout(set = 0, binding = N) uniform sampler2D sunShadowMap;
//
// 两侧都要有 CameraProperties 块里的 `historyParams`、`viewInverse` 与
// `frameCount`（抖动要用）。
//
// 为什么用阴影图而不是逐样本射线、为什么存深度而不是二值，
// 见 docs/decisions/sun-shadow.md
#ifndef SUN_SHADOW_GLSL
#define SUN_SHADOW_GLSL

// Z 出盒兜底档开关（默认 1；置 0 + 半深宏回 128 = 旧档逐字节复现）。
// 见消费者 sunShadowVisibility 内的说明。
#ifndef SUN_SHADOW_Z_FADE
#define SUN_SHADOW_Z_FADE 1
#endif

// 阴影图是定尺的，不随窗口/分辨率变化，所以不需要重建逻辑。
// 2026-10-04（用户选方案 A）：256 → 1024。近图 texel 从 0.75 格降到 0.1875 格，
// 窗框这类 1-2 格的开口在体积光柱里能出细节（此前 256² 下只有 ~1.3 纹素，抖动
// 也救不回来）。成本：重建一次 6.5 万条 → 105 万条 rayQuery（远图已是同量级，
// far 项实测 0~2.7ms 可承受）；站立不动零重建、走路按 0.75 格门控量化（见下），
// 重建节奏与 256 时代完全一致。
const int SUN_SHADOW_RESOLUTION = 1024;
// 中心量化格**保持 0.75 格**，与分辨率解耦：它决定"走路多久重建一次"，
// 若跟着分辨率变细，行走时重建频率会 ×16（每 0.19 格一重建）。
const float SUN_SHADOW_TEXEL = 1.0 / 1024.0;
const float SUN_SHADOW_CENTER_QUANTUM = 1.0 / 256.0;

// 正交盒半宽（格）。盒子的 XY 只需要罩住 **march 的采样点**：每条纹素的射线是
// 沿太阳方向的竖柱，遮挡体是靠"同一柱子里第一个命中的深度"抓到的，所以山、楼顶
// 只要落在那根 256 格长的射线里就够了，不需要它的水平投影也进盒。
//
// march 最远到 GODRAY_MAX_DISTANCE = 288，而原来的 48 只罩到离相机 48 格以内的
// 采样点，更远的采样点一律按"可见"处理 —— 站在远处遮挡体的影子里、正对太阳时，
// 光柱就会在墙上显出一块太阳形状的亮斑（"穿墙看见太阳轮廓"）。96 把可判定的行程
// 翻倍，代价是纹素从 96/256 = 0.375 格涨到 0.75 格，窗框这类 1-2 格的开口会糊一点。
//
// 改这个常量不影响性能：生产者仍是 6.5 万条 256 格长的射线，消费者仍是每个 march
// 样本 1 次取样；变的只有那 6.5 万条射线在水平面上的铺开范围（单格面积 ×4）。
//
// 2026-10-04（用户截图：天空同心弧 + 地面惨白）：96 < GODRAY_MAX_DISTANCE(128)，
// 于是 march 的**最后 32 格**落在盒外 —— 那里正是"近图 → 远图"的混合带，而两张图
// 的纹素尺度差 4×（0.19 vs 0.75 格）、偏置不同（0.25 vs 0.45），混出来的等值线就是
// 以太阳为圆心的那几圈弧。同时盒外可见度从旧档 0.08 被抬到远图的 ≈1.0，恒定薄霾
// 在"全可见"下按满程累积（σ×128×光源项）⇒ 地面与天空整片发白。
// 把半宽扩到 160（>128 + 余量）后，体积 march 全程都在盒内：弧线与"兜底可见度"
// 一起消失，加法项被真实地形/云影调制（这才是光柱该有的对比度来源）。
// 代价仅纹素变粗：2×160/1024 = 0.3125 格/纹素（旧 0.1875），仍远细于远图 0.75。
// ⚠ 必须与 C++ 的 SUN_SHADOW_NEAR_TEXEL_WORLD 同步（它 = 2×本值/256，用于重建量化）。
const float SUN_SHADOW_EXTENT_XY = 160.0;

// 盒外没有数据，旧行为是无条件返回 1.0（可见）—— 和 extent 是同一个病：任何落在
// 盒外的采样点都按"被太阳照到"算。现在按"出盒多远"把可见性从 1.0 平滑压到
// SUN_SHADOW_OUTSIDE_VISIBILITY。
// 2026-10-03 0.25 → 0.08：体积光介质改为恒定薄霾后（volumetric_light.comp
// VL_MEDIUM_SIGMA），盒外的 25% 兜底变成肉眼可见的"光穿墙"—— 盒外 ≥96 格的
// 墙体/山体挡不住薄霾，其背光侧照常按 1/4 强度发光。0.08 让兜底保持一点
// 远景光柱的余晖、又低于可感知的穿墙亮度；32 格过渡带不变（不会有硬边环）。
const float SUN_SHADOW_OUTSIDE_VISIBILITY = 0.08;
const float SUN_SHADOW_OUTSIDE_FADE_BLOCKS = 32.0;

// 沿太阳方向的半深（格）。生产者的射线最长就是这个值的两倍。
//
// 2026-09-26：128 → 160（宏 SUN_SHADOW_Z_EXTENT_BLOCKS 可覆盖回 128）。
// 体积光 march 最远 288 格，采样点沿太阳轴的投影可以到 ±288；半深越短，
// 落到盒外兜底档（见下方 Z 出盒说明）的采样越多。160 把正午常见情形
// （投影主要是竖直分量）基本全罩住，清晨/傍晚仍靠兜底档。代价：生产者
// 射线 256 → 320 格 —— 仍是每帧固定 6.5 万条，O(1) 开销不变。
#ifndef SUN_SHADOW_Z_EXTENT_BLOCKS
#define SUN_SHADOW_Z_EXTENT_BLOCKS 160.0
#endif
const float SUN_SHADOW_EXTENT_Z = SUN_SHADOW_Z_EXTENT_BLOCKS;

// 深度比较偏置（格）。和所有 shadow map 一样，防自遮挡的必需品：
// 没有它，遮挡体表面自己会被判成遮挡。
// 2026-10-03 拆成两档：远场档（照亮的是地表本身，抖动 ±0.5 纹素的深度误差
// 在低太阳时可达 1 格以上）保持 0.45 防痤疮；近场档（体积 march，样本是
// 空气点，自遮挡风险低）用 0.25 —— 偏置的另一半代价是"贴墙漏光"（遮挡体
// 沿太阳轴向背光侧一段阴影区被判可见，低太阳时水平拉长数格），体积介质
// 恒定化后这段漏光肉眼可见，减半。
const float SUN_SHADOW_BIAS = 0.45;
// 近场档（只被体积光/雾的近图包装器使用；远场/rgen 走 SUN_SHADOW_BIAS，不受影响）。
//
// 2026-10-05 0.25 → 0.06：0.25 的代价就是上面写的"贴墙漏光"——遮挡体沿太阳轴向
// 背光侧仍有一段被判可见，低太阳时水平拉长数格；介质恒定为薄霾后这段漏光在强度
// 3.0 档下肉眼可见，表现为"光柱穿墙"。近场样本是空气点、自遮挡风险本来就低（这
// 也是当初敢从 0.45 减半到 0.25 的理由），继续收紧到 0.06 只留防痤疮余量。
const float SUN_SHADOW_BIAS_NEAR = 0.06;

// 射线的起点抬离量，避免起点正好贴在某个三角形上。
const float SUN_SHADOW_ORIGIN_BIAS = 0.02;

// 位 0x04 = "遮挡体积光"。射线是**用这一位去 cull** 的，所以它选中的是
// 仍然带着这一位的实例。rebuildWorldGeometry 把这个位从 alpha 桶
// （玻璃 / 树叶 / 植物）上清掉、并显式加到本地玩家代理上。
//
// 反过来写是静默失效：`(instance.mask & cullMask) != 0` 才相交，把 cull mask
// 设成和某个桶一样的值，那个桶照样会被打到。
const uint SUN_SHADOW_CULL_MASK = 0x04u;

// 光的方向：与两个体积 pass 的 sunDirection() 同一个公式（同一个
// cam.historyParams.w），并且同样在太阳落到地平线以下后翻到月亮方向。
//
// 这里用函数内常量而不是共享的 PI：各个着色器的 PI 声明位置不同，
// 引用全局 PI 会踩到"用到时还没声明"的坑。
vec3 sunShadowLightDirection()
{
    const float TWO_PI = 6.28318530718;
    float angle = fract(cam.historyParams.w) * TWO_PI;
    vec3 direction = normalize(vec3(cos(angle), 0.81915204429 * sin(angle),
        0.57357643635 * sin(angle)));
    return direction.y >= 0.0 ? direction : -direction;
}

// 正交盒中心：跟着相机，但量化到纹素尺寸以抑制移动时的"游泳"。
// 正交基随太阳缓慢转动，所以按世界轴量化不是严格的纹素对齐，
// 但足以压住绝大部分抖动。
//
// 参数化版本（2026-10-01）给**远场图**用：远场图的 extent / texel 由 CameraUBO
// 的 sunShadowFarParams 下发，不是编译期常量。近场那份（sunShadowCenter）保持
// 原样，行为逐字节不变。
vec3 sunShadowCenterFor(float extentXY, float texel)
{
    float texelWorld = 2.0 * extentXY * texel;
    vec3 cameraPosition = cam.viewInverse[3].xyz;
    return floor(cameraPosition / texelWorld) * texelWorld;
}

vec3 sunShadowCenter()
{
    // 中心量化用 SUN_SHADOW_CENTER_QUANTUM（0.75 格，与分辨率解耦），
    // 不用 SUN_SHADOW_TEXEL —— 否则 1024² 下重建节奏 ×16。
    return sunShadowCenterFor(SUN_SHADOW_EXTENT_XY, SUN_SHADOW_CENTER_QUANTUM);
}

// 太阳方向 → 正交基。生产者和消费者都调这一个，所以不会各算一套。
void sunShadowBasis(vec3 sunDirection, out vec3 basisRight, out vec3 basisUp)
{
    vec3 reference = abs(sunDirection.y) > 0.99
        ? vec3(1.0, 0.0, 0.0)
        : vec3(0.0, 1.0, 0.0);
    basisRight = normalize(cross(reference, sunDirection));
    basisUp = cross(sunDirection, basisRight);
}

// 生产者用：某个纹素对应的射线起点（盒的"上风侧"边界上）。
//
// =====================================================
// 2026-10-10 修正：起点必须落在**朝太阳**那一端，不是背太阳端
// =====================================================
// 这里原来是 `- sunDirection * SUN_SHADOW_EXTENT_Z`。sunDirection 是
// sunShadowLightDirection()，一个 y ≥ 0 的**朝太阳**单位向量，于是 "-" 把起点
// 放到了背太阳端（地下），射线朝太阳方向（向上）打。整套数学随之变成一个
// 镜像：stored 变成"从地下一路向上遇到的**最低**遮挡体距离"，而
// `depth < stored` 的含义就成了"采样点比最低遮挡体还低"—— 即**地下可见、
// 地面与空中的一切判为遮挡**。这正是 `rt_test.rgen` 里"远场图对表面采样点恒判
// 全遮挡"的根因（那次把它归给"远场图"并只停用了 rgen 的消费点，但同一份数学
// 也在近场图里，服务着体积光/雾）。
//
// 标准 shadow map 的做法是**从光源侧朝场景**打射线：起点在太阳端、方向朝下，
// stored = 沿太阳方向遇到的**最高**遮挡体距离，`depth < stored` = 采样点比最高
// 遮挡体更靠近太阳 = 中间没有东西挡着 = 受光。所以起点取 "+"。
// 配合消费者把 depth 改成从太阳端量起（见 sunShadowVisibilityIn），本文件回到
// 标准形式，体积光"穿墙"（背光侧被判可见）随之消失。
vec3 sunShadowRayOrigin(vec2 uv, vec3 sunDirection, vec3 center)
{
    vec3 basisRight;
    vec3 basisUp;
    sunShadowBasis(sunDirection, basisRight, basisUp);
    vec2 offset = (uv * 2.0 - 1.0) * SUN_SHADOW_EXTENT_XY;
    return center +
        basisRight * offset.x +
        basisUp * offset.y +
        sunDirection * SUN_SHADOW_EXTENT_Z;
}

// 生产者用（远场图）：与上面同式，只把 extent 换成参数（远场图走 UBO 下发）。
// 同 2026-10-10 修正：起点在朝太阳一端（原来也是 "-"，见 sunShadowRayOrigin）。
vec3 sunShadowRayOriginFar(vec2 uv, vec3 sunDirection, vec3 center,
    float extentXY, float extentZ)
{
    vec3 basisRight;
    vec3 basisUp;
    sunShadowBasis(sunDirection, basisRight, basisUp);
    vec2 offset = (uv * 2.0 - 1.0) * extentXY;
    return center +
        basisRight * offset.x +
        basisUp * offset.y +
        sunDirection * extentZ;
}

// 生产者用：沿太阳方向打到第一个遮挡体的距离；打不到就返回 maxDistance。
#ifdef SUN_SHADOW_PRODUCER
float sunShadowTraceDepth(vec3 origin, vec3 sunDirection, float maxDistance)
{
    rayQueryEXT query;
    rayQueryInitializeEXT(
        query,
        sceneTlas,
        // 唯一的问题是"路上有没有东西"，所以第一个候选命中就停，
        // 也永远不会调用 closest-hit 着色器。这是硬件能做的最便宜的射线。
        gl_RayFlagsTerminateOnFirstHitEXT |
            gl_RayFlagsSkipClosestHitShaderEXT,
        SUN_SHADOW_CULL_MASK,
        origin,
        SUN_SHADOW_ORIGIN_BIAS,
        sunDirection,
        maxDistance);
    while(rayQueryProceedEXT(query)) {}
    if(rayQueryGetIntersectionTypeEXT(query, true) ==
        gl_RayQueryCommittedIntersectionNoneEXT)
    {
        return maxDistance;
    }
    return rayQueryGetIntersectionTEXT(query, true);
}
#endif // SUN_SHADOW_PRODUCER

// 逐帧、逐步去相关的抖动，单位是纹素（±0.5）。
//
// 一次取样 + 抖动 = "抖动的硬边"。march 的每一步各取一次、且各用不同的抖动，
// 于是单个像素上的结果其实是十几次独立抖动的平均 —— 一条十几级的软边，
// 正好对上参考图那种带噪点的柔边，而且不需要真的做 4 次 PCF。
//
// 抖动的时间种子（`SUN_SHADOW_FRAME_SEED`）：默认跟随帧号，供**自己有时域累积**
// 的消费者（体积雾/云）用。消费者若没有时域累积，可在 include 前把它定义成常量
// 表达式把帧号冻结 —— 否则同一步的取样偏移逐帧变，叶隙/剪影内就是逐帧二值翻转。
#ifndef SUN_SHADOW_FRAME_SEED
#define SUN_SHADOW_FRAME_SEED cam.frameCount
#endif
vec2 sunShadowJitter(int sampleIndex)
{
    uint hash = uint(SUN_SHADOW_FRAME_SEED) * 747796405u +
        uint(sampleIndex) * 2246822519u + 2891336453u;
    hash ^= hash >> 16;
    hash *= 0x7feb352du;
    hash ^= hash >> 15;
    hash *= 0x846ca68bu;
    hash ^= hash >> 16;
    return vec2(float(hash & 0xffffu), float((hash >> 16) & 0xffffu)) /
        65535.0 - 0.5;
}

// 消费者用：世界点是否被太阳照到。1.0 = 可见。
//
// `sunShadowMap` 由调用方声明（set 0 的某个 binding，最近邻取样）。
//
// =====================================================
// 2026-10-01：参数化（远场图共用这一份数学）
// =====================================================
// 现在有两张太阳阴影图：近场那张（256² / ±96 格，体积光与体积雾用）与远场那张
// （1024² / extent 由 UBO 下发，rgen 的远场分支用）。两张图的**投影、出盒兜底、
// 偏置、抖动**必须逐字节同源，否则"写进图的位置"和"查图的位置"会对不上 ——
// 这正是本文件头注释立下的契约。所以把 extent/texel/sampler/中心全部提成参数，
// 近场那份只做零行为变化的转发。
//
// sampler 作形参是 GLSL 允许的（不透明类型只能作 in 参数），调用方把全局
// uniform 传进来即可。
//
// ⚠ SUN_SHADOW_OUTSIDE_VISIBILITY / SUN_SHADOW_OUTSIDE_FADE_BLOCKS /
//    SUN_SHADOW_Z_FADE 两张图共用，保持为全局常量；偏置 2026-10-03 起分近/远两档。
#ifdef SUN_SHADOW_CONSUMER
float sunShadowVisibilityIn(
    sampler2D shadowMap,
    vec3 position,
    vec3 sunDirection,
    int sampleIndex,
    float extentXY,
    float extentZ,
    float texel,
    float centerQuantum,
    float outsideVisibility,
    float bias,
    out float outsideFade01)
{
    // 2026-10-04：texel（抖动步长）与 centerQuantum（中心量化格）拆成两个参数。
    // 近图分辨率升到 1024 后两者不再相等：抖动应随纹素变小（0.1875 格），
    // 中心量化必须与生产者的 sunShadowCenter()（0.75 格）逐位一致 ——
    // 否则消费侧重算的中心与生产侧错开一个量化差，整张近图对不齐。
    vec3 basisRight;
    vec3 basisUp;
    sunShadowBasis(sunDirection, basisRight, basisUp);
    vec3 local = position - sunShadowCenterFor(extentXY, centerQuantum);
    vec2 uv = vec2(dot(local, basisRight), dot(local, basisUp)) /
        (2.0 * extentXY) + 0.5;
    // 出盒距离，换算成格（uv 的 1.0 对应 2×半宽）。0 = 还在盒内。
    float outsideDistance = max(
        max(-uv.x, uv.x - 1.0),
        max(-uv.y, uv.y - 1.0)) * (2.0 * extentXY);
#if SUN_SHADOW_Z_FADE
    // Z 出盒（沿太阳轴 ±EXTENT_Z 之外；2026-09-26，开关 SUN_SHADOW_Z_FADE
    // 默认 1，置 0 连同半深宏回 128 可逐字节复现旧档）。XY 判定罩不到这个
    // 轴，而深度比较在盒外会系统性地撒谎 —— depth 的取值域只有 [0, 2×EXTENT_Z]，
    // 采样点越过任一端后 depth 就落到 stored 永远不可能取到的区间：
    //   • 朝太阳侧超过 EXTENT_Z：depth = extentZ - dot < 0 ⇒ depth < stored 恒真
    //     ⇒ 无条件"可见" —— 光柱穿过太阳与采样点之间的任何墙体/山体。
    //     正午时该端在很高的天上（症状不可见），清晨/傍晚太阳贴地平时它正好
    //     沿地平面铺开 = "某些时间段穿墙"的元凶。
    //   • 背太阳侧超过 EXTENT_Z：depth > 2×EXTENT_Z ⇒ 恒"遮挡"，朝太阳的
    //     远端光柱被整体切断。
    // （2026-10-10 起点/深度推翻到太阳端后，两端症状互换 —— 上面这条"朝太阳侧
    // 泄漏"就是现在要防的那一侧；本块判据用的是 ±dot 的对称式，两端都覆盖，
    // 所以代码不变，只更新说明。）
    // 处理方式与 XY 出盒一致：按出盒距离平滑压到 SUN_SHADOW_OUTSIDE_VISIBILITY。
    float depthSigned = dot(local, sunDirection);
    outsideDistance = max(outsideDistance,
        max(-depthSigned - extentZ,
            depthSigned - extentZ));
#endif
    if(outsideDistance > 0.0)
    {
        // 盒外没有数据：按出盒距离把"可见"平滑压到 outsideVisibility，而不是
        // 无条件 1.0。该值**不是**两张图共用的常量：
        //   • 近场图传 SUN_SHADOW_OUTSIDE_VISIBILITY（0.25）—— 它服务体积 march，
        //     盒外意味着"这条光柱的远端"，压暗是为了不与云影打架；
        //   • 远场图传 SUN_SHADOW_FAR_OUTSIDE_VISIBILITY（1.0）—— 它服务 rgen 的
        //     远场直射，盒外只意味着"这张图没覆盖到"，若沿用 0.25，则 384 格之外
        //     的**整片远场**会被无缘无故压到 25% 直射光（比原来更暗，是新的全局
        //     变暗而不是阴影）。宁可"没数据就不作判定 = 完全可见"。
        // 2026-10-04：outsideFade01（0=盒内，1=完全出盒）回传给调用方 —— 近图
        // 包装器用它把盒外平滑过渡到远图（修"六个面的圆圈"）。
        outsideFade01 = smoothstep(
            0.0, SUN_SHADOW_OUTSIDE_FADE_BLOCKS, outsideDistance);
        return mix(
            1.0,
            outsideVisibility,
            outsideFade01);
    }
    outsideFade01 = 0.0;
    // 2026-10-10 修正：depth 与生产者同端 —— 从**朝太阳**那一端量起。
    // 原来写 `dot(local, sunDirection) + extentZ`（从背太阳端量），配合旧的
    // 背太阳端起点，两边一起镜像 ⇒ 判据反向（地下可见、空中全遮挡）。
    // 起点改到太阳端后，这里必须同步改成 `extentZ - dot(...)`。
    float depth = extentZ - dot(local, sunDirection);
    vec2 sampleUv = uv + sunShadowJitter(sampleIndex) * texel;
#ifdef SUN_SHADOW_PCF_TAPS_4
    // 2026-10-05：2×2 PCF（只由体积光/雾消费者定义 SUN_SHADOW_PCF_TAPS_4 开启）。
    //
    // 原来是"单点 + 逐步/逐帧去相关抖动"，靠 march 的十几次采样与时间累积平均出
    // 软边（见上面 sunShadowJitter 的说明）。但叶片孔隙的尺度 ≈ 1 个阴影纹素时，
    // 单点结果在叶隙内就是二值翻转 ⇒ 屏幕上的叶隙逐帧闪。
    //
    // 这里把每步的核从"1 个随机纹素"换成"覆盖 1 个纹素的 2×2 平均"，先把每步估计
    // 的方差压掉约 4×，剩下的再交给时域重投影。抖动偏移保留 ⇒ 跨步/跨帧仍去相关，
    // 边缘依旧是软的，只是不再有单点造成的整像素级跳变。
    //
    // 偏置 2026-10-10 由 `- bias` 改为 `+ bias`：depth 现在朝远离太阳的方向增大，
    // 防痤疮要把采样点朝**光源**挪（即减小 depth）⇒ `depth - bias < stored`。
    // 原来的 `- bias` 是镜像坐标系下的防痤疮写法；在新坐标系里它会把遮挡体
    // 自身的表面判成遮挡（逐点自影子）。数值档位不变，只是符号跟着坐标系走。
    float lit = 0.0;
    for(int tapY = 0; tapY < 2; ++tapY)
    {
        for(int tapX = 0; tapX < 2; ++tapX)
        {
            vec2 tapUv = sampleUv +
                (vec2(float(tapX), float(tapY)) - 0.5) * texel;
            lit += depth < texture(shadowMap, tapUv).r + bias ? 1.0 : 0.0;
        }
    }
    return lit * 0.25;
#else
    float stored = texture(shadowMap, sampleUv).r;
    return depth < stored + bias ? 1.0 : 0.0;
#endif
}

// ---------------------------------------------------------------
// 近场档：零行为变化的转发（extent/texel 就是原来的编译期常量）。
//
// ⚠ rgen 不声明 `sunShadowMap` 这个全局（它只用远场图），会定义
//    SUN_SHADOW_NO_NEAR_MAP 把这一段整体跳过 —— 否则 `undeclared
//    identifier sunShadowMap` 直接编译失败。两个体积消费者不受影响
//    （它们不定义这个宏，拿到的还是同一个函数）。
// ---------------------------------------------------------------
#ifndef SUN_SHADOW_NO_NEAR_MAP
// 2026-10-04：远场档前向声明（近图盒外渐变要用；FAR_MAP 未定义时编译期剔除）。
#ifdef SUN_SHADOW_FAR_MAP
float sunShadowFarVisibility(vec3 position, vec3 sunDirection, int sampleIndex);
#endif
float sunShadowVisibility(vec3 position, vec3 sunDirection, int sampleIndex)
{
    float outsideFade = 0.0;
    float nearVis = sunShadowVisibilityIn(sunShadowMap, position, sunDirection, sampleIndex,
        SUN_SHADOW_EXTENT_XY, SUN_SHADOW_EXTENT_Z, SUN_SHADOW_TEXEL,
        SUN_SHADOW_CENTER_QUANTUM,
        SUN_SHADOW_OUTSIDE_VISIBILITY, SUN_SHADOW_BIAS_NEAR, outsideFade);
#ifdef SUN_SHADOW_FAR_MAP
    if(outsideFade > 0.0)
    {
        // 2026-10-04 修复"六个面的圆圈"：近图盒外不再压到 0.08 —— 连续雾密度
        // （团雾关闭后）下近图盒子的六个面会在天空显形。改在 32 格渐变带里
        // 平滑过渡到**远图**：extent 384 覆盖整个 march 行程（288），全程有
        // 真遮挡数据，远图盒外 = 1.0 不显盒。
        return mix(
            nearVis,
            sunShadowFarVisibility(position, sunDirection, sampleIndex),
            outsideFade);
    }
#endif
    return nearVis;
}
#endif

// 远场图盒外的可见性（见上面 outsideVisibility 处的论证）。1.0 = 完全可见，
// 因为盒外只代表"这张图没覆盖到"，不代表任何遮挡。
const float SUN_SHADOW_FAR_OUTSIDE_VISIBILITY = 1.0;

// ---------------------------------------------------------------
// 远场档（2026-10-01）：extent / texel 来自 CameraUBO 的 sunShadowFarParams。
// 调用方除了声明 `sunShadowMapFar`，还要 `#define SUN_SHADOW_FAR_MAP 1`。
// ---------------------------------------------------------------
#ifdef SUN_SHADOW_FAR_MAP
float sunShadowFarVisibility(vec3 position, vec3 sunDirection, int sampleIndex)
{
    float ignoredOutsideFade;
    return sunShadowVisibilityIn(sunShadowMapFar, position, sunDirection, sampleIndex,
        cam.sunShadowFarParams.x, cam.sunShadowFarParams.y, cam.sunShadowFarParams.z,
        cam.sunShadowFarParams.z,
        SUN_SHADOW_FAR_OUTSIDE_VISIBILITY, SUN_SHADOW_BIAS, ignoredOutsideFade);
}
#endif

// ---------------------------------------------------------------
// 体积雾专用入口（2026-10-21 起取 min(近,远)；2026-10-22 修出盒地板）
//
// 为什么需要它（"体积光穿墙"的根因）
// ---------------------------------
// 阴影图的可见性是"沿太阳方向的**一根竖柱**里有没有遮挡体"。柱子的 XY 位置由
// 采样点决定，而**柱子有多长由 extentZ 决定** —— 这才是决定"能看见多远的
// 遮挡体"的量，不是 XY 半宽。
//
// 近图的柱子只有 ±SUN_SHADOW_EXTENT_Z（160 格）。而体积雾在近图盒内**只查
// 近图**（outsideFade == 0 ⇒ 直接返回 nearVis）。于是：一座 200 格开外、在
// 太阳方向上的山/高楼，根本不在任何一根柱子范围内 ⇒ 近图认为"天上什么都没有"
// ⇒ 近处空气判为受光 ⇒ **站在它的背光侧，光柱照常出现**。
// 太阳越低，遮挡体投影越远（40 格高的楼在 5° 太阳下影长 457 格），所以低太阳
// 时症状最明显；正午影长短，症状轻但不会消失（远山/大建筑仍可 >160 格）。
//
// 远图的柱子长 2×sunShadowFarParams.y。⚠ 2026-10-22 更正：这里原来写"2026-10-21
// 起 = 1536 格"，但主机下发的是 sunShadowFarExtent() = SUN_SHADOW_FAR_EXTENT_XY
// ＝ 384（UBO 的 [0] 与 [1] 写同一个值，见 VulkanCore.cpp 的 sunShadowFarParams
// 段），实际柱长只有 768 格。要么把 .y 单独放大，要么按 768 设计 —— 不能按
// 注释里的 1536 想问题。
// XY 盒仍比近图大。两张图取 min = "任一张看见遮挡体就算被挡" ⇒ 有效覆盖范围
// 是两者柱子的并集。
//
// 2026-10-22 修正（"体积光穿墙"的直接洞）：旧实现是
//     min(sunShadowVisibility(), sunShadowFarVisibility())
// 而近图包装器在出盒时把结果 mix 到**远图的原值**，远图盒外又恒为
// SUN_SHADOW_FAR_OUTSIDE_VISIBILITY = 1.0 ⇒ 两张图都覆盖不到时 min 得 **1.0
// 完全受光**，比 union 之前那条 0.08 地板还亮。体积雾的 march 最远 672 格，
// 384 格以外整段都落在这个洞里 ⇒ 远景光柱对任何遮挡体都不再衰减。
// 现在：两张图各自只报"有没有数据"（盒外一律 1.0），再由 noDataFade（两者都
// 出盒才为 1）统一压到 SUN_SHADOW_OUTSIDE_VISIBILITY（0.08）。并集覆盖不变，
// 出盒地板恢复；近图盒外、远图盒内的那段仍然只用远图的真实值，不会被压暗。
//
// 只在远图真的被派发过时合并（sunShadowFarParams.w）。远图被
// ORION_SUNSHADOW_FAR=0 关掉时它里面是未定义内容，合并会把整层雾判成全黑；
// 此时 farFade 按 1（没有远图数据）处理，地板退回近图自己的出盒行为。
//
// 成本：每个雾 march 样本多一次纹理取样（1024² R32F = 4 MB，L2 常驻），
// 不增加任何射线 —— 远图本来就在每帧派发。
// ---------------------------------------------------------------
#if defined(SUN_SHADOW_FOG_UNION) && defined(SUN_SHADOW_FAR_MAP) && \
    !defined(SUN_SHADOW_NO_NEAR_MAP)
float sunShadowFogVisibility(vec3 position, vec3 sunDirection, int sampleIndex)
{
    // 近图：盒外先按"无数据 = 1.0"取值，真正的出盒地板留到最后统一施加。
    float nearFade = 0.0;
    float visibility = sunShadowVisibilityIn(
        sunShadowMap, position, sunDirection, sampleIndex,
        SUN_SHADOW_EXTENT_XY, SUN_SHADOW_EXTENT_Z, SUN_SHADOW_TEXEL,
        SUN_SHADOW_CENTER_QUANTUM,
        SUN_SHADOW_FAR_OUTSIDE_VISIBILITY,
        SUN_SHADOW_BIAS_NEAR, nearFade);

    // "完全没数据"的程度：两张图都出盒才为 1。远图未派发时按 1 处理，于是
    // noDataFade 只剩近图自己的出盒判据 —— 与 union 之前逐值等价。
    float noDataFade = nearFade;
    if(cam.sunShadowFarParams.w > 0.5)
    {
        float farFade = 0.0;
        visibility = min(visibility, sunShadowVisibilityIn(
            sunShadowMapFar, position, sunDirection, sampleIndex,
            cam.sunShadowFarParams.x, cam.sunShadowFarParams.y,
            cam.sunShadowFarParams.z, cam.sunShadowFarParams.z,
            SUN_SHADOW_FAR_OUTSIDE_VISIBILITY,
            SUN_SHADOW_BIAS, farFade));
        noDataFade = min(nearFade, farFade);
    }

    return mix(visibility, SUN_SHADOW_OUTSIDE_VISIBILITY, noDataFade);
}
#endif
#endif // SUN_SHADOW_CONSUMER
#endif
