// =====================================================
// glass_chain_common.glsl —— 主射线透射链的**唯一契约**
//
// 背景（2026-10-25）：阶段 D 把"透过玻璃的接收面"从递归改成单条射线累乘，
// 但第一版有两处静态错误：
//   1) 复用通用 HitPayload 却没有播种 glassFaceBudget/glassInside/glassEntryT
//      ⇒ any-hit 读到垃圾预算，第一片玻璃被当成遮挡接受 ⇒ 玻璃不透；
//   2) 一个玻璃体的进/出两个面各乘一次 ⇒ 双重吸收。
// 本文件把"链的状态"收进一个入口函数，并统一"一个事件该乘多少"，
// 让 rgen（发起点）与两个 rahit（事件点）不可能再各自漂移。
//
// 与 dxvk-remix 的对应关系（对照 src/dxvk/shaders/rtx/）：
//   · 每像素状态只在开始处初始化一次 —— geometry_resolver.slangh:3223-3240
//     （geometryResolver 里 createEmpty 后只由 vertex 函数推进）；
//   · 一个事件该乘多少 —— translucent_surface_material_interaction.slangh:648-683
//     （thin-walled：方向不变 + attenuated geometric series），
//     数学在 utility/brdf.slangh:499-539；
//   · 上限 —— cb.psrRayMaxInteractions（geometry_resolver.slangh:3203）。
//
// ⚠ 与本仓库架构的已知差异（这里必须说明，不能假装一致）：
//   Remix 用 RAY_FLAG_FORCE_OPAQUE + raygen 循环，终止条件在 raygen 里判。
//   本引擎不能那样做：树叶/粒子/雨的 alpha cutout 只在 any-hit 里执行，
//   FORCE_OPAQUE 会让它们变成实心卡片。因此本引擎的终止（层数上限 / 透射率阈值）
//   只能留在 any-hit 里，但**判定逻辑集中在 glassChainShouldAbort() 一处**。
// =====================================================
#ifndef GLASS_CHAIN_COMMON_GLSL
#define GLASS_CHAIN_COMMON_GLSL

// =====================================================
// 2026-10-26：MC translucent 层的 **alpha blend** 口径（对齐 RTX Remix）。
//
// Remix 的映射链（都在 _ref/rtx-remix-minecraft）：
//   RenderLayerUtils.java:210  TRANSLUCENT_TRANSPARENCY → SurfaceMode.TRANSLUCENT
//   RemixEntitySubmitter.java:1541  → BlockTextureManager.ATLAS_TRANSLUCENT_BLEND
//   ⇒ 走 dxvk-remix 的 AlphaBlendSurface（并发 alpha 混合层），**不是**折射材质。
//
// 合成式（dxvk-remix/concept/surface/alpha_blend_surface.slangh:128-158 原文注释）：
//   C' = C1·a1 + C2·(1−a1)·a2 + … + CB·(1−a1)(1−a2)(1−a3)
// 其中 C_surface 的辐射由 stochasticAlphaBlendUseNeighborSearch 从**邻居不透明像素**
// 取得（rtx_composite.h:108-122）。我们这条链拿不到屏幕空间邻居辐射，于是用
// "背景辐射自己"当 C_surface 的代理（同一个近似），每一层的乘数退化为
//       透射率 = (1 − a) + a · tint
// 即 vanilla 的 out = src·a + dst·(1−a) 在"src ≈ dst"近似下的乘数形式。
//
// 旧口径（薄壁折射 + dye^0.5 + profile 标量 + 多格染料 DDA）在
// VPT_GLASS_ALPHA_BLEND=0 时完整保留。
// =====================================================
#ifndef VPT_GLASS_ALPHA_BLEND
// 2026-10-27 A/B：置 0 回到 alpha blend 之前的折射/透射模型
// （用户称之为 "22 号原来的折射模型"）。置 1 走 Remix 的 alpha blend 选层语义。
#ifndef VPT_GLASS_ALPHA_BLEND
#define VPT_GLASS_ALPHA_BLEND 1
#endif
#endif

