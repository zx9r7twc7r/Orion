#ifndef CLOUD_UE_CORE_GLSL
#define CLOUD_UE_CORE_GLSL

// ===========================================================================
// UE 风格体积云（独立实现，与 cloud_bevy_core.glsl 并列）
//
// 用法：`--model ue` 走这套；`--model bevy`（默认）走旧模型。
//       两套共用相机 / 太阳 / 天空 / 海面 / 雾 / TAA / 云分辨率，
//       对照时差异只来自密度模型与光照积分。
//
// 【许可与出处，必读】
//   本文件不是 Unreal Engine 源码的移植（UE 的 .usf 在 Epic 私有仓库、
//   受 EULA 约束，禁止用于非 UE 产品；本项目是 Minecraft mod）。
//   只按**公开文档**描述的机制重写，代码自创：
//     · Epic 官方文档 "Volumetric Cloud Component"（dev.epicgames.com）：
//       Conservative Density 两段式、Multi Scattering Octaves / Contribution /
//       Occlusion、Ray Marched 阴影 vs Beer Shadow Maps、Ground Contribution、
//       Cloud Ambient Occlusion、Use per Sample Atmospheric Light Transmittance、
//       Cloud Scattering Luminance Scale、Mode 0/1/2 的分辨率与求交语义。
//     · Hillaire 2016 §5.3 —— 多次散射 octave、能量守恒散射积分。
//     · Schneider 2015（HZD）—— powder、双 lobe 相函数。
//   [DOC] = 照文档机制；[自创] = 无公开依据、自行设计。
//
// 【密度模型：三层，对应 UE 的材质分层】
//   weather map（2D 大尺度覆盖）× shape（3D Perlin-Worley）× 高度梯度 − 侵蚀
//   2026-10-22 实测记录（先量后改）：
//     · baseVolume 的 R 分布退化（p10=0 / p50=p90=max=0.804），不能当密度源；
//     · 只用 shape 时云块大而整、但缺大尺度分布（云到处一样多）⇒ 本版补 weather。
//
// 【还没做的（管线级，需要新 pass）】
//   · Beer Shadow Maps（第②刀）
//   · 云影投地 / 光柱（第③刀）
//   · 1/4 trace + 半分辨率重建 + 上采样（第④刀）
//   场景深度求交：接口已就绪（ueRaymarch 的 maxDist），游戏端接线时传真实深度。
// ===========================================================================

// ---------------------------------------------------------------------------
// weather map 对覆盖度阈值的扰动权重 —— **唯一的定义处**。
// ①② 两处阈值必须同口径，否则"保守密度说有云、完整密度算出没云"会漏采
// （表现为云缘针孔、TAA 拖影）。原先 0.5 是两处各写一份的魔数，2026-10-22
// 收到 0.06 并提到这里，理由见 ① 下方注释（2D 图集 + 掠射角 = 放射状条纹）。
// 量级判据：必须**远小于** ② 的 smoothstep 窗口（0.08）。
// ---------------------------------------------------------------------------
const float UE_WEATHER_THRESHOLD_WEIGHT = 0.06;

struct UeCloudConfig
{
    float bottomHeight;
    float topHeight;
    float planetRadius;

    float coverage;          // [DOC] 覆盖度：被云遮挡的天空比例
    float density;           // [DOC] 消光系数 σtBase（每格）
    float shapeWorldScale;   // 形状体世界周期（格）
    float weatherScale;      // [DOC] weather map 采样频率（1/格）
    float detailScale;       // 侵蚀频率
    float detailStrength;    // 侵蚀幅度

    int viewSteps;           // [DOC] ViewRaySampleMaxCount 同义
    int shadowSteps;         // [DOC] ShadowViewSampleCountScale 同义
    int msOctaves;           // [DOC] Multi Scattering Octaves（游戏建议 1）
    float msContribution;    // [DOC] Multi Scattering Contribution
    float msOcclusion;       // [DOC] Multi Scattering Occlusion
    float luminanceScale;    // [DOC] Cloud Scattering Luminance Scale（octave 能量补偿）

