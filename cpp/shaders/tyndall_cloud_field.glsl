// Tyndall density adaptation shared by primary clouds, reflections and shadows.
// Requires cam, cloudShapeNoise and cloudCurlNoise to be declared by the caller.
#ifndef TYNDALL_CLOUD_FIELD_GLSL
#define TYNDALL_CLOUD_FIELD_GLSL
// 2026-10-02 Route A：行星半径对齐大气 LUT（sky_atmosphere.comp:94 EARTH_RADIUS = 6360 km，
// 那行注释自称 "matches the miss-shader cloud shells"，其实旧值 512000 格 = 512 km，
// 差了 12.4× ⇒ 云壳是个"迷你星球"，曲率强、远处明显下弯）。单位=格（1 格 = 1 m）。
// 置回 512000.0 可逐位回旧观感。数值稳：tyndallHeight / tyndallSphereRoots 用的都是
// 有理化形式（sqrt(R²+delta)+R、双根 c/q），没有 R 量级的相减；6.36e6 仍远在 fp32 安全区，
// 且这套形式的相对精度与 R 无关（缩放不变），所以和旧值一样稳。
#ifndef TYNDALL_PLANET_RADIUS
#define TYNDALL_PLANET_RADIUS 6360000.0
#endif
const float TYNDALL_RADIUS = TYNDALL_PLANET_RADIUS;
// ⚠ 2026-09-26 试过并**已回退**：42000 → 100000（云内 18000 → 40000）。
// 动机是用户报"云在远处被截断，要铺到地平线"；根因确实在这里 ——
// 相机在 y≈70、云底 3104 ⇒ 相机在云底球面下方 d≈3034，水平视线进入云层的距离是
// sqrt(2·R·d) = 55.7 km > 42000 ⇒ tyndallInterval 里 entry > exit ⇒ return false，
// 于是仰角低于 atan(sqrt(2d/R)) = 6.2° 的视线一个云样本都不采。
// 但把上限抬过 55.7 km 之后实机结果是**两件事同时发生**：
//   1) 视线积分路径长了 2~3 倍 ⇒ 地平线那一圈被云填满（"密密麻麻"）；
//   2) 步数不变（cloudQuality=1 ⇒ 20 步）⇒ 单步跨度涨到 5 km，云形特征只有 ~1.3 km
//      ⇒ 远景云糊成一坨。
// 结论：这条路要真做，必须同时抬步数（吃帧）或改用另一层"远景云片"近似，
// 单独抬上限是净负。保持 42000 不动。
// —— 2026-10-02 Route A 重开，上面两条教训都吸收：
//   * 步数：本次同时抬高 MAX_VIEW_STEPS（见 volumetric_clouds.comp），并吃到 2026-10-01
//     建的噪声体 mip 链 + footprint LOD，远样本读预过滤低模 ⇒ 不再是点采样糊。
//   * 上限：**不再动这条共享上限**。云影（rt_test.rgen）/ 体积光（volumetric_light.comp）
//     的短程 march 继续用 42000；另开 tyndallIntervalFar 只给"看云"的两个消费者
//     （volumetric_clouds.comp / rt_test.rmiss），避免连带改亮度和成本。
const float TYNDALL_MAX_DISTANCE = 42000.0;

vec3 tyndallRegionHash(vec3 cell)
{
    uvec3 h = uvec3(ivec3(cell));
    h = h * uvec3(1597334677u, 3812015801u, 2798796415u);
    uint seed = h.x ^ h.y ^ h.z;
    uvec3 v = seed ^ uvec3(2246822519u, 3266489917u, 668265263u);
    v ^= v >> 16u;
    v *= 2246822519u;
    v ^= v >> 13u;
    return vec3(v & 0x00ffffffu) / 16777216.0;
}

vec3 tyndallRegion(vec3 p)
{
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(tyndallRegionHash(cell), tyndallRegionHash(cell + vec3(1,0,0)), f.x),
            mix(tyndallRegionHash(cell + vec3(0,1,0)), tyndallRegionHash(cell + vec3(1,1,0)), f.x), f.y),
        mix(mix(tyndallRegionHash(cell + vec3(0,0,1)), tyndallRegionHash(cell + vec3(1,0,1)), f.x),
            mix(tyndallRegionHash(cell + vec3(0,1,1)), tyndallRegionHash(cell + vec3(1,1,1)), f.x), f.y), f.z);
}

float tyndallTime()
{
    return max(0.0, uintBitsToFloat(cam.padding));
}

vec3 tyndallWind()
{
    return vec3(0.81915204429, 0.0, 0.57357643635) *
        cam.cloudParams1.y * tyndallTime();
}

// Rain damps the vertical churn.  cloudParams1.z drives both the vertical
// scroll of the base shape and the detail layer's own offset, so at full
// strength an overcast deck boils instead of hanging.  Scaling it by the
// overworld rain level keeps the clear-sky look untouched and settles the deck
// exactly when the sky turns grey.  windSpeed (cloudParams1.y) is deliberately
// left alone: a storm should still translate, it just should not churn.
const float TYNDALL_RAIN_CHURN_SCALE = 0.12;