// =====================================================
// 2026-10-26（第三轮）：从"乘性透射"改成 Remix 的**加性合成**。
//
// 上一轮把每层的效率写成 T = (1-a) + a*tint，这等价于假设
//     C_surface == C_background * tint
// 也就是把玻璃面自己的辐射当成"背景乘染料"。Remix 不是这样：
//
//   alpha_blend_surface.slangh:128-158   逐层 C' = C_i*a_i + C*(1-a_i)
//   composite_alpha_blend.comp.slang:50-84
//       diffuseLight = PrimaryDirectDiffuseRadianceHitDistance[neighbor] * surface.color
//       （邻居不透明像素的 **demodulated** 直接光 x 本层 albedo => 不含背景 albedo）
//   composite.comp.slang:812
//       return radianceOutput * backgroundAlpha + alphaBlendOutput
//       backgroundAlpha = PI(1 - a_i)
//
// 确定性展开（本引擎单射线链可以逐层精确求期望；Remix 随机选层是为了只存一层）：
//   C_out = C_bg * PI(1 - a_i) + SUM_i (E_bg * tint_i) * a_i * PI_{j<i}(1 - a_j)
// E_bg 是该像素背景接收面的**入射辐照度**（= 接收面辐射 / 其 albedo），
// 所有层共用同一个 E_bg —— 这正是 Remix 的"从邻居不透明像素取光"。
//
// 与"RTXDI 关闭 + RR"这条链的关系：Remix 的 E_bg 来自 direct lighting pass 的
// demodulated buffer（enableRtxdi 只控制 ReSTIR，关闭后就是逐光源 NEE 的和），
// DLSS-RR 消费的是**合成后**的颜色，而 depth / normal / MV 仍取自背景不透明面。
// 本引擎同构：E_bg 用既有的 evaluateDirectSunLighting + evaluateVPTRISDirectLighting，
// guide 仍由接收面填（ORION_GLASS_RECEIVER_MV）。
//
// A/B：VPT_GLASS_ALPHA_BLEND_SURFACE_RADIANCE=0 => 逐位回到上一轮的乘性口径。
//
// ⚠ 2026-10-28 实机事故：本宏曾默认 0，而 rgen 里负责写"这一格有没有半透明面"
//   的 glassAlphaBlendSurfaceReset 挂在**另一个**宏（DEFERRED_COMPOSITE）上，
//   于是 update() 被剔除、reset 仍每帧执行 ⇒ 74 号图被反复写成 hitT=0（invalid）
//   ⇒ glass_alpha_blend.comp / glass_alpha_finish.comp 对全屏直接 return
//   ⇒ 整条 alpha blend 链静默空转。症状是"改任何参数都没有任何画面变化"，
//   极易被误判成"部署没生效"。
//   现在本值取 1（走下面这套加性合成），并且 rgen 的 reset 守卫也加了本宏
//   ⇒ 两个口径只能一起开、一起关，不会再出现半死状态。
//   若要彻底回退乘性口径：把本值改 0（reset 会同步关掉，链整体惰性）。
// =====================================================
#ifndef VPT_GLASS_ALPHA_BLEND_SURFACE_RADIANCE
#define VPT_GLASS_ALPHA_BLEND_SURFACE_RADIANCE 1
#endif

// alpha blend 表面是否保留 pane 的镜面项（Fresnel 反射 + 太阳 GGX 高光）。
// 默认 0 = Remix 口径：AlphaBlendSurface 只有 albedo x opacity，
//   out = a*C_surface + (1-a)*C_bg 里 (1-a) 已经是全部透射能量。
// 置 1 = 回到"电介质薄片"叠加（掠射时 abs(N·V)->0 使 Fresnel->1 =>
//   侧面被镜面反射盖成不透明纯色，2026-10-26 用户截图的现象）。
#ifndef VPT_GLASS_ALPHA_BLEND_PANE_SPECULAR
#define VPT_GLASS_ALPHA_BLEND_PANE_SPECULAR 0
#endif