    vec3 sunDir;
    vec3 sunColor;
    vec3 ambientTop;
    vec3 ambientBottom;
    vec3 groundAlbedo;       // [DOC] Ground Albedo
    float groundContribution; // [DOC] Ground Contribution
    float cloudAO;           // [DOC] Cloud Ambient Occlusion 强度
    float atmosphereDensity; // [DOC] 逐采样大气透射率强度（0 = 关闭）
    float forwardG;
    float backwardG;

    float viewDistance;
    float fadeStart;
    float minTransmittance;
    float time;
    float shadowStepSize;
};

float ueHG(float cosTheta, float g);   // 前置声明，定义在文件末尾

float ueNormalizedHeight(vec3 pos, UeCloudConfig cfg)
{
    const float h = cfg.topHeight - cfg.bottomHeight;
    return (length(pos) - (cfg.planetRadius + cfg.bottomHeight)) / max(h, 1.0);
}

// 球壳求交，与 bevy 的 bevyIntersectShell 同一套数值稳定写法。
// 不能用教科书的 c/(s+b)：相机在云层之上俯视时 b ≈ -3.6e6、sqrt(d) ≈ +3.6e6，
// float32 里相消掉 6 位有效数字，剩下的全是噪声 ⇒ 俯视整片"没有云"。
float ueIntersectShell(float originRadius, float rayDirY, float sphereRadius)
{
    const float b = originRadius * rayDirY;
    const float c0 = originRadius * originRadius - sphereRadius * sphereRadius;
    const float d = b * b - c0;
    if(d < 0.0) return -1.0;
    const float s = sqrt(d);
    const float q = -(b + (b >= 0.0 ? s : -s));
    if(abs(q) < 1.0e-9) return 0.0;
    return c0 / q;
}

// [DOC] 高度剖面：UE 由材质给高度梯度，预览没有材质图，
// 用 Hillaire 的 stratus / cumulus 曲线代替 [自创]。
float ueHeightProfile(float h)
{
    const float pCumulus = smoothstep(0.04, 0.18, h) * (1.0 - smoothstep(0.62, 0.94, h));
    const float pStratus = smoothstep(0.00, 0.10, h) * (1.0 - smoothstep(0.84, 1.00, h));
    return mix(pStratus, pCumulus, 0.6);
}

// freqMul：采样频率倍率（1 = 基准；>1 更细），phase：相位偏移（REPEAT 寻址下用它错开）。
float ueShapeSampleFreq(vec3 pos, UeCloudConfig cfg, sampler3D shapeTex,
    float freqMul, vec3 phase)
{
    const vec3 localPos = pos - vec3(0.0, cfg.planetRadius, 0.0);
    const float inv = freqMul / max(cfg.shapeWorldScale, 100.0);
    return clamp(texture(shapeTex, localPos * inv + phase).r, 0.0, 1.0);
}

float ueShapeSample(vec3 pos, UeCloudConfig cfg, sampler3D shapeTex)
{
    return ueShapeSampleFreq(pos, cfg, shapeTex, 1.0, vec3(0.0));
}

// [DOC] Weather Map：大尺度覆盖（哪些区域有云），与 shape（长什么样）分开。
// 用现成的 2D 图集：它的 R 就是 bevy 系烘出来的覆盖度场，坐标口径与
// bevyCloudMapBase 的 0.00035*baseScale 一致，保证两边"云量分布"同源。
float ueWeatherSample(vec3 pos, UeCloudConfig cfg, sampler2D atlasTex)
{
    const vec3 localPos = pos - vec3(0.0, cfg.planetRadius, 0.0);
    return clamp(texture(atlasTex, localPos.xz * max(cfg.weatherScale, 1.0e-8)).r, 0.0, 1.0);
}