float tyndallEvolution()
{
    return cam.cloudParams1.z *
        mix(1.0, TYNDALL_RAIN_CHURN_SCALE, clamp(cam.weatherParams.x, 0.0, 1.0));
}

float tyndallHeight(vec3 p)
{
    vec2 horizontal = p.xz - cam.cloudParams3.xy;
    float vertical = p.y - cam.cloudParams0.x;
    // Rationalized length - radius avoids subtracting two ~512 km floats.
    float delta = dot(horizontal, horizontal) + vertical * (2.0 * TYNDALL_RADIUS + vertical);
    float altitude = delta / (sqrt(max(TYNDALL_RADIUS * TYNDALL_RADIUS + delta, 0.0)) + TYNDALL_RADIUS);
    return altitude / max(cam.cloudParams0.y, 1.0);
}

// 距离预过滤（2026-10-01）：把几何像素脚印换算成噪声体的 mip 级别。
// createCloudNoiseResources 现在给 128^3 形状体 / 64^3 卷曲体逐级 2x 盒式
// 降采样建了完整 mip 链，远处样本改读预过滤后的低分辨率层，把"点采样未过滤
// 体素"造成的方块边缘换成平滑软影（近处 footprint≈1 体素，LOD 0，不变）。
// footprint 单位=格；frequency 单位=texcoord/格；texResolution=体素边长。
// footprint<=0（反射 / 参考天空不传脚印）返回 0，保持旧档行为。
float tyndallNoiseLod(float footprint, float frequency, float texResolution)
{
    return clamp(log2(max(footprint * frequency * texResolution, 1.0)), 0.0, 16.0);
}

#ifndef CLOUD_WISPY_EDGE_WIDTH
// #4（2026-10-02 参考重构）絮状边缘宽度（参考 _HP_WispyEdgeWidth）：density 低于
// 该值视为"边缘"，只有边缘处才把侵蚀族从主 FBM 换成絮状胞元（Billowy→Wispy 的
// 过渡宽度）。必须定义在 tyndallDensity 之前（使用点在函数体内）。
#define CLOUD_WISPY_EDGE_WIDTH 0.35
#endif
#ifndef CLOUD_WISPY_TOP_HEIGHT
// #4 絮状顶部起始高度（参考 _HP_WispyTopHeight）：归一到 [0,1] 的局部高度，
// 高于它之后絮状权重升到 1（参考另有 _HP_WispyTopHardness 幂曲线，这里用
// smoothstep 的宽度表达同义效果）。
#define CLOUD_WISPY_TOP_HEIGHT 0.45
#endif

// ── 2026-10-07 降本：region 场的预计算 ───────────────────────────────────────
// tyndallRegion 每次要 8 个 tyndallRegionHash（每个 ~6 条 uvec3 ALU）+ 一层
// trilerp。而 region 只依赖 p.xz（y 分量是写死的常量 0.37 / 0.61），周期 =
// 1/0.00012 ≈ 8333 格 / size。自阴影光 march 的四步沿太阳方向只挪 24~96 格
// ⇒ 这四步的 region 相差 < 1.2% 个周期，肉眼与逐帧抖动都看不出。
// 所以把 region 提到循环外只算一次：每命中样本省 ~8 次 region（≈400 ALU）。
// 视图 march 仍走原来的 tyndallDensity（内部照旧即时算 region），逐位不变。
struct TyndallRegions
{
    vec3 region;
    // detileStrength <= 0.001 时不使用（写 0），避免白付一次 region。
    vec3 regionLarge;
};

TyndallRegions tyndallRegionsFor(vec3 p)
{
    vec3 advected = p + tyndallWind();
    float size = clamp(cam.cloudParams3.z, 0.50, 3.0);
    TyndallRegions r;
    // Non-periodic kilometre-scale regions break the repeated texture lattice.
    r.region = tyndallRegion(vec3(advected.x * 0.00012 / size, 0.37, advected.z * 0.00012 / size));
    r.regionLarge = vec3(0.0);
    if(clamp(cam.cloudParams6.w, 0.0, 2.0) > 0.001)
    {
        r.regionLarge = tyndallRegion(
            vec3(advected.x * 0.000041 / size, 0.61, advected.z * 0.000041 / size));
    }
    return r;
}