// C_surface 的光照来源（对齐 composite_alpha_blend.comp.slang:50-84 / 174-187）。
// 0（默认）= Remix 的邻居语义：不透明背景接收面的 demodulated 直接光
//             （behindRadiance / receiverAlbedo）；miss 不是合法邻居
//             （`!isMiss`），回落到环境辐照度（Remix 用 radiance volume）。
// 1 = 玻璃表面**自身** NEE（太阳 + 环境 + VLI）。
//     ⚠ 这是上一轮的自创，Remix 没有这条：实测点光源紧贴玻璃时 E 无上限
//     => 过曝截断成白色；1spp 太阳阴影还会在玻璃边缘产生萤火虫。
#ifndef VPT_GLASS_ALPHA_BLEND_PANE_ILLUMINATION
#define VPT_GLASS_ALPHA_BLEND_PANE_ILLUMINATION 0
#endif

// 一个半透明体的进/出两个面是否各算一层。
// Remix 的 AlphaBlendSurface::update() 对**每个**半透明面调用一次；
// 本引擎 any-hit 的历史口径是"一个体只乘一次"（glassChainAcceptEntry）。
// 默认 0 = 保持既有不透明度（只把 C_surface 从"背景 x 染料"换成真实着色）；
// 置 1 = 每个面各算一层，玻璃会明显更不透明（等同原版逐面 alpha blend）。
#ifndef VPT_GLASS_ALPHA_BLEND_PAIR_FACES
#define VPT_GLASS_ALPHA_BLEND_PAIR_FACES 0
#endif

vec3 glassAlphaBlendTransmission(vec3 tint, float alpha)
{
    const float clampedAlpha = clamp(alpha, 0.0, 1.0);
    return clamp(
        (vec3(1.0) - vec3(clampedAlpha)) +
            clamp(tint, vec3(0.0), vec3(1.0)) * clampedAlpha,
        vec3(0.0),
        vec3(1.0));
}

// 单层在加性口径下的两个量：透射 (1-a) 与表面权重 tint*a。
// 与 glassAlphaBlendTransmission 是同一层的两种写法：
//     (1-a) + a*tint  ==  (1-a) + tint*a
// 区别只在"tint*a 该乘谁"：乘背景（旧）还是乘 E_bg（Remix）。
void glassAlphaBlendLayerTerms(
    vec3 tint,
    float alpha,
    out vec3 transmission,
    out vec3 surfaceWeight)
{
    const float clampedAlpha = clamp(alpha, 0.0, 1.0);
    transmission = vec3(1.0 - clampedAlpha);
    surfaceWeight =
        clamp(tint, vec3(0.0), vec3(1.0)) * clampedAlpha;
}

// 2026-10-26：放宽以对齐 Remix 口径（16 面/0.01 -> 32 面/0.001）。
// 原值下"第二个接受面"就会 abort（any-hit 直接 return、不 ignore），表现为
// 近处玻璃透明、再往里一层突然变实心。Remix 的 psrRayMaxInteractions = 32，
// 且 thin-walled 用几何级数自然收敛、没有硬阈值。
// 面数预算（与 GI_GLASS_VISIBILITY_MAX_FACES 同口径：16 面 = 8 层）。
#ifndef ORION_GLASS_CHAIN_MAX_FACES
#define ORION_GLASS_CHAIN_MAX_FACES 32
#endif

// 累积透射低于此值即当遮挡收尾（与太阳 march 的 W2 阈值同口径）。
#ifndef ORION_GLASS_CHAIN_MIN_TRANSMITTANCE
#define ORION_GLASS_CHAIN_MIN_TRANSMITTANCE 0.0
#endif