// ---------------------------------------------------------------------------
// ① [DOC] Conservative Density —— 核心
//   官方文档：先求值它，X <= 0 就跳到下一个采样，不进完整材质求值
//   （材质图没有动态分支）。规则：有云处必须 > 0，宁可高估不可漏掉。
//   这里 = 2 次纹理采样（weather + shape）+ 高度剖面，窗口比完整密度更宽。
// ---------------------------------------------------------------------------
float ueConservativeDensity(vec3 pos, float h, UeCloudConfig cfg,
    sampler2D atlasTex, sampler3D shapeTex)
{
    if(h < 0.0 || h > 1.0) return 0.0;

    const float s = ueShapeSample(pos, cfg, shapeTex);
    const float profile = ueHeightProfile(h);

    // ⚠ weather map **只移动覆盖度阈值**，不直接乘进密度。
    // 直接乘会得到竖直柱子：weather 是 2D 图集、沿 y 恒定，乘进去之后密度在 y 方向
    // 只剩 profile 在变 ⇒ 刷子扫过的竖条。这不是新问题，本项目 HANDOFF 第十轮
    // 已经实测记录过同一条（"w=0.35 拖笔减轻但云缘碎成竖条"）。
    // UE 里 weather 也是 2D 的，但它只决定"覆盖度"（哪些区域有云），
    // 形状由 3D 材质负责 —— 所以这里改成阈值调制。
    // ⚠ weather 的权重必须**远小于** smoothstep 的窗口宽度。理由：它是 2D 图集
    // （沿 y 恒定），掠射角会把这个 2D 图案投影成"从地平线发散的放射线" ——
    // 本仓库 HANDOFF §4 #12 记录过同款现象，2026-10-22 我(=实现方)又把权重给到
    // 0.5（= ② 的 0.08 窗口的 6 倍），实测游戏内整片天空变成放射状条纹。
    // 权重见文件头 UE_WEATHER_THRESHOLD_WEIGHT（①② 共用一份，不许各写魔数）。
    const float w = ueWeatherSample(pos, cfg, atlasTex);
    const float lo = 1.0 - clamp(cfg.coverage, 0.0, 1.0) +
        (0.5 - w) * UE_WEATHER_THRESHOLD_WEIGHT;

    // 比 ② 的窗口宽 ±0.15 —— "宁可高估"的落地。
    const float covMask = smoothstep(lo - 0.15, lo + 0.35, s);

    return clamp(covMask * profile, 0.0, 1.0);
}

// ---------------------------------------------------------------------------
// ② 完整密度 = weather × shape × 剖面 − 云缘侵蚀
//   [DOC] 保守密度 > 0 之后才求值（文档里是 Albedo/Emissive/Extinction）。
// ---------------------------------------------------------------------------
float ueDensity(vec3 pos, float h, UeCloudConfig cfg,
    sampler2D atlasTex, sampler3D shapeTex, sampler3D worleyTex)
{
    if(ueConservativeDensity(pos, h, cfg, atlasTex, shapeTex) <= 0.004) return 0.0;

    const float w = ueWeatherSample(pos, cfg, atlasTex);

    // 2026-10-22 高频调制（只加在完整密度里，保守密度保持单次采样）：
    // 同源纹理、坐标乘 2.7 + 相位错开 ⇒ 频率约 2.7 倍的细节层。
    // 用**乘法** (0.70 + 0.60*s1) 而不是 mix：mix 会把大形状一起削平，
    // 乘法只在大轮廓表面加起伏。系数取 s1 均值 0.5 时乘数恰为 1.0，
    // 所以平均密度不变（实测 mean 103.7 → 103.9，基本没漂）。
    // 2.7 这个比值与 1 不可公度（参照本仓库双尺度用 2.37 的思路），避免网格感。
    // 为什么这里安全：保守密度的判断窗口是 smoothstep(lo-0.15, lo+0.35, s0)，
    // 比调制后的波动幅度（±30%）宽得多，不会出现"保守说没云、完整算出有云"的漏采。
    const float sBase = ueShapeSample(pos, cfg, shapeTex);
    const float sDetail = ueShapeSampleFreq(pos, cfg, shapeTex, 2.7, vec3(0.37, 0.11, 0.73));
    const float s = clamp(sBase * (0.70 + 0.60 * sDetail), 0.0, 1.0);
    // 与 ① 同一条规则、同一个常量：weather 只做**极小的**阈值扰动
    // （UE_WEATHER_THRESHOLD_WEIGHT，远小于下面的 0.08 窗口），形状完全由 3D
    // shape 决定。权重给大就会被 2D 图集主导 ⇒ 掠射时出放射线。
    const float lo = 1.0 - clamp(cfg.coverage, 0.0, 1.0) +
        (0.5 - w) * UE_WEATHER_THRESHOLD_WEIGHT;
    // 过渡带宽 0.18 → 0.08（2026-10-22）。
    // 0.18 是把云缘拉成一大片渐变，近处大云团看着像棉花糖；侵蚀削的是密度，
    // 改不了这个带宽 —— 宽度由这个常量单独决定。收窄后边界更硬、更"撕"。
    // 注意不能收到 0：完全硬切会让采样不足的地方出现锯齿状色阶。
    const float base = smoothstep(lo, lo + 0.08, s) * ueHeightProfile(h);
    if(base <= 0.004) return 0.0;

    // 侵蚀只作用于云缘：云心保持实心，否则视线会从云体中间穿出去（"纸片镂空"）。
    //
    // 2026-10-22 侵蚀改成**两层**。频率比与幅度照本仓库 HANDOFF 第十五轮的实测结论：
    //   "侵蚀 2 层 → 3 层，且全部往**粗**的方向铺（频率比 1 : 0.42 : 0.18，
    //     幅度 0.70/0.40/0.22）。关键经验：比步长（~13 格）更细的 octave 采不到，
    //     只会变成 TAA 平均掉的噪声，所以第二个 octave 千万别往上加 —— 加了反而更平滑。"
    // 这里取前两层：1 和 0.42（更粗），幅度 0.70 / 0.40。相位用固定偏移错开。
    const vec3 localPos = pos - vec3(0.0, cfg.planetRadius, 0.0);
    const float detailFreq = 0.0016 * cfg.detailScale * (1.0 / 32.0);

    const float d1 = clamp(texture(worleyTex, localPos * detailFreq).r, 0.0, 1.0);
    const float d2 = clamp(texture(worleyTex,
        localPos * (detailFreq * 0.42) + vec3(0.37, 0.11, 0.73)).r, 0.0, 1.0);

    const float edgeMask = 1.0 - smoothstep(0.30, 0.80, base);
    const float erosion = clamp(cfg.detailStrength, 0.0, 2.0) * 0.42 *
        mix(0.15, edgeMask, 0.85) * ((1.0 - d1) * 0.70 + (1.0 - d2) * 0.40);

    return clamp(base - erosion, 0.0, 1.0);
}