// tyndallDensity 的本体：region 由调用方给。直接调用者请用下面的包装。
float tyndallDensityFromRegions(vec3 p, bool cheap, float footprint, TyndallRegions regions)
{
    if(cam.cloudParams1.w < 0.5 || abs(cam.weatherParams.z - 1.0) >= 0.25)
        return 0.0;
    float h = tyndallHeight(p);
    if(h <= 0.0 || h >= 1.0) return 0.0;

    vec3 wind = tyndallWind();
    vec3 advected = p + wind;
    float size = clamp(cam.cloudParams3.z, 0.50, 3.0);
    float distribution = clamp(cam.cloudParams3.w, 0.0, 1.0);
    vec3 region = regions.region;

    // ── 批次4：抗重复域扭曲（cloudParams6.w，0=关，出厂 1.0）──
    // 高空俯视时形状噪声的纹理周期肉眼可见（用户截图）：基形约 19km/云团
    // 大小、BA 约 4.8km/云团大小、细节侵蚀固定约 1.3km（不随 size 缩放，
    // 最严重）。BA 通道早有 (region-0.5)*0.65 的 UV 扭曲，base 与 detail
    // 没有。修复：用非周期区域 hash 在两个尺度（~24km / ~8.3km 格）构造
    // ±5.7km/size 的位移场，同时加到 base 与 detail 的采样坐标上——位移
    // 相对细节周期 ±4.4 个周期，所有尺度的精确平铺同时打散。区域场用
    // advected 坐标 ⇒ 与云同速平流，扭曲锁定在云形上、无时间游移。
    // 纯 ALU（8 hash + trilerp），零新增纹理读。0 = 逐位旧档。
    vec2 detileWarp = vec2(0.0);
    float detileStrength = clamp(cam.cloudParams6.w, 0.0, 2.0);
    if(detileStrength > 0.001)
    {
        vec3 regionLarge = regions.regionLarge;
        detileWarp = detileStrength * (
            (region.xy - 0.5) * (3200.0 * size) +
            (regionLarge.xy - 0.5) * (8200.0 * size));
    }

    float baseNoise = textureLod(cloudShapeNoise,
        vec3((advected.xz + detileWarp) * (0.0000525 / size), 0.37),
        tyndallNoiseLod(footprint, 0.0000525 / size, 128.0)).r;
    float localBase = baseNoise * min(0.125, 260.0 / max(cam.cloudParams0.y, 1.0));
    h = (h - localBase) / max(1.0 - localBase, 0.01);
    if(h <= 0.0 || h >= 1.0) return 0.0;

    // ── 批次2-T2b：coverage 驱动的云顶 Möbius 拉伸（cloudParams6.z，0=关）──
    // f(h) = h/(1+(s-1)·h)，s = 1 + 强度×2×云量：底部精确锚定（h=0→0），
    // 云量越高云顶向上生长越多（满云量+满档 ≈ 3× 顶高）。用平滑云量
    //（不含 region 抖动）避免逐采样高度抖动。视图/云影/反射走同一密度场，
    // 天然一致。关=0 时 s=1 恒等，逐位旧档。
    if(cam.cloudParams6.z > 0.001)
    {
        float stretchCover = mix(cam.cloudParams0.z,
            min(cam.cloudParams0.z + 0.30, 0.98),
            clamp(cam.weatherParams.x, 0.0, 1.0));
        float s = 1.0 + cam.cloudParams6.z * 2.0 * stretchCover;
        h = h / (1.0 + (s - 1.0) * h);
    }

    // The baked FBM starts at four cells per tile: divide the WGSL frequency
    // by four. Cellular BA channels must not be mixed into the cloud mass.
    vec3 q = (advected + vec3(0.0, -tyndallTime() * tyndallEvolution() * 22.0, 0.0)) * (0.000105 / size);
    q += (region - 0.5) * 0.65;
    const mat3 rotation = mat3(0.0, 0.8, 0.6, -0.8, 0.36, -0.48, -0.6, -0.48, 0.64);
    vec2 a = textureLod(cloudShapeNoise, q, tyndallNoiseLod(footprint, 0.000105 / size, 128.0)).rg;
    vec2 b = textureLod(cloudShapeNoise, rotation * q * 1.37 + 0.173,
        tyndallNoiseLod(footprint, 0.000105 * 1.37 / size, 128.0)).rg;
    float shape = mix(dot(a, vec2(0.85, 0.15)), dot(b, vec2(0.85, 0.15)), 0.25);
    shape = mix(shape, smoothstep(0.22, 0.78, shape), 0.45);

    // =====================================================================
    // 2026-10-02 参考重构（第三阶段：云的大体形状）—— 云底软化
    //
    // 参考 HPVolumeCloud / HDRP 的 _HP_BottomSmoothHeight / _HP_BottomSmoothPow：
    // 局部高度趋 0 时 **baseShape → 1、detail → 0** ⇒ 云底在 XZ 上变得均匀
    //（像被切平的层积云），而不是"边缘散开、越靠底越稀"的絮状。
    //
    // 这是"大体形状"层面我们此前缺的一项：我们把高度廓形（profile）当作
    // 唯一的底部收束，没有让基形本身在底部去结构。h >= 高度阈值时
    // bottomBlend = 0 ⇒ 逐位旧档（0 加法不改变位型）。
    //
    // 消费者：本模块被 volumetric_clouds.comp / rt_test.rgen / rt_test.rmiss /
    // volumetric_light.comp 四处 include ⇒ 改这里必须四个 spv 一起重编（云影与
    // 反射自动与视图一致，因为它们走的就是同一密度场）。
    // A/B：置 CLOUD_BOTTOM_SMOOTH 0 回到旧档。
    // =====================================================================
#ifndef CLOUD_BOTTOM_SMOOTH_HEIGHT
// 淡出区间（归一化局部高度）。0.22 ≈ 底部 1/5 的云体去结构；
// 参考区间随云柱厚度自适应（与 phi_fwd 的底部软化同一套思路），此处取固定值，
// 因为密度场是视图/云影/反射共享的，逐样本自适应会让三者的云底不一致。
#define CLOUD_BOTTOM_SMOOTH_HEIGHT 0.22
#endif
#ifndef CLOUD_BOTTOM_SMOOTH_POW
// 淡出曲线指数，>1 = 底部平坦区更长、过渡更陡。
#define CLOUD_BOTTOM_SMOOTH_POW 1.0
#endif
    // 两个旋钮走**共享尾块** CameraUBO.cloudShapeParams（偏移 1104）：本模块被
    // 视图/云影/反射/体积光四处 include，所以它们必须同源 —— 这也是 2026-10-02
    // 把它们从编译期宏提升为 uniform 时必须给那三个 shader 补桥接 lane 的原因。
    // <0 = 回落编译期宏（A/B 通道）；0 = 该旋钮关闭（bottomBlend 恒 0）。
    float bottomSmoothHeight = cam.cloudShapeParams.x >= 0.0
        ? cam.cloudShapeParams.x : CLOUD_BOTTOM_SMOOTH_HEIGHT;
    float bottomSmoothPow = cam.cloudShapeParams.y > 0.0
        ? cam.cloudShapeParams.y : CLOUD_BOTTOM_SMOOTH_POW;
    float bottomBlend = 0.0;
    if(bottomSmoothHeight > 0.0)
    {
        bottomBlend = pow(clamp(1.0 - h / bottomSmoothHeight, 0.0, 1.0),
            max(bottomSmoothPow, 1.0e-3));
    }
    shape = mix(shape, 1.0, bottomBlend);
    // =====================================================================
    // 2026-10-02 参考重构（第四阶段：形状与细节）
    //
    // 参考（HDRP 血统的 EvaluateCloudProperties）在形状层用的是**两处重映射**，
    // 而我们此前两处都用的"减法"，这正是云体偏薄、边缘发灰的根因：
    //   ① coverage：参考 Remap(density, 1−coverage, 1, 0, 1) —— 把阈值以上的
    //      区间**归一化铺满**；我们 `density = shape*profile − (1−coverage)`，
    //      刚过阈值处几乎为 0 ⇒ 云体薄、内部空。⇒ CLOUD_COVERAGE_REMAP
    //   ② erosion：参考 Remap(density, erosion, 1, 0, 1) —— detail 抬起**下限**
    //      再重映射 ⇒ 顶面保持满、只在边缘切出缺口（花椰菜/絮状边缘）；
    //      我们 `density −= detail*strength` ⇒ 整体变薄、边缘发灰。⇒ CLOUD_EROSION_REMAP
    // 两处都可 A/B 回旧形式（0）。廓形（高度）保持解析式（参考是 256×1 LUT，
    // 解析等价），但给一个曲线指数旋钮。
    // =====================================================================
    // ── #3（2026-10-02 参考重构）：剖面从"单一解析两段"升级为**云型三段插值** ──
    // 参考 `_CloudLutTexture`：UV=(归一化高度, 径向距离)，RGB=(Cu, Tcu, Cb) 三型
    // 剖面，云型由天气图 G 通道三段插值。我们无天气图，云型代理用**非周期区域场**
    //（region.x，等价于参考的径向距离项）加一个全局偏移旋钮：
    //   type = clamp(cloudProfileParams.x + (region.x−0.5)·cloudProfileParams.y, 0, 1)
    // 三型**只相对缩放既有锚点**（cloudParams4.x = 低端锚点、0.75 = 高端锚点）
    // ⇒ type=0 时三个乘子全为 1.0 ⇒ 与旧档逐位一致（0 加法/乘 1 不改变位型）。
    //   Cu (0.0)：低端 1.00× / 高端 1.00× / 曲线 1.00×  ← 旧档
    //   Tcu(0.5)：低端 1.40× / 高端 0.80× / 曲线 0.85×  （更集中在下段、塔状）
    //   Cb (1.0)：低端 0.60× / 高端 1.23× / 曲线 0.70×  （几乎填满整层、深对流）
    float profileType = clamp(cam.cloudProfileParams.x +
        (region.x - 0.5) * clamp(cam.cloudProfileParams.y, 0.0, 1.0), 0.0, 1.0);
    bool profileLowerHalf = profileType < 0.5;
    float profileTypeT = profileLowerHalf ? profileType * 2.0 : (profileType - 0.5) * 2.0;
    float profileTypeLo = profileLowerHalf
        ? mix(1.00, 1.40, profileTypeT) : mix(1.40, 0.60, profileTypeT);
    float profileTypeHi = profileLowerHalf
        ? mix(1.00, 0.80, profileTypeT) : mix(0.80, 1.23, profileTypeT);
    float profileTypeCurve = profileLowerHalf
        ? mix(1.00, 0.85, profileTypeT) : mix(0.85, 0.70, profileTypeT);
    float profile = clamp(h / (clamp(cam.cloudParams4.x, 0.03, 0.30) * profileTypeLo),
        0.0, 1.0) * clamp((1.0 - h) / (0.75 * profileTypeHi), 0.0, 1.0);
#ifndef CLOUD_PROFILE_CURVE_ON
// 廓形曲线开关（GLSL 的 #if 只吃整数常量表达式，所以开关与指数分开两个宏）。
#define CLOUD_PROFILE_CURVE_ON 1
#endif
#ifndef CLOUD_PROFILE_CURVE
// 廓形曲线指数（参考的 _DensityProfile LUT 的解析等价）：>1 让密度更集中在
// 中段（云更"实"），<1 更均匀。1.0 时与旧档逐位一致（pow(x,1)=x）。
#define CLOUD_PROFILE_CURVE 1.0
#endif
#if CLOUD_PROFILE_CURVE_ON
    // 第四阶段：廓形曲线进 GUI（cloudShape2Params.z；<=0 回落编译期宏）。
    float profileCurve = cam.cloudShape2Params.z > 0.0
        ? cam.cloudShape2Params.z : CLOUD_PROFILE_CURVE;
    // #3：云型自带的曲线乘子在同一个 pow 里生效（type=0 时 = 1.0 ⇒ 逐位旧档）。
    profile = pow(clamp(profile, 0.0, 1.0),
        max(profileCurve * profileTypeCurve, 1.0e-3));
#endif
    float rain = clamp(cam.weatherParams.x, 0.0, 1.0);
    float coverage = mix(cam.cloudParams0.z, min(cam.cloudParams0.z + 0.30, 0.98), rain);
    coverage = clamp(coverage + (region.x - 0.5) * distribution * 0.60, 0.02, 0.95);
    float density = shape * profile - (1.0 - coverage);
    if(density <= 0.0) return 0.0;

#ifndef CLOUD_COVERAGE_REMAP
// ① coverage 归一化重映射（参考 Remap(shape*profile, 1−coverage, 1, 0, 1)）。
// 置 0 回我们此前的减法形式（云体更薄、内部更空），供 A/B。
#define CLOUD_COVERAGE_REMAP 1
#endif
#if CLOUD_COVERAGE_REMAP
    // 把阈值以上的区间**归一化铺满** ⇒ 云体蓬松、内部不再发空（HDRP 的体积感）。
    // 除以 coverage 还顺带把密度归一到 [0,1] ⇒ 后面的侵蚀重映射有稳定定义域，
    // 也让两级步进的占用阈值（cloudShapeParams.z）有了可预期的量纲。
    // 代价：整体密度上升（云更实/更不透明），用「云量」「云体密度」回调。
    density = density / max(coverage, 1.0e-3);
#endif

    if(!cheap)
    {
        // 细节保持距离（CLOUD_DETAIL_RANGE，默认 1；置 0 回 35..180 旧档）：
        // 旧带 35..180 ⇒ footprint>180 格后细节侵蚀完全关闭 = 远处云发糊。
        // 新带 60..320 让云须/边缘到更远仍保留；代价是远处样本多付 2 次
        // noise 读（半分辨率 march，可接受）。消费者：clouds/light/rgen/rmiss
        // 四处同步，改档后四个 spv 必须一起重编。
#ifndef CLOUD_DETAIL_RANGE
#define CLOUD_DETAIL_RANGE 1
#endif
#if CLOUD_DETAIL_RANGE
        float detailWeight = 1.0 - smoothstep(60.0, 320.0, footprint);
#else
        float detailWeight = 1.0 - smoothstep(35.0, 180.0, footprint);
#endif
        float detail = 0.5;
        if(detailWeight > 0.01)
        {
            // 第四阶段（参考的 _HP_DetailScale / 细节对比）：细节层自己的频率倍率
            // 与对比。频率倍率 >1 = 更细的絮状（云须更碎），<1 = 更粗的团块；
            // 对比以 0.5 为中心缩放（1.0 = 旧档逐位）。
            float detailScale = cam.cloudShape2Params.x > 0.0
                ? cam.cloudShape2Params.x : 1.0;
            float detailContrast = cam.cloudShape2Params.y > 0.0
                ? cam.cloudShape2Params.y : 1.0;
            float detailFreq = 0.000775 * detailScale;
            vec3 dq = (p + detileWarp.xyx * vec3(1.0, 0.0, 1.0) + wind * 2.5 +
                vec3(tyndallTime() * tyndallEvolution() * 35.0,
                tyndallTime() * tyndallEvolution() * 14.0, 0.0)) * detailFreq;
            vec3 curl = textureLod(cloudCurlNoise, dq * 0.08,
                tyndallNoiseLod(footprint, detailFreq * 0.08, 64.0)).xyz * 2.0 - 1.0;
            // ── #4（2026-10-02 参考重构）：同一次 textureLod 取回 .rba 三个通道 ──
            // .r = FBM（旧档的 detail）/ .g = 胞元 7（Billowy 血统，粗）/
            // .b = 胞元 15（Wispy 血统，细）。B/A 两通道建库时烘好，但**此前从未
            // 被任何着色器使用** ⇒ 两族侵蚀是零额外纹理读、零额外带宽、零额外
            // 描述符（同一 128³ 体、同一坐标、同一 mip）。强度默认 0 ⇒ 逐位旧档。
            vec3 detailSample = textureLod(cloudShapeNoise, dq + curl * 0.08,
                tyndallNoiseLod(footprint, detailFreq, 128.0)).rba;
            detail = mix(0.5, detailSample.r, detailWeight);
            detail = clamp(0.5 + (detail - 0.5) * detailContrast, 0.0, 1.0);
            float wispyStrength = clamp(cam.cloudDetailParams.x, 0.0, 2.0);
            if(wispyStrength > 0.001)
            {
                // 参考 _HP_WispyLowStr / _HP_WispyHighStr / _HP_WispyTopHeight /
                // _HP_WispyTopHardness / _HP_WispyReach / _HP_WispyEdgeWidth：
                // 低胞元与高胞元按高度混合，只在**上部**与**边缘**（density 低）
                // 把侵蚀族换成絮状，并把侵蚀下限再压低 Reach。
                float wispyEdgeWidth = cam.cloudDetailParams.y > 0.0
                    ? cam.cloudDetailParams.y : CLOUD_WISPY_EDGE_WIDTH;
                float wispyReach = clamp(cam.cloudDetailParams.z, 0.0, 1.0);
                float wispyTopHeight = cam.cloudDetailParams.w > 0.0
                    ? clamp(cam.cloudDetailParams.w, 0.05, 1.0) : CLOUD_WISPY_TOP_HEIGHT;
                float wispy = mix(detailSample.g, detailSample.b, clamp(h, 0.0, 1.0));
                float topWeight = smoothstep(wispyTopHeight * 0.5, wispyTopHeight, h);
                float edgeWeight = 1.0 - smoothstep(0.0,
                    max(wispyEdgeWidth, 1.0e-3), density);
                float wispyErosion = wispy * (1.0 + wispyReach);
                detail = mix(detail, wispyErosion, clamp(wispyStrength *
                    mix(edgeWeight, 1.0, topWeight), 0.0, 1.0));
            }
        }
#ifndef CLOUD_EROSION_REMAP
// ② 侵蚀改成重映射（参考 Remap(density, erosion, 1, 0, 1)）。置 0 回旧的减法形式。
#define CLOUD_EROSION_REMAP 1
#endif
#ifndef CLOUD_EROSION_SCALE
// 把「边缘侵蚀」滑块（0~0.5）映射成侵蚀下限。参考的 _ErosionScale 同义。
//
// ⚠ 2026-10-02 修正 2.0 → 1.0（用户反馈"云扁扁的"）：
// 2.0 时 detail(≈0.5~0.9) × 强度(出厂 0.5) × 2 ≈ 0.5~0.9，直接顶到钳位 ⇒
// 重映射 (d−e)/(1−e) 的分母趋 0 ⇒ 幸存的体素被**拉满**成硬边厚块，云于是看起来
// "扁而实、边缘发死"。1.0 让侵蚀下限落在 0.25~0.45 —— 这正是重映射设计的区间
//（e 相对 d 小），边缘重新变成可读的絮状、体积感回来。
// 想要更强的侵蚀效果，请调大「边缘侵蚀」滑块本身，而不是把这个系数再抬回去。
#define CLOUD_EROSION_SCALE 1.0
#endif
#if CLOUD_EROSION_REMAP
        // 参考：detail 抬起**下限**再重映射 ⇒ 顶面保持满密度、只在边缘切出缺口
        //（清晰的絮状/花椰菜边缘）。云底软化区间里 detail 一并归零
        //（"base→1、detail→0"），否则底部会被切出絮状边缘、软化白做。
        // 上限 0.95 → 0.8：分母 (1−e) 太小会把密度拉满（见 CLOUD_EROSION_SCALE 注释）。
        float erosion = clamp(detail * cam.cloudParams1.x * CLOUD_EROSION_SCALE *
            mix(0.75, 1.25, h) * (1.0 - bottomBlend), 0.0, 0.8);
        density = clamp((density - erosion) / max(1.0 - erosion, 1.0e-3),
            0.0, 1.0);
#else
        density -= detail * cam.cloudParams1.x * mix(0.75, 1.25, h) *
            (1.0 - clamp(density * 6.0, 0.0, 1.0)) * (1.0 - bottomBlend);
#endif
    }
    // 2026-10-17 参考外形（Clouds.fsh:104-120,152-155）——**整段按参考重算密度**：
    //   fade      = 1 - h²
    //   coverage  = mix(晴, 雨, rain) * fade
    //   cloudBase = clamp01(baseNoise - (0.65 - coverage))
    //   density   = cloudBase * pow2(h·π)/π² ( = cloudBase * h² )  × 5
    // 换上参考噪声后必须走这一段：参考 R 的均值仅 0.498，套我们旧的
    // `(baseNoise-(1-cov))/cov` 会恒为负 ⇒ 天上没有云（已实测）。
    // 量级 ×5 是参考的定标；需要更浓/更稀直接调 `cloudDensity`（cloudParams0.w）。
    {
        const float kPi = 3.14159265;
        float covClear = clamp(cam.cloudParams0.z, 0.0, 0.95);
        float covRain = clamp(cam.cloudParams0.z + 0.30, 0.0, 0.98);
        float cov = mix(covClear, covRain, clamp(cam.weatherParams.x, 0.0, 1.0));
        float hh = clamp(h, 0.0, 1.0);
        cov *= (1.0 - hh * hh);
        float cloudBase = clamp(baseNoise - (0.65 - cov), 0.0, 1.0);
        float profile = (hh * kPi) * (hh * kPi) / (kPi * kPi);   // = h²
        // TODO(侵蚀): 参考的 `max(detail-0.8,0)*0.15` 需要在**函数作用域**拿到 detail
        // （当前 detail 只在上面 `if(...)` 的嵌套块里可见，glslang 报 undeclared）。
        // 下一步把 detail 提到函数作用域后在此扣减。
        density = cloudBase * profile * 5.0;
    }

    return max(density, 0.0) * cam.cloudParams0.w;
}