// 一个半透明体被裁掉一面时，判为"新区域"的射线面距（格）。
// 同一个体进/出面 ≈ 1.0；相邻两格玻璃之间被裁掉的那一面 = 2.0。
#ifndef ORION_GLASS_CHAIN_REGION_BREAK
#define ORION_GLASS_CHAIN_REGION_BREAK 1.5
#endif

// 半透明材质号区间。
//
// 2026-10-26 修：这里原先写 2.0..2.5，但真正的生产者是
//   rt_test.rgen 的 getLegacyMaterialId()（:7951-7963）：
//     RT_HIT_WATER       → 1.0（进水）/ 2.0（水→空气）
//     RT_HIT_TRANSLUCENT → 3.0（正面）/ 4.0（玻璃底面）
// 3.0/4.0 因此从不命中 2.0..2.5 ⇒ 半透明面在"最近命中"路径上会被当成不透明面，
// 最终走 rgen 的 `else { behindRadiance = hitAlbedo; }`（无光照 albedo 直出）
// —— 就是"透过第一层看到的第二个面是纯色实心"。
//
// 现在覆盖 2.0..4.5：既包含水→空气的 2.0（旧口径），也包含玻璃的 3.0/4.0。
bool glassChainIsTranslucentMaterial(float materialId)
{
    return materialId >= 2.0 && materialId <= 4.5;
}

// =====================================================
// 链的起点：**所有**发起透射链的地方都必须走这个函数。
// 少调一次 = any-hit 读到上一根射线残留的预算/介质状态，正是 2026-10-25 的事故。
// =====================================================
void glassChainBegin(inout HitPayload payload)
{
    payload.transmittanceAccum = vec3(1.0);
    payload.glassInside = 0.0;
    payload.glassEntryT = -1.0;
    payload.glassFaceBudget = 0.0;
    // 加性口径的链内表面权重（见 hit_payload.glsl 与文件顶部）。
    // 每条透射链必须从这里起步，否则会读到上一条射线残留的权重。
    payload.glassSurfaceWeightAccum = vec3(0.0);
}

// 单事件透射率：与 main 侧（rt_test.rgen 的物理薄片段）和
// rahit（透过面累乘）使用**同一个**函数，避免两边口径漂移。
vec3 glassChainEventThroughput(
    vec3 blockTransmission,
    float thicknessBlocks,
    vec3 faceNormal,
    vec3 rayDirection,
    float ior)
{
    vec3 transmission;
    vec3 reflection;

    glassThinWallFactors(
        blockTransmission,
        thicknessBlocks,
        abs(dot(normalize(faceNormal), normalize(rayDirection))),
        ior,
        0.04,
        transmission,
        reflection);

    return clamp(transmission, vec3(0.0), vec3(1.0));
}

// 终止判据：唯一一处。返回 true = 把当前玻璃面当遮挡收尾。
bool glassChainShouldAbort(vec3 accumulatedTransmittance, float faceBudget)
{
    float peak = max(
        accumulatedTransmittance.r,
        max(accumulatedTransmittance.g, accumulatedTransmittance.b));

    return peak < ORION_GLASS_CHAIN_MIN_TRANSMITTANCE ||
           faceBudget >= float(ORION_GLASS_CHAIN_MAX_FACES);
}

// 介质状态推进：返回 true = 这是一个"进入面"，需要乘一次透射率。
// 出射面（同一体的第二个面）返回 false —— 一个体只乘一次。
bool glassChainAcceptEntry(inout HitPayload payload, float hitT)
{
    bool wasInside = payload.glassInside > 0.5;
    bool regionBreak =
        payload.glassEntryT >= 0.0 &&
        hitT - payload.glassEntryT > ORION_GLASS_CHAIN_REGION_BREAK;

    if(!wasInside || regionBreak)
    {
        payload.glassInside = 1.0;
        payload.glassEntryT = hitT;
        // 预算按"面"记：一次进入 = 进 + 出两面。
        payload.glassFaceBudget = payload.glassFaceBudget + 2.0;
        return true;
    }

    payload.glassInside = 0.0;
    return false;
}