// ---------------------------------------------------------------------------
// [DOC] 逐采样大气光透射率（Use per Sample Atmospheric Light Transmittance）。
// 官方文档：用逐采样透射代替定向光的全局透射，更准但更贵。
// [自创] 的实现：按样本海拔做指数近似（真实做法要查 transmittance LUT，
// 预览器没有那条链）。海拔越高 → 到太阳的光程越短 → 透过率越高，这与物理一致。
// atmosphereDensity = 0 时恒为 1（不影响观感，可当开关）。
// ---------------------------------------------------------------------------
float ueAtmosphericTransmittance(vec3 pos, UeCloudConfig cfg)
{
    if(cfg.atmosphereDensity <= 0.0) return 1.0;
    const float altitude = max(length(pos) - cfg.planetRadius, 0.0);
    const float scaleHeight = 8000.0;   // 大气标高（格；1 格 ≈ 1 m）
    return exp(-altitude / scaleHeight * cfg.atmosphereDensity);
}

// ---------------------------------------------------------------------------
// ③ [DOC] 向阳透射率：等距步进 + 射程钳制 + 保守密度剪枝
//   官方文档叫 Ray Marched volume shadow，开销由 ShadowViewSampleCountScale 控制。
// ---------------------------------------------------------------------------
float ueSunTransmittance(vec3 origin, UeCloudConfig cfg,
    sampler2D atlasTex, sampler3D shapeTex, sampler3D worleyTex)
{
    const int steps = max(cfg.shadowSteps, 1);
    const float stepCount = float(steps);

    // 沿太阳方向穿出云层顶的距离（origin 在壳内 ⇒ 远交点 = -b + sqrt(d)）。
    const float originRadius = length(origin);
    const float topRadius = cfg.planetRadius + cfg.topHeight;
    const float b = originRadius * cfg.sunDir.y;
    const float c0 = originRadius * originRadius - topRadius * topRadius;
    const float disc = max(b * b - c0, 0.0);
    const float tExit = -b + sqrt(disc);

    const float maxLightDistance = cfg.shadowStepSize * stepCount;
    const float totalLightDistance = clamp(tExit, 0.0, maxLightDistance);
    const float intervalSize = totalLightDistance / stepCount;

    float opticalDepth = 0.0;
    for(int j = 0; j < 32; ++j)
    {
        if(j >= steps) break;
        const vec3 pos = origin + cfg.sunDir * (intervalSize * (0.25 + float(j)));
        const float h = ueNormalizedHeight(pos, cfg);
        if(h > 1.0) break;

        // 关键：先过保守密度，绝大多数样本只付 2 次纹理采样。
        if(ueConservativeDensity(pos, h, cfg, atlasTex, shapeTex) <= 0.004) continue;
        opticalDepth += ueDensity(pos, h, cfg, atlasTex, shapeTex, worleyTex);
    }

    const float tau = opticalDepth * cfg.density * intervalSize;
    return exp(-tau);
}