// 原入口：region 即时算。早退刻意放在 region 之前 —— 视图 march 的绝大多数
// 调用是落在云层外的空样本探测，不该为一次 region 付费（与改动前逐位同序）。
float tyndallDensity(vec3 p, bool cheap, float footprint)
{
    if(cam.cloudParams1.w < 0.5 || abs(cam.weatherParams.z - 1.0) >= 0.25)
        return 0.0;
    float h = tyndallHeight(p);
    if(h <= 0.0 || h >= 1.0) return 0.0;
    return tyndallDensityFromRegions(p, cheap, footprint, tyndallRegionsFor(p));
}

bool tyndallSphereRoots(vec3 p, vec3 direction, float radius, out vec2 roots)
{
    vec2 xz = p.xz - cam.cloudParams3.xy;
    float y = p.y - cam.cloudParams0.x + (TYNDALL_RADIUS - radius);
    float b = dot(vec3(xz.x, y + radius, xz.y), direction);
    float c = dot(xz, xz) + y * (2.0 * radius + y);
    float discriminant = b * b - c;
    if(discriminant < 0.0) return false;
    float q = -b - (b >= 0.0 ? 1.0 : -1.0) * sqrt(discriminant);
    float other = abs(q) > 1.0e-5 ? c / q : -b;
    roots = vec2(min(q, other), max(q, other));
    return true;
}