// =====================================================
// 2026-10-26（第四轮，用户要求"严格搬 RTX Remix"）
// AlphaBlendSurface 的**逐字移植**
//
// 原文：_ref/dxvk-remix/src/dxvk/shaders/rtx/concept/surface/alpha_blend_surface.slangh
//
//   struct AlphaBlendSurface {                                   // :29-37
//     vec4 color;  vec3 normal;  float backgroundTransparency;
//     float randomThreshold;  uint16_t geometryHash;  float hitT;  bool hasEmissive;
//   };
//
//   [mutating] void update(vec4 newColor, vec3 newNormal, float newHitT,
//                          uint16_t newHash, bool newHasEmissive)  // :128-158
//   {
//     if (!isOpaque())                                           // color.a == 1  (:100-103)
//     {
//       if (randomThreshold >= 1.0 - backgroundTransparency)      // :144
//       { color = newColor; hitT = newHitT; normal = newNormal;
//         geometryHash = newHash; hasEmissive = newHasEmissive; }
//       else
//       { setOpaque(); }                                          // color.a = 1  (:105-108)
//     }
//     backgroundTransparency *= saturate(1.0f - float(newColor.a));  // :157
//   }
//
// 三个必须照搬的性质：
//   1) 每像素**只有一个槽位**：多层按概率竞争，最终只输出一层
//      （原文 :130-141 注释给了无偏性证明）。这就是"斜面不会叠成纯色"的原因 ——
//      旧实现把每层都累加（SUM tint_i*a_i*PI(1-a_j)），层数一多必然饱和成 tint。
//   2) 状态存在**每像素 GBuffer**里、不在 ray payload 里：
//      geometry_resolver.slangh:2196-2199 就是 load -> update -> store。
//      我们同一做法 ⇒ 不需要扩 ray payload。
//   3) randomThreshold 在**每条主射线开始处**赋一次（:3238）。
//
// 通道分配：三张 rgba16f = 12 个浮点，与原文 pack() 的 16 字节（:41-57）一一对应。
//   73 gbuffer : color.rgba                       <- data.x（原文 4xunorm8，这里 fp16 更准）
//   74 meta    : (backgroundTransparency, hitT, normalOct.x, normalOct.y)
//                                                 <- data.y（bgT+hitT 两个 f16）+ data.z（snorm2x16）
//   75 aux     : (randomThreshold, geometryHash, hasEmissive, 0)
//                                                 <- data.w（threshold f16 + hash 15bit + emissive 1bit）
// =====================================================

// 原文 resolve.slangh:471-524 的三个阈值，默认值取自
//   rtx_options.h:715（1.0f/255.0f）、rtx_options.h（254.0f/255.0f）、
//   rtx_composite.h:107（0.95f）。
#ifndef GLASS_ALPHA_BLEND_TRANSPARENCY_THRESHOLD
#define GLASS_ALPHA_BLEND_TRANSPARENCY_THRESHOLD (1.0 / 255.0)
#endif
#ifndef GLASS_ALPHA_BLEND_OPAQUENESS_THRESHOLD
#define GLASS_ALPHA_BLEND_OPAQUENESS_THRESHOLD (254.0 / 255.0)
#endif
#ifndef GLASS_ALPHA_BLEND_STOCHASTIC_MAX_OPACITY
#define GLASS_ALPHA_BLEND_STOCHASTIC_MAX_OPACITY 0.95
#endif
// 原文 geometry_resolver.slangh:2201 的终止阈值 `1.0 / 255.0`。
#ifndef GLASS_ALPHA_BLEND_MIN_BACKGROUND_TRANSPARENCY
#define GLASS_ALPHA_BLEND_MIN_BACKGROUND_TRANSPARENCY (1.0 / 255.0)
#endif