// ---------------------------------------------------------------------------
// ④ [DOC] 多次散射 octaves：每阶用更小的消光系数重新积分（exp(-τ·k^i)），
//   能量按 Contribution^i 衰减。官方文档取 0/1/2，建议游戏只用 1。
//   ⚠ 严格说 UE 是"用更小的消光重新 march 一遍密度"，这里是对已算出的 τ 乘 k^i
//     （Hillaire 的零成本近似）。差别在精度，不在成本。
// ---------------------------------------------------------------------------
float ueMultiScatterShadow(float tau, UeCloudConfig cfg)
{
    const int octaves = clamp(cfg.msOctaves, 1, 3);
    float total = 0.0;
    float amp = 1.0;
    float k = 1.0;
    for(int o = 0; o < 3; ++o)
    {
        if(o >= octaves) break;
        total += amp * exp(-tau * k);
        amp *= clamp(cfg.msContribution, 0.0, 1.0);
        k *= clamp(cfg.msOcclusion, 0.05, 1.0);
    }
    return total;
}

// ---------------------------------------------------------------------------
// ⑤ 主 march。输出约定与 bevy 版一致：rgb = 散射辐亮度（HDR），a = 透射率。
//   maxDist 就是场景深度求交的接口：游戏端传 min(视距, 场景距离)，
//   预览器没有场景几何所以传 1e9（外部还有海面/天空的独立分支）。
// ---------------------------------------------------------------------------
vec4 ueRaymarch(vec3 rayOrigin, vec3 rayDir, float maxDist, UeCloudConfig cfg,
    sampler2D atlasTex, sampler3D shapeTex, sampler3D worleyTex, out float hitDistance)
{
    hitDistance = maxDist;

    // 球壳求交：与 bevyRaymarch 的区间语义逐字一致（层内 / 层外两种情形），
    // 两边共用同一套求交，对照时才不会把"求交差异"误读成"模型差异"。
    const float originRadius = length(rayOrigin);
    const float bottomRadius = cfg.planetRadius + cfg.bottomHeight;
    const float topRadius = cfg.planetRadius + cfg.topHeight;

    const float tBottom = ueIntersectShell(originRadius, rayDir.y, bottomRadius);
    const float tTop = ueIntersectShell(originRadius, rayDir.y, topRadius);

    float start = 0.0;
    float end = 0.0;
    {
        const bool insideShell = (tBottom <= 0.0 && tTop > 0.0);
        if(insideShell)
        {
            start = 0.0;
            end = (rayDir.y >= 0.0) ? tTop : tBottom;
        }
        else
        {
            start = max(min(tBottom, tTop), 0.0);
            end = max(tBottom, tTop);
        }
    }

    end = min(end, maxDist);
    if(!(end > start)) return vec4(0.0, 0.0, 0.0, 1.0);
    if(start > maxDist) return vec4(0.0, 0.0, 0.0, 1.0);

    const int steps = max(cfg.viewSteps, 1);
    const float stepDistance = (end - start) / float(steps);

    // [自创] 起点抖动：每帧变化，交给 TAA 累积压掉步进分层。
    const float jitter = fract(cfg.time * 0.6180339887 +
        float(gl_GlobalInvocationID.x * 17u + gl_GlobalInvocationID.y * 131u) * 0.0001);

    const float rayDotSun = dot(rayDir, cfg.sunDir);
    const float phase = mix(ueHG(rayDotSun, cfg.forwardG), ueHG(rayDotSun, cfg.backwardG), 0.5);

    vec3 scatteredLight = vec3(0.0);
    float transmittance = 1.0;
    float dist = maxDist;
    float dirLength = start - stepDistance * jitter;

    for(int step = 0; step < 256; ++step)
    {
        if(step >= steps) break;
        const vec3 worldPosition = rayOrigin + dirLength * rayDir;
        const float h = ueNormalizedHeight(worldPosition, cfg);

        // [DOC] 两段式：先保守密度。
        float density = 0.0;
        if(ueConservativeDensity(worldPosition, h, cfg, atlasTex, shapeTex) > 0.004)
        {
            density = ueDensity(worldPosition, h, cfg, atlasTex, shapeTex, worleyTex);
        }

        if(density > 0.0)
        {
            dist = min(dist, dirLength);

            const float sigmaT = density * cfg.density;
            const float shadow = ueSunTransmittance(worldPosition, cfg, atlasTex, shapeTex, worleyTex);

            // [DOC] 多次散射作用在光学厚度上。
            const float tauAlongLight = -log(max(shadow, 1.0e-6));
            const float msShadow = ueMultiScatterShadow(tauAlongLight, cfg);
            const float litShadow = clamp(mix(shadow, msShadow, 0.5), 0.0, 1.0);

            // [自创] powder（Schneider HZD 形式，用 σt 而不是裸密度）。
            const float powder = 1.0 - exp(-sigmaT * 60.0);
            const float powderWeight = clamp(rayDotSun, 0.0, 1.0);
            const float powderTerm = mix(1.0, 0.4 + 0.6 * powder, powderWeight);

            // [DOC] Cloud Ambient Occlusion：云遮挡天空光，用向阳光学厚度的指数衰减
            // 表现"柔和的环境阴影"。cloudAO 是强度：0 = 不遮挡（原行为），1 = 物理透射率。
            const float skyVis = exp(-tauAlongLight * clamp(cfg.cloudAO, 0.0, 4.0));

            // [DOC] 逐采样大气透射率：只作用在直射光上（天光本身已是散射结果）。
            const float atmT = ueAtmosphericTransmittance(worldPosition, cfg);

            // [DOC] Ground Contribution / Ground Albedo：云底受地面反照照亮。
            // 用一个随高度指数衰减的底部项表示"越靠近云底，看到的地面越多"。
            const vec3 groundLight = cfg.groundAlbedo * cfg.groundContribution *
                exp(-clamp(h, 0.0, 1.0) * 5.0);

            const vec3 ambient = mix(cfg.ambientBottom, cfg.ambientTop, clamp(h, 0.0, 1.0)) *
                skyVis + groundLight;

            // [DOC] Cloud Scattering Luminance Scale：补偿 octave 近似的能量损失。
            const vec3 L = cfg.sunColor * phase * litShadow * powderTerm * atmT * cfg.luminanceScale;

            const float albedo = 0.98;
            const float deltaT = exp(-sigmaT * stepDistance);

            // 能量守恒积分：∫ σs·L·T ds。σs = σt × albedo，与 bevy 版同一形式。
            scatteredLight += transmittance * sigmaT * albedo * (ambient + L) *
                (1.0 - deltaT) / max(sigmaT * albedo, 1.0e-5);
            transmittance *= deltaT;
        }

        if(transmittance <= cfg.minTransmittance) break;
        dirLength += stepDistance;
    }

    // 远处淡出，避免视距处一条硬切边。
    const float fadeBegin = cfg.viewDistance * clamp(cfg.fadeStart, 0.0, 0.99);
    const float distFade = 1.0 - smoothstep(fadeBegin, cfg.viewDistance, dist);
    scatteredLight *= distFade;
    transmittance = mix(1.0, transmittance, distFade);

    hitDistance = dist;
    return vec4(scatteredLight, transmittance);
}

// [自创但必须如此] 相位函数**不做 4π 归一化**。
// 整条光照链的标定（sunColor ≈ 0.75、exposure 0.5、ambient 0.11/0.08）都是对着
// bevyHG 调的，而它没有除 4π（cloud_bevy_core.glsl:1144）。归一化会让散射整体
// 暗 12.6 倍 —— 实测第一版就是"整屏发黑只剩一团"（out/ue.png）。
float ueHG(float cosTheta, float g)
{
    const float g2 = g * g;
    const float d = max(1.0 + g2 - 2.0 * g * cosTheta, 1.0e-4);
    return (1.0 - g2) / (d * sqrt(d));
}

#endif  // CLOUD_UE_CORE_GLSL