bool tyndallInterval(vec3 origin, vec3 direction, out float entry, out float exitDistance)
{
    vec2 outer;
    if(!tyndallSphereRoots(origin, direction, TYNDALL_RADIUS + cam.cloudParams0.y, outer))
        return false;
    entry = max(outer.x, 0.0);
    exitDistance = min(outer.y, TYNDALL_MAX_DISTANCE);
    vec2 inner;
    if(tyndallSphereRoots(origin, direction, TYNDALL_RADIUS, inner) && inner.y > entry)
    {
        if(inner.x > entry) exitDistance = min(exitDistance, inner.x);
        else entry = max(entry, inner.y);
    }
    // Bound both absolute distance and in-cloud traversal, including downward rays.
    exitDistance = min(exitDistance, entry + 18000.0);
    return exitDistance > entry + 0.01;
}

// 2026-10-02 Route A：给"看云"消费者（volumetric_clouds.comp 主视图 / rt_test.rmiss
// 天空反射）用的远射程版本。与 tyndallInterval 逐字同构，只有两点不同：
//   (1) 射程上限放到地平线之外（TYNDALL_FAR_DISTANCE，不再被 42000 裁断）；
//   (2) 不设 in-cloud 硬钳 —— 改由调用方的 MIN_TRANSMITTANCE 早退 + 步数上限兜底。
// 云影 / 体积光继续用上面的短程版，避免连带改亮度和成本。
#ifndef TYNDALL_FAR_DISTANCE
// R=6360 km、相机在云底球面下方 d≈3034 格 ⇒ 云底入射距离 ≈ sqrt(2·R·d) ≈ 196 km，
// 穿过 ~3 km 厚的云层后出壳约 277 km；取 ~1.5× 余量。
#define TYNDALL_FAR_DISTANCE 420000.0
#endif
bool tyndallIntervalFar(vec3 origin, vec3 direction, out float entry, out float exitDistance)
{
    vec2 outer;
    if(!tyndallSphereRoots(origin, direction, TYNDALL_RADIUS + cam.cloudParams0.y, outer))
        return false;
    entry = max(outer.x, 0.0);
    exitDistance = min(outer.y, TYNDALL_FAR_DISTANCE);
    vec2 inner;
    if(tyndallSphereRoots(origin, direction, TYNDALL_RADIUS, inner) && inner.y > entry)
    {
        if(inner.x > entry) exitDistance = min(exitDistance, inner.x);
        else entry = max(entry, inner.y);
    }
    return exitDistance > entry + 0.01;
}
// -------------------------------------------------------------------------
// 旧平面云（单平面 + 两次噪声，无 march）已被取代，屏蔽保留。
//
// 2026-10-02 C 档重构：新实现移到 high_cloud_field.glsl（photon 蓝本的双层
// 球壳薄板 + curl 域扭曲 + 解析光学厚 + 可选光 march）。旧实现的三个缺陷：
//   1. abs(direction.y) < 0.005 硬截断 ⇒ 视线压平到地平线附近时整圈云"啪"地
//      消失。取代方案用解析薄板光学厚 extinction·d·THICKNESS/max(|dir.y|,0.05)
//      —— 掠射视线光程 ∝1/|dir.y| 自然加厚变淡，无需截断。
//   2. 平面按 cameraY 求交，俯仰角一变云层整体滑动；取代方案用与体积云同一
//      球壳约定（hcSphereCenter / tyndallSphereRoots），视差正确。
//   3. 无域扭曲、单层、无相函数/powder ⇒ 像贴上去的色块。取代方案双层
//      （卷云 + 卷层云）、curl 扭曲、Beer-Lambert + 双瓣相函数。
// 参数从 cloudParams6（已退役）迁到新尾块 highCloudParams0..4，见
// VulkanCore.cpp / RTXSettingsConfig.java 的 lane 映射。
//
// 保留本段只为逐字节回溯；它不再被任何 .comp/.rgen/.rmiss include。
// 外层用 #if 0 而不是宏守护：即使有人 -D 打开也无效果（退役块规则）。
#if 0
// A single planar intersection and two filtered noise reads, no volume march.
// cloudParams6 = enabled, distance above volume top, coverage, brightness.
vec4 sampleHighCloud(vec3 origin, vec3 direction, out float distance)
{
    distance = 1000000.0;
    if(cam.cloudParams6.x < 0.5 || cam.cloudParams6.z <= 0.0 ||
       abs(cam.weatherParams.z - 1.0) >= 0.25 || abs(direction.y) < 0.005)
        return vec4(0, 0, 0, 1);
    float planeHeight = cam.cloudParams0.x + cam.cloudParams0.y + cam.cloudParams6.y;
    float t = (planeHeight - origin.y) / direction.y;
    if(t <= 0.0 || t > 120000.0) return vec4(0, 0, 0, 1);
    vec2 p = (origin + direction * t + tyndallWind() * 0.6).xz;
    float a = textureLod(cloudShapeNoise, vec3(p * vec2(0.000035, 0.00009), 0.29), 0.0).r;
    float b = textureLod(cloudShapeNoise, vec3(p * 0.00014 + a * 0.1, 0.71), 0.0).r;
    float shape = a * 0.75 + b * 0.25;
    float threshold = mix(0.80, 0.15, cam.cloudParams6.z);
    float opacity = smoothstep(threshold, threshold + 0.18, shape) * 0.60;
    opacity *= 1.0 - smoothstep(70000.0, 120000.0, t);
    if(opacity < 0.001) return vec4(0, 0, 0, 1);
    distance = t;
    float elevation = sin(fract(cam.historyParams.w) * 6.2831853) * 0.819152;
    vec3 tint = mix(vec3(1.0, 0.66, 0.40), vec3(0.91, 0.95, 1.0),
                    smoothstep(0.0, 0.5, elevation));
    float daylight = mix(0.012, 1.0, smoothstep(-0.10, 0.15, elevation));
    float fire = (1.0 - smoothstep(0.04, 0.22, elevation)) * 1.8;
    vec3 color = (tint + vec3(1.0, 0.12, 0.015) * fire) * daylight * cam.cloudParams6.w *
                 mix(1.0, 0.55, clamp(cam.weatherParams.x, 0.0, 1.0));
    return vec4(color * opacity, 1.0 - opacity);
}
#endif