// 2026-10-27：原文 update() 里"未选中的层 ⇒ setOpaque()（a=1）"。
// 1 = 回到原文（靠时域抽签平均）；0 = 丢弃该层，不做整层锁定。
// 默认 0，理由见 update() 里的诊断记录。
#ifndef VPT_GLASS_ALPHA_BLEND_SET_OPAQUE
#define VPT_GLASS_ALPHA_BLEND_SET_OPAQUE 0
#endif

// 2026-10-27：整条链只记录最近的一层（见两个 rahit 的 acceptLayer 注释）。
// 1 = 只算最近一层（异色玻璃也透）；0 = 原文的 Π(1-a) 多层累乘。
#ifndef VPT_GLASS_ALPHA_BLEND_SINGLE_LAYER
#define VPT_GLASS_ALPHA_BLEND_SINGLE_LAYER 1
#endif

// 2026-10-27：玻璃源 alpha 的上限。
//
// 贴图边框 texel 的 alpha 是 1.0（中心才 0.4）。正面看边框只占边缘细线，
// 侧面掠射时整个面被压缩、边框铺满投影 ⇒ 侧面看起来完全不透明。
// 这正是"只有正对面透明、邻面一直不透"的直接原因，与层数/取光无关。
//
// 0.6 = 边框最多 60% 不透明（仍有 40% 透光）。设 1.0 恢复贴图原值。
#ifndef VPT_GLASS_ALPHA_BLEND_MAX_ALPHA
#define VPT_GLASS_ALPHA_BLEND_MAX_ALPHA 0.3
#endif

// 2026-10-27：玻璃族的 **固定不透明度**。
//
// 贴图 alpha 不适合当透明度：玻璃贴图的边框 texel 是 1.0，而侧面往往整块
// 落在边框上 ⇒ 整面实心；正面采到中心才看着透。这条差异正是"只有正对面透明"。
// 现在玻璃一律用本常量，贴图只贡献颜色。
#ifndef GLASS_FIXED_ALPHA
#define GLASS_FIXED_ALPHA 0.3
#endif

// 总闸：1 = 走本文这套 Remix 口径；0 = 回到旧的"逐层累加"路径（A/B 用）。
// ⚠ 必须定义在共享头里：rgen 与两个 rahit 都要看到同一个值。
#ifndef VPT_GLASS_ALPHA_BLEND_DEFERRED_COMPOSITE
#define VPT_GLASS_ALPHA_BLEND_DEFERRED_COMPOSITE 1
#endif
#if VPT_GLASS_ALPHA_BLEND_PANE_SPECULAR
// PANE_SPECULAR=1 是"叠加裸 Fresnel + 太阳 GGX"的 A/B 档，那两项在 rgen 的
// 函数后半段求和；本口径在写图后提前返回，会丢掉它们 ⇒ 强制回旧路径。
#undef VPT_GLASS_ALPHA_BLEND_DEFERRED_COMPOSITE
#define VPT_GLASS_ALPHA_BLEND_DEFERRED_COMPOSITE 0
#endif

// 两分量球面编码，作用与原文 sphereDirectionToSnorm2x16（:54）等价（octahedral）。
vec2 glassAlphaBlendEncodeNormal(vec3 n)
{
    const vec3 v = normalize(n);
    const float l1 = abs(v.x) + abs(v.y) + abs(v.z);
    vec2 p = v.xy / max(l1, 1.0e-5);
    if(v.z <= 0.0)
    {
        const vec2 s = vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
        p = (vec2(1.0) - abs(p.yx)) * s;
    }
    return p;
}

vec3 glassAlphaBlendDecodeNormal(vec2 encoded)
{
    vec3 v = vec3(encoded.x, encoded.y, 1.0 - abs(encoded.x) - abs(encoded.y));
    const float t = clamp(-v.z, 0.0, 1.0);
    v.xy += vec2(v.x >= 0.0 ? -t : t, v.y >= 0.0 ? -t : t);
    return normalize(v);
}