// ---------------------------------------------------------------------------
// 云影密度门控（2026-09-26）：只有"比较大块的云"（云核）参与云影投影。
//
// 两个消费点（rt_test.rgen 的 c3CloudSunTransmittance 地形云影、
// volumetric_light.comp 的 cloudSunTransmittance 光柱云影）在积分光学厚度前
// 先过这一道软门槛：样本密度低于门槛 → 贡献清零，高于门槛 → 减去门槛后按
// 剩余区间归一（软阈值，云影边缘无锯齿）。效果：薄云/云须/碎云不再投云影，
// 地面影子跟随云核成块，覆盖率显著下降。门槛 0 = 所有云参与（恒等）。
//
// 旋钮：TYNDALL_SHADOW_DENSITY_GATE。云密度场的主体分布靠下（大多数样本
// <0.5），0.45 ≈ "只有较厚的一半"，想更稀疏往 0.55~0.65 提，想恢复全云
// 影要么把门槛改 0 要么用 C3_CLOUD_SHADOW_GATE_ON=0 整体关掉（逐字节回旧档）。
// 零性能成本：样本数与 noise 读数不变，只多了几次标量运算。
#ifndef C3_CLOUD_SHADOW_GATE_ON
#define C3_CLOUD_SHADOW_GATE_ON 1
#endif
const float TYNDALL_SHADOW_DENSITY_GATE = 0.45;

float tyndallShadowGate(float density)
{
#if C3_CLOUD_SHADOW_GATE_ON
    return max(density - TYNDALL_SHADOW_DENSITY_GATE, 0.0) /
        (1.0 - TYNDALL_SHADOW_DENSITY_GATE);
#else
    return density;
#endif
}
#endif