// 对应 AlphaBlendSurface::createEmpty()（:76-86）。
// 只由 raygen 在链起点调用；任何一像素同时只有一条主射线，无竞争。
void glassAlphaBlendSurfaceReset(ivec2 pixel, float randomThreshold)
{
    imageStore(glassAlphaBlendGbufferImage, pixel, vec4(0.0));
    imageStore(
        glassAlphaBlendMetaImage,
        pixel,
        vec4(1.0, 0.0, 0.0, 0.0));      // bgT = 1, hitT = 0 (= invalid)
    imageStore(
        glassAlphaBlendLightImage,
        pixel,
        vec4(clamp(randomThreshold, 0.0, 1.0), 0.0, 0.0, 0.0));
}

// 对应 AlphaBlendSurface::update()（:128-158），逐行。
// 返回累乘后的 backgroundTransparency（调用方拿它做 bgT 终止判据）。
// newColor 按原文 getAlphaBlendSurface():117-123 的**解调**口径传入
// （rgb = albedo / opacity）。
float glassAlphaBlendSurfaceUpdate(
    ivec2 pixel,
    vec4 newColor,
    vec3 newNormal,
    float newHitT,
    float geometryHash)
{
    vec4 gbuffer = imageLoad(glassAlphaBlendGbufferImage, pixel);
    vec4 meta = imageLoad(glassAlphaBlendMetaImage, pixel);
    vec4 aux = imageLoad(glassAlphaBlendLightImage, pixel);

    // isOpaque() == (color.a == 1.0)  :100-103
    if(gbuffer.a != 1.0)
    {
        // randomThreshold >= 1.0 - backgroundTransparency  :144
        if(aux.x >= 1.0 - meta.x)
        {
            gbuffer = newColor;
            meta.y = newHitT;                                   // hitT
            meta.zw = glassAlphaBlendEncodeNormal(newNormal);    // normal
            aux.y = geometryHash;                                // geometryHash
        }
        else
        {
            // setOpaque()  :105-108
            //
            // 2026-10-27：**默认不锁定**（新增开关 VPT_GLASS_ALPHA_BLEND_SET_OPAQUE）。
            //
            // 诊断实测（DBG 截图）：单层区域 a = 0.4 正常，双层区域被原文的
            // setOpaque 拉成 a = 1.0 ⇒ 那一帧该像素"整层完全不透明"，
            // 表现为"同一块玻璃有的地方透、有的地方像实心"。
            // 原文靠逐帧换蓝噪声 + 时域降噪把抽签结果平均掉；本引擎对玻璃
            // 没有可靠的时间累积，单帧看到的就是抽签本身。
            //
            // 锁定时还必须守住 premultiplied：rgb 存的是 albedo/α，
            // 直接把 a 置 1 会让消费端算成 albedo/α（α=0.4 时 2.5 倍过亮）。
#if VPT_GLASS_ALPHA_BLEND_SET_OPAQUE
            gbuffer.rgb *= gbuffer.a;
            gbuffer.a = 1.0;
#else
            // 丢弃这一层：颜色与 a 都不动（保留先被选中的那层）。
            // 背景权重仍由下面的 Π(1-a) 累乘 ⇒ 多层依然更实，但没有 a=1 的整层实心。
#endif
        }
    }

    // backgroundTransparency *= saturate(1 - newColor.a)  :157
    meta.x = clamp(meta.x * (1.0 - clamp(newColor.a, 0.0, 1.0)), 0.0, 1.0);

    // 2026-10-27：候选层计数（只用于 DBG 显示，不参与任何运算）。
    // "只有正对面透明、侧面不透明"必须能区分"层数太多"与"取光过亮"，
    // 靠猜会来回改错地方。
    aux.w = min(aux.w + 1.0, 255.0);

    imageStore(glassAlphaBlendGbufferImage, pixel, gbuffer);
    imageStore(glassAlphaBlendMetaImage, pixel, meta);
    imageStore(glassAlphaBlendLightImage, pixel, aux);

    return meta.x;
}

#endif // GLASS_CHAIN_COMMON_GLSL
