// ===========================================================================
// Shared water wave field.
//
// 2026-10-03：**旧解析自然波面已整体删除**（用户要求）。
//
// 现在水面形态由**波方程场单独决定**：
//   · water_sim.comp 的常驻风源 + 交互脉冲 → 波高/速度场（RG16F 双缓冲）
//   · 本文件只负责把该场转成光学斜率（waterInteractionSlope）
// 这与参考 HPWater 的机制一致（HPWaterFluidDynamics.cs：没有解析波带、没有风场，
// 只有一套波方程场 + 外部波源；HPWaterFluidHeightToNormal 就是同一套中心差分）。
//
// 被删除的内容：两条 Gerstner 长波带、三组域扭曲、三档噪声倍频程、以及只服务它们
// 的辅助函数 accumulateFineGerstnerOpticalWave。
// 副作用：GUI 的「波长倍率 / 主波向 / 涌浪陡度 / 波峰扭曲」失去作用对象（它们只
// 作用在被删掉的解析波带上）；解析焦散也失去波前细节（焦散与波面同源）。
// 需要这两层时，再按波方程场给它们重新接靶。
//
// Include this AFTER `cam` (CameraProperties) is declared: the field reads
// cam.waterSim*.
// ===========================================================================

// 2026-10-15 退役重构：本头文件被两部分消费者共用 ——
//   ① 水面/焦散（rchit / rgen）：需要 binding 33（交互场）与 59（FFT 涌浪）。
//   ② 体积光波闪（volumetric_light.comp）：只要无描述符回退的解析细波斜率
//，不能引入任何新 binding
//      （否则要为本 pass 扩 C++ 描述符布局）。
// 故这两个 sampler 声明用 WATER_WAVE_FIELD_NO_DESCRIPTORS 守卫：
// 消费方在 include 之前 #define 它，即可拿到与本文件同一份纯计算函数，
// 却不引入 binding。见 volumetric_light.comp 的 include 区。
#ifndef WATER_WAVE_FIELD_NO_DESCRIPTORS
// Two-layer RG16F persistent height/velocity field.  The current readable layer
// is selected through the UBO, so no descriptor is rewritten while frames are
// in flight.
layout(binding = 33, set = 0) uniform sampler2DArray waterSimulationState;

// 2026-10-03 FFT 频谱海面（方案 B）：binding 59 = 宿主预烘的**斜率图**
// （256² RGBA16F，LINEAR + REPEAT ⇒ 按世界 XZ 平铺无缝）。
//   .rg = 基础层斜率（128 格平铺周期，最小波长 ~1 格）
//   .ba = 2026-10-19 细节层斜率（32 格平铺、谱高通过滤 ⇒ 0.25~1 格亚格小波）
// 着色端对同一张图做 2 次取样（两套 UV/LOD），叠加后仍只是一条光学法线：
// 水面几何/RT 求交完全不动，小波只活在法线里。安全阀见下面的
// waterNaturalWaveGradient()：cam.waterFftParams.y <= 0 时绝不取样。
layout(binding = 59, set = 0) uniform sampler2D waterFftSlope;
#endif // WATER_WAVE_FIELD_NO_DESCRIPTORS

// 2026-10-17 定档：
// 可见水面的波面法线 = **单条 Tessendorf FFT 海洋**（，见下方
// waterFftSwellSlope）——宿主 FFT 链（N=256，基础层 19 趟 + 细节层 18 趟）预烘
// 斜率图，本文件按 UBO 的平铺周期取样（2026-10-19 起同一张图再带一层亚格细波）。
//
// 安全阀：cam.waterFftParams.y≤ 0 时
// 直接返回 0，保证绝不采样一个未写入的 binding 59。
// ---------------------------------------------------------------------------
// 涌浪层依赖 binding 59（FFT 预烘斜率图）。无描述符的消费者（体积光波闪）用
// WATER_WAVE_FIELD_NO_DESCRIPTORS 关闭本层 —— 它走解析细波回退。
#if !defined(WATER_WAVE_FIELD_NO_DESCRIPTORS)
#define WATER_WAVES_FFT_SWELL 1
// 细节层世界平铺周期（格）。**必须与宿主 WATER_FFT_DETAIL_TILE 一致**：
// 宿主用同一个周期烘焙细节层（谱高通过滤后只剩 0.25~1 格的小波），这里用它
// 把 .ba 映射回世界坐标。与基础层平铺周期无关 ⇒ 基础层滑杆只改大浪尺度。
#define WATER_FFT_DETAIL_TILE 32.0
#endif

// ---------------------------------------------------------------------------
// 2026-10-15 **旧后端全部退役**（用户指令："把已经死的没用的设置入口退役一下"）。
//
// 退役对象（都曾是可见水面的候选后端，被 ROUTE C 取代后仅剩 A/B 回退价值，
// 而用户明确定调"全删，只留 ROUTE C"）：
//   · WATER_WAVES_SEASCAPE  Seascape 式 fbm 浪 —— 可见水面早已不用；退役后
//       本文件不再 include 它，且体积光波闪也一并改到 ROUTE C ⇒ 该文件**彻底
//       无消费者，已删除**（备份 `water_seascape.glsl.bak_retired_20261015`）。
//   · WATER_WAVES_NOISE     psrdnoise 噪声波 —— 见文件尾注：
//       `water_noise_wave.glsl` 仍被 rt_test_translucent.rchit 直接 include 用作
//       **透明水面**，故那个文件本身保留；退役的只是本文件里的分发臂 +
//       本文件对它的转发包含（退役后本文件零引用其符号）。
//   · WATER_WAVES_AFL       afl getwaves（CC BY-NC）。文件体删除。
//   · WATER_WAVES_FBM       ROUTE A（FBM-on-sum-of-sines）。文件体删除。
//   · WATER_WAVES_GUNNELL   ROUTE B（SteepSine 波列）。ROUTE C 是它的方向生成
//       变体，共用全部旋钮；退役后 `GUNNELL_DIRS` 常量表与其函数体一并删除。
//
// 连带退役（本文件内）：
//   · `waterFbmWaveRandom` 之上原属 ROUTE A/B 的专用注释（该哈希函数**保留**：
//       ROUTE C 的逐层相位仍用它，故它现在服务唯一后端）；
//   · 退役的路线 A′（WATER_WAVES_NOISETEX）整块 `#if 0` 屏蔽代码；
//   · 分发链的 `#elif` 天梯 —— 只剩 ROUTE C 一条 ⇒ 无需再接链。
//
// 复活任一路线：见 deliverables/水面波面退役旧后端-2026-10-15/README.md 的回退表
//（都靠 git 历史；退役后工程内不再保留源码副本，这是用户接受的代价）。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 2026-10-03 用户反馈：水面出现**笔直的贯穿亮/暗线**（没下水也有）。
// 根因：交互波场（water_sim 的 512² 窗口）在风源修复后**常开**（跟在玩家身边的
// 32×32 格），而着色端边框淡出只有 2.5% ≈ 0.8 格 ⇒ 斜率在一步内从全场值跳到 0，
// 方框边缘扫过水面就是一条笔直的明暗线。**用户明确要保留交互波场** ⇒ 不停用，
// 修法是把边框淡出加宽到 12%（≈3.8 格，见 waterInteractionSlope），斜率平滑归零。
// 本开关保留作诊断/回退用（1 = 启用交互波场）。
// ---------------------------------------------------------------------------
#define WATER_INTERACTION_WAVES 1

// 2026-10-15 退役重构：本文件已**不再引用** water_noise_wave.glsl 的任何符号
// （WATER_WAVES_NOISE 分发臂删除后，waterNoiseOctaveSlope / waterNoiseWaveGradient
// 在本文件中零引用）。故转发包含一并移除 —— 免得每个消费者（rgen/rchit/comp）
// 都白编译一份 psrdnoise。该文件本身仍存活：rt_test_translucent.rchit 直接
// include 它作**透明水面**。


// ---------------------------------------------------------------------------
// 2026-10-17 ** ROUTE C 整体屏蔽（用户指令"不用 PhysicsOcean"后的最终定档）**：
// 波面后端换成下方 waterSundialWaveSlopeCapped —— 自带水面
//（Water.glsl: waterWaveNormal）的完整移植（剖面 + 噪声域扭曲 + 旋转链全保留），
// 不再是 2026-10-15 那版"只借逐层方位结构"的 ROUTE C。按既定惯例 #if 0 屏蔽
// 保留源码，不删除。
// ---------------------------------------------------------------------------
#if 0

// ---------------------------------------------------------------------------
// 逐波常量哈希（ROUTE A / ROUTE B **共用**，故放在后端 #if 之外）。
// 把波序号位混成 4 个独立 [0,1) 随机数。整数位混（见 sun_shadow.glsl 的
// sunShadowJitter 同族）⇒ 零 SFU；这些量与采样点无关（每像素恒定），所以放在逐波
// 循环里也不会有额外负担。
// ---------------------------------------------------------------------------
vec4 waterFbmWaveRandom(uint index)
{
    uint hash = index * 747796405u + 2891336453u;
    hash ^= hash >> 16;
    hash *= 0x7feb352du;
    hash ^= hash >> 15;
    hash *= 0x846ca68bu;
    hash ^= hash >> 16;
    const vec2 first = vec2(
        float(hash & 0xffffu), float((hash >> 16) & 0xffffu)) / 65535.0;

    uint hash2 = hash * 2246822519u + 3266489917u;
    hash2 ^= hash2 >> 16;
    hash2 *= 0x7feb352du;
    hash2 ^= hash2 >> 15;
    hash2 *= 0x846ca68bu;
    hash2 ^= hash2 >> 16;
    const vec2 second = vec2(
        float(hash2 & 0xffffu), float((hash2 >> 16) & 0xffffu)) / 65535.0;

    return vec4(first, second);
}


// ---------------------------------------------------------------------------
// 2026-10-15 波面后端 **ROUTE C：陡剖面波 + 逐层方位旋转**
// —— 参考 `Dev Build 2026-09-16` 的 waterWaveNormal
//    （该文件头无 LICENSE，**只取结构思想、代码全部自写**，
//     与仓库既有对 ocean-waves 等"无 LICENSE"项目的处理一致）。
//
// 为什么只取"结构"而不是整段移植 —— 本文件头的判据是**重复性**，先看 CPU 实测：
//
//   （判据见 water-waves.md：格点判据 p 存在 ⟺ corr(p)、corr(2p)、corr(3p) 均 ≥0.9；
//     括号内为旧口径"归一化自相关最大值"，仅参考、不作判决。）
//
//   | 实现                                  | 格点周期 p(24/96格) | maxcorr(24/96) |
//   | ---                                   | ---                | ---            |
// | waterWaveNormal（完整） | None / None | 0.082 / 0.101 |
// | 纯波层（**去掉噪声扭曲**） | None / None | 0.780 / 0.676 |
// | physics_waveNormal（海洋分支） | None / None | 0.918 / 0.332 |
//   | ROUTE B 现网档（3.5/.90/12/8）         | None / None        | 0.064 / 0.097  |
//
//   两条结论（都是这次调研的**否定性**结论，先摆出来免得后人再试一次）：
// ① 完整波形与本项目 ROUTE B **同档**（0.082 vs 0.064 / 0.101 vs 0.097），
//        **整段移植不会改善重复**。它那个看起来很低的 maxcorr 0.04（按 rms 归一后）
//        几乎全部来自**噪声相位扭曲**：把该项置 0（纯 5 层金角剖面波）maxcorr 立刻
//        升到 0.780（24 格）——比 ROUTE B 差一个数量级。而它的噪声项 UV 步长约
//        1.0/像素（coord×32 × 0.003×64），本质是**逐像素白噪**；本项目在 RT+DLSS
//        下反复确认过这类亚像元高频会闪（见 water-waves.md「按像素足迹逐分量剔除」、
//        ROUTE A/B 的 0.30~2.0 格细端下限），所以这一项**明确不移植**。
//     ② physics_waveNormal（参考的"物理海洋"分支）maxcorr 24 格 0.918 ⇒ 它是三条里
//        最重复的（exp-sin 单谱族 + 固定 iter+=12 方向序列），也不移植。
//
// 那移植什么 —— 只移植参考里**唯一在设计上独立于路线的那个点**：
//   `normalRotation`（Water.glsl:62/70/71）：每层把**已经累加出来的梯度矢量**再做一次
//   方位旋转，且旋转矩阵逐层左乘 `mat2(scale)·goldenRotate` ⇒ **各层朝向互不相同**。
//   这与"旋转采样坐标"（golden-angle）**正交**：坐标旋转改的是每层的相位取样位置，
//   方位旋转改的是每层梯度所占的**方向**。CPU 实测（本后端同参数、只切方向生成）：
//
//       96 格：哈希方向 maxcorr 0.097 → 逐层黄金角累加方向 0.067（-31%）
//       24 格：哈希方向 maxcorr 0.064 → 逐层黄金角累加方向 0.058（-9%）
//
//   这正是本项目 `water-waves.md` 结论 1「5 条波列只有 5 个自由度 ⇒ 孤立近重合调参消不掉」
//   的那个自由度的**补法**：方向不再是 5 个独立常量，而是 5 个**被约束在一条黄金角序列
//   上**的方位 ⇒ 自相关的旁瓣被摊平。
//
// 与原参考的逐条差异（都为"只要斜率 + 本项目 RT"做的适配）：
//   · 借鉴其 **1D 陡剖面** `cos(K·(|x|+C2)^S − K·C1)`（profile），但**保留本项目的
//     SteepSine**做峰形 —— 两者都是"浪尖窄、浪谷宽"，本项目的版本已量化成整数走
//     乘法链（零 SFU）；为**不改变"波峰锐化"滑杆口径**，峰形不换成参考的 pow 版。
//     ⇒ S 仍由 `waterFftParams.z`（既有「波峰锐化」）给。
//   · **不采样任何噪声贴图**（理由见上 ①）⇒ 无新 descriptor、无新 binding、
//     复现性与既有 ROUTE B 同级（纯程序化）。
//   · 逐层方位 = 黄金角累加（`iter += GOLDEN`，全程序化），不是参考的矩阵左乘链
//     —— 等价于同一个"逐层转固定角"的意图，但**零矩阵乘法**、零额外寄存器。
//   · 细端仍是**视距相关的连续波长下限**（ROUTE B 同款：0.30→2.0 格），
//     因为参考的噪声项被拿掉了，防闪必须靠它。
//   · 深水色散 ω=√(g·k) 保留（ROUTE B 已证"波不再整体平移"）。
//
// 旋钮（**完全复用 ROUTE B 的既有四件套**，设置入口一个都不新增）：
//   专用（CameraUBO.waterWaveShapeParams，偏移 1248；closest-hit / rgen 读）
//     x 最长波长 λmax（格，1..8）  y 域扭曲 drag（0..1.5）
//     z 波数 count（1..16）        w 波长带宽比 band（2..32，λmin = λmax/band）
//   共用（既有「海浪」一组滑杆）：海浪强度 waterFftParams.y（同时是 0 = 关的安全阀）/
//     浪速 waterFftParams.w / 波峰锐化 waterFftParams.z（= SteepSine 指数）/ 海浪风向
//     waterFftParams2.x。
//
// 2026-10-15：**本后端是唯一的自然波后端**（旧 ROUTE B 等已按用户指令退役，
//   源码从本文件移除）。原 WATER_WAVES_DIRROT 宏随之取消 —— 函数无条件编译。
// ---------------------------------------------------------------------------

// 逐层方位旋转的黄金角步长（弧度）。2π·(1−1/φ) = 2.39996 rad ≈ 137.5°——
// 与参考 goldenRotate 同源；相邻层方位差恒定且永不回卷 ⇒ 各层朝向均匀铺满圆周。
const float WATER_DIRROT_GOLDEN = 2.39996323;
// 该角的旋转矩阵常量（cos/sin 提前算好，避免逐根调用三角函数）。
// 下面用**递推** d ← R(G)·d 取代逐根 sin/cos(angle)：
//   实测（cpp/.tmp/watergunnell 的 fp32 模拟）16 层后与解析值误差 = 0.0
//（黄金角在 fp32 下递推 16 次仍精确），成本 4 mul + 2 add、**零 SFU**。
// 相比 ROUTE B 的 16 项方向常量表：省掉常量内存与索引，代价是多 4 条 FMA 级指令。
const float WATER_DIRROT_COS = -0.73736888;   // cos(2.39996323)
const float WATER_DIRROT_SIN =  0.67549029;   // sin(2.39996323)

vec2 waterDirRotWaveSlopeCapped(vec2 worldXZ, float amplitude, int octaveCap)
{
    // 专用旋钮（语义与退役的 ROUTE B 一致 ⇒ 用户的 JSON 不需要迁移）。
    const float lambdaMax = clamp(cam.waterWaveShapeParams.x, 1.0, 8.0);
    const float drag = clamp(cam.waterWaveShapeParams.y, 0.0, 1.5);
    const int count =
        clamp(int(cam.waterWaveShapeParams.z + 0.5), 1, min(max(octaveCap, 1), 16));
    const float band = clamp(cam.waterWaveShapeParams.w, 2.0, 32.0);

    // 「海浪」一组：浪速 / 波峰锐化（= SteepSine 指数 S，量化成整数走乘法链）。
    const float speedMul = clamp(cam.waterFftParams.w, 0.0, 4.0);
    const int steepInt = clamp(int(clamp(cam.waterFftParams.z, 0.0, 3.0) + 1.5), 1, 4);
    const float waveTime = uintBitsToFloat(cam.padding) * speedMul;

    // 波浪主向：整体旋转采样域（0 度 = 出厂方向）。
    const float dirRad = radians(cam.waterFftParams2.x);
    const float dirCos = cos(dirRad);
    const float dirSin = sin(dirRad);
    const vec2 p = mat2(dirCos, -dirSin, dirSin, dirCos) * worldXZ;

    // 细端几何波长下限（ROUTE B 同款、同理由：RT+DLSS 亚像元高频会闪）。
    const vec2 lodOrigin = cam.waterSimSurfaceAndOrigin.yz;
    const float lodDistance = length(worldXZ - lodOrigin);
    const float minWavelength =
        mix(0.30, 2.0, smoothstep(24.0, 96.0, lodDistance));
    const float kCap = 6.28318531 / minWavelength;

    const float kMin = 6.28318531 / lambdaMax;
    const float log2Band = log2(band);
    const float spectralBeta = 0.8;

    vec2 grad = vec2(0.0);
    vec2 warp = vec2(0.0);
    // 本次移植的核心：逐层方位递推。第 w 根的方位 = w·GOLDEN。
    // 起手 d = (1,0)（= 0 弧度），每轮 d ← R(GOLDEN)·d ⇒ 方位以黄金角累进。
    // 用递推而非逐根 sin/cos(angle)：零 SFU，且 fp32 下 16 步零误差（见上面的常量注释）。
    vec2 dirVec = vec2(1.0, 0.0);

    for(int w = 0; w < count; ++w)
    {
        const vec4 r = waterFbmWaveRandom(uint(w));

        // 波长对数均匀（与 ROUTE B 同式 ⇒ 频段口径不变）。
        const float q = log2Band * r.z;
        const float k = kMin * exp2(q);
        if(k > kCap) continue;

        // ★ 移植点：方位来自黄金角序列的**递推**（= 参考 normalRotation 逐层左乘
        // 固定旋转的等价、无矩阵版）。注意递推在 continue 之后 ⇒ 跳过的层不占位，
        // 方位序列与"实际参与叠加的层号"一致。
        dirVec = vec2(
            WATER_DIRROT_COS * dirVec.x - WATER_DIRROT_SIN * dirVec.y,
            WATER_DIRROT_SIN * dirVec.x + WATER_DIRROT_COS * dirVec.y
        );
        const vec2 dir = dirVec;

        // A ∝ λ^β = 2^(-β·q)（与 ROUTE B 同谱形 ⇒ 能量带一致）。
        const float amp = exp2(-spectralBeta * q);
        // 深水色散 ω = √(g·k)。
        const float omega = sqrt(9.8 * k);

        const float x =
            k * dot(dir, p + warp) + waveTime * omega + r.w * 6.28318531;
        const float u = 0.5 * (sin(x) + 1.0);

        // SteepSine 解析梯度（同 ROUTE B：h = 2A·u^S ⇒ ∂h/∂p = dir·S·k·A·u^(S-1)·cos）。
        float sharp = 1.0;
        if(steepInt >= 2) sharp *= u;
        if(steepInt >= 3) sharp *= u;
        if(steepInt >= 4) sharp *= u;

        const float dH = amp * float(steepInt) * k * sharp * cos(x);
        grad += dir * dH;
        warp += dir * (dH * amp * drag);
    }

    // 标定：与 ROUTE B 同一形式 1/√count、同一增益 0.0621 ⇒ 出厂档下「海浪强度」= 1
    // 给出与 ROUTE B 相同的斜率 RMS（0.120，约 6.8°）——**观感基线不动**，只换结构。
    // 数值复核见 cpp/.tmp/watergunnell/{sundial_gridcrit,dirrot_calib}.py。
    return grad * (amplitude * 0.0621 / sqrt(float(count)));
}
#endif // 2026-10-17 ROUTE C 屏蔽结束（回退：把本 #endif 与上面的 #if 0 一并删掉，
       // 并把 waterNaturalWaveGradientCapped 里的调用换回来）。

// ---------------------------------------------------------------------------
// 2026-10-17 波面后端 **最终定档：waterWaveNormal 完整移植**（用户指令
// `Dev Build 2026-09-16` 的 waterWaveNormal +
// `gerstnerApproxWaveDerivative`（包内无 LICENSE，
// 按仓库对无 LICENSE 参考的既有处理：观感+结构移植，代码自写/逐字转写）。
//
// 参考的三件事全部保留（与 ROUTE C 只借结构相反）：
//   ① 1D 陡波剖面 cos(coordScale·(|x|+c2)^S − coordScale·c1)，x∈[−1,1] 周期，
//      浪尖窄浪谷宽；陡度 S = waveSmoothness 由 STEEPNESS 给；
//   ② **噪声相位扭曲**：x = fract(coord.x·0.5/π + N·3/π)·2−1 —— 波相位被平滑
//      value-noise 场（1 纹素 ≈ 5.2 格，低频非逐像素）推动，波峰线弯折不重复；
//   ③ 逐层 golden 旋转 + ×1.4 递缩 + ×0.6 权重衰减，法线用 normalRotation
//      矩阵左乘链做链式法则（各层朝向互不相同，且带 scale^i 幅度）。
//
// CPU 标定（cpp/.tmp/watergunnell/{sundial_ref_audit,sundial_procnoise_check}.py）：
//   参考出厂档（H=0.9/SCALE=1/LAYERS=5/STEEP=0.4）光学斜率 RMS ≈ 0.046，
//   rep 0.039/0.057（全场最低——比现网 ROUTE C 的 0.12 档 RMS 温和、重复度低
//   一个数量级，噪声扭曲正是用户记得的观感来源）。
//
// 与参考仅两处差异（都有硬理由）：
//   · noisetex → **程序化 value noise**（pcg2d 格点哈希 + smoothstep 插值 +
//     解析梯度）：RT 管线没有 noisetex 绑定，加绑定要动三个消费者的描述符布局
//     （体积光波闪还是无描述符消费者）；统计等价已验证（rms 0.046 vs 0.046，
//     rep 0.015~0.019 vs 0.039~0.057 —— 哈希格点不 64 纹素平铺，反而更低）。
//   · 只移植法线路径（斜率），不移植 waterWave 的 5+5 步 raymarch 视差 ——
//     本项目水面是真实 RT 几何（平面），历来后端都只做光学斜率。
//
// 旋钮映射（**设置入口一个不新增**，全部复用既有滑杆）：
//   海浪强度 waterFftParams.y（0=安全阀不变）→ WATER_WAVE_HEIGHT = 0.9×强度
//     （×0.9 使出厂档=参考出厂 0.9）
//   浪速 waterFftParams.w → WATER_WAVE_SPEED（参考速度 = 2·t·SPEED）
//   海浪风向 waterFftParams2.x → 整体旋转采样域（参考无此项；0°=参考朝向）
//   波数 waterWaveShapeParams.z（1..16）→ WATER_WAVE_NORMAL_LAYERS（参考默认 5）
//   波长 λmax waterWaveShapeParams.x → WATER_WAVE_SCALE = λmax/3.6（出厂=1）
//   波峰锐化 waterFftParams.z（0..3）→ STEEPNESS = z/3（参考默认 0.4）
//   域扭曲 drag waterWaveShapeParams.y → 参考结构无对应项，本后端闲置
//   （LAYER_SCALE 1.4 / LAYER_FADE 0.6 同参考出厂，不设旋钮。）
// ---------------------------------------------------------------------------

// pcg2d 格点哈希：uvec2 → uint，已知好的廉价混洗（乘加+移位，零 SFU）。
float waterSundialHash(ivec2 lattice)
{
    uvec2 v = uvec2(lattice);
    v = v * 1664525u + 1013904223u;
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v ^= v >> 16u;
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v ^= v >> 16u;
    return float(v.x ^ v.y) / 4294967296.0;
}

// 平滑 value noise（noisetex 的替身）：返回 (值, ∂/∂纹素x, ∂/∂纹素y)。
// 插值权重 f²(3−2f) 与参考 textureGather 手写插值同式；解析梯度省掉 4 次额外取样。
vec3 waterSundialValueNoise(ivec2 cell, vec2 f)
{
    const float v00 = waterSundialHash(cell);
    const float v10 = waterSundialHash(cell + ivec2(1, 0));
    const float v01 = waterSundialHash(cell + ivec2(0, 1));
    const float v11 = waterSundialHash(cell + ivec2(1, 1));
    const vec2 wgt = f * f * (3.0 - 2.0 * f);
    const float value = mix(mix(v00, v10, wgt.x), mix(v01, v11, wgt.x), wgt.y);
    const vec2 dw = 6.0 * f * (1.0 - f);
    return vec3(
        value,
        ((v10 - v00) * (1.0 - wgt.y) + (v11 - v01) * wgt.y) * dw.x,
        ((v01 - v00) * (1.0 - wgt.x) + (v11 - v10) * wgt.x) * dw.y
    );
}

// gerstnerApproxWaveDerivative 的逐字转写（常数与运算顺序同参考）：
// 返回该层剖面在 coord 空间的 2D 导数。
vec2 waterSundialProfileDeriv(vec2 coord, float steepness)
{
    const float PI = 3.14159265;
    const float waveSmoothness = 1.0 - 0.7 * steepness;
    const float curveOffset1 = (1.0 - waveSmoothness) / PI;
    const float curveOffset2 = pow(curveOffset1, 1.0 / waveSmoothness);
    const float coordScale = PI / (pow(1.0 + curveOffset2, waveSmoothness) - curveOffset1);

    const vec2 noiseCoord = coord * (0.003 * 64.0) + 0.5;
    const ivec2 noiseCell = ivec2(floor(noiseCoord));
    const vec3 n = waterSundialValueNoise(noiseCell, noiseCoord - vec2(noiseCell));
    // 参考的噪声导数在纹素坐标里算完再 ×(0.003·64·6)；x 项 += 1 是相位自导数
    //（d(coord.x·0.5/π)·2π 折算，与参考 signMul 链一致）。
    vec2 noiseDerv = n.yz * (0.003 * 64.0 * 6.0);
    noiseDerv.x += 1.0;

    const float x =
        fract(coord.x * (0.5 / PI) + n.x * (6.0 * 0.5 / PI)) * 2.0 - 1.0;
    const float power = pow(abs(x) + curveOffset2, waveSmoothness - 1.0);
    return sin(coordScale * power * (abs(x) + curveOffset2) - coordScale * curveOffset1)
        * (x < 0.0 ? -coordScale : coordScale)
        / PI * noiseDerv * waveSmoothness * power;
}

vec2 waterSundialWaveSlopeCapped(vec2 worldXZ, float amplitude, int octaveCap)
{
    const float lambdaMax = clamp(cam.waterWaveShapeParams.x, 1.0, 8.0);
    const int layers =
        clamp(int(cam.waterWaveShapeParams.z + 0.5), 1, min(max(octaveCap, 1), 16));
    const float steepness = clamp(cam.waterFftParams.z * (1.0 / 3.0), 0.0, 1.0);
    const float speedMul = clamp(cam.waterFftParams.w, 0.0, 4.0);
    const float waveTime = uintBitsToFloat(cam.padding) * speedMul;
    const float waveScale = lambdaMax * (1.0 / 3.6);
    const float waveHeight = 0.9 * amplitude;

    // 海浪风向：整体旋转采样域（0 度 = 参考朝向）。
    const float dirRad = radians(cam.waterFftParams2.x);
    const float dirCos = cos(dirRad);
    const float dirSin = sin(dirRad);

    // goldenRotate（参考 Uniform.glsl:124 同常量；列主序：(cosG,sinG)/(−sinG,cosG)）。
    const mat2 goldenRotate =
        mat2(-0.73736888, 0.67549029, -0.67549029, -0.73736888);
    const mat2 layerRotation = mat2(1.4) * mat2(
        goldenRotate[0].x, goldenRotate[1].x,
        goldenRotate[0].y, goldenRotate[1].y);
    mat2 normalRotation = transpose(goldenRotate) * mat2(32.0);

    const float speed = 2.0 * waveTime;
    // 2026-10-17 尺度修复：参考的 coord 是"世界/SCALE 再 ×32"，其物理基波 =
    // 2π·SCALE/32 格 ≈ 0.2 格（移植自带的 CPU 复现 sundial_procnoise_check.py：
    // 420px/24 格里约 120 个波周期，基波 0.196 格）——远低于像素足迹，细波层因此
    // 几乎完全不可见（用户实测：关掉「涌浪强度」后水面直接变镜面）。此处去掉 ×32
    // ⇒ 基波 = 2π·waveScale 格（≈6 格），与文件头 "基波 ≈ 2π·λmax/3.6 格" 的移植
    // 注释一致。注意：终值里的 /32 **不要**动（它与 normalRotation 的 ×32 配对，
    // 与 coord 缩放无关），否则世界斜率会炸到 RMS≈1.8。留存的世界斜率 RMS≈0.056，
    // 与 FFT 涌浪同量级。副作用：noise 格点同步变粗到 ≈5.2·waveScale 格，正合注释口径。
    vec2 coord = mat2(dirCos, -dirSin, dirSin, dirCos) * (worldXZ / waveScale);

    vec2 totalNormal = vec2(0.0);
    float currentWeight = 1.0;
    for(int i = 0; i < layers; ++i)
    {
        coord = goldenRotate * coord;
        totalNormal += normalRotation * (currentWeight *
            waterSundialProfileDeriv(coord - vec2(speed, 0.0), steepness));
        normalRotation *= layerRotation;
        currentWeight *= 0.6;
        coord *= 1.4;
    }
    const float totalWeight = (1.0 - 0.6) / (1.0 - pow(0.6, float(layers)));
    // 参考终值：normalize(vec3(sampleNormal * vec2(0.2*H/(SCALE*32)), 1)) 的斜率部分。
    // 2026-10-17：这里的 /32 与 normalRotation 里 mat2(32.0) 的 ×32 成对抵消（与 coord
    // 的缩放无关），因此第 427 行去掉 coord 的 ×32 后，本行的 /32 必须**保留**——实测
    // 世界空间斜率 RMS ≈ 0.056（24 格）/0.053（96 格），与 FFT 涌浪同量级且不随分辨率变化。
    // 若也去掉此处的 /32，斜率会暴涨到 RMS≈1.8（约 60°，明显过强）。
    return totalNormal * (totalWeight * 0.5) *
        (0.2 * waveHeight / (32.0 * waveScale));
}

// ---------------------------------------------------------------------------
// 2026-10-17 **水面波面法线移植（用户指令"严格按照它的来"）。**
//
// 来源：
//   · Passes/B1_GenWaveFieldX.comp + B2_GenWaveFieldY.comp —— Tessendorf FFT 海洋
//     （谱 → 行 FFT → 列 FFT → 高度/位移场，rg16f）。
//   · Phillips 谱（FFT 波场）：
//       k = m·(250/N)  （GetWaveFieldCoord，**不含 2π**）
//       A = 5500e-9·(1+3·rain)，V = 4+rain，g = 9.80665，L = V²/g
//       P = A/k⁴·exp(−1/(k²L²))·(k̂·ŵ)²，wind = normalize(vec2(1,1))
//       ω = √(g|k|)，h0 = (1/√2)(ξr+iξi)·√P
//   · GetWavesHeight / GetWavesNormal：
//       height = texture(waveTex, fract(worldXZ·0.1)).x · WAVE_HEIGHT_MULT − 1.0
//       normal = normalize(vec3((t−h)·100, (t−v)·100, 1.0))   （delta = 0.01 格）
//
// 本工程落地：宿主 17 趟 FFT 链（water_fft_common.glsl 的谱 + water_fft.comp 的
// 蝶形/finalize）已等价于 B1/B2；谱常量与波数已换成（见
// water_fft_common.glsl）。finalize 用相邻纹素中心差分把高度场烘成斜率图
//（绑定 59，RGBA16F，REPEAT），等价于在片元里对高度场做有限差分；
// 这里按平铺尺度取样。安全阀：cam.waterFftParams.y ≤ 0
//⇒ 静水，且绝不采样未写入的 binding。
// ---------------------------------------------------------------------------
#if WATER_WAVES_FFT_SWELL
// 2026-10-18 远海走样修复（方案 A）：斜率图带 mip 链，按屏幕足迹取级。
// 斜率在**光线追踪阶段**采样，texture() 的隐式 LOD 在 RT 里未定义，故必须显式
// 传 LOD。本函数把"该水面点的每像素世界足迹"换算成纹素倍数并取 log2：
//   projScale   = gl_LaunchSizeEXT.y / (2·tan(fov/2))，由 RT 调用方传（仿 voxyLodMip）；
//                 compute 无 gl_LaunchSizeEXT ⇒ 传 <= 0，退化为 level 0（改动前行为）；
//   rayDistance = 光线命中距离（RT 的 gl_HitTEXT）或相机距离近似；
//   noV         = 视线与竖直方向的夹角余弦（掠射时足迹拉长，取更大的 mip）。
float waterFftSlopeLodFromProj(vec3 surfacePos, float rayDistance, float projScale)
{
    if(projScale <= 0.0)
    {
        return 0.0;
    }
    const float tilePeriod = max(cam.waterFftParams.x, 1.0e-3);
    const float texelWorld = tilePeriod / 256.0;   // 宿主 FFT 分辨率 WATER_FFT_N = 256
    vec3 toCam = surfacePos - cam.viewInverse[3].xyz;
    float dist = max(length(toCam), 1.0e-3);
    float noV = max(abs(toCam.y) / dist, 0.04);
    float worldPerPixel = max(rayDistance, 1.0e-3) / (projScale * noV);
    // 2026-10-19：下界放开到 -8（原来是 0）。基础层采样仍会 clamp 到 0，
    // 但细节层纹素更小（32 格 / 256），它的 LOD = 本值 + log2(基础周期/32)；
    // 若这里先截到 0，近景细节会被错误地多糊 2 级。
    return clamp(log2(max(worldPerPixel / texelWorld, 1.0e-4)), -8.0, 8.0);
}

// horizonFade = HORIZON_WAVE_FADE = 1 − exp(−4·|NoV|)，由调用方按
// 相机→水面的视线与竖直方向的夹角算好传入（NoV = 该夹角的余弦 = |viewDir.y|）。
// waveLod = 上面算好的 mip 级（0 = 最清，8 = 最糊）。
vec2 waterFftSwellSlope(vec2 worldXZ, float horizonFade, float waveLod)
{
 // 采样坐标：fract(worldXZ / 平铺周期)。固定 fract(worldXZ·0.1)（10 格）；
    // 这里取 UBO 的可调周期（格），与宿主 texelWorldSize = period/N 保持一致。
    const float tilePeriod = max(cam.waterFftParams.x, 1.0e-3);
    const vec2 uv = fract(worldXZ / tilePeriod);
    // 显式 LOD：mip 链在宿主侧逐级 box 过滤生成（VulkanCore.cpp 的 blit 链），
    // 远处的欠采样摩尔纹 / 直线状分界由此抹平。
    const vec2 slope = textureLod(waterFftSlope, uv, clamp(waveLod, 0.0, 8.0)).rg;

    // 2026-10-19 细节层（波面细分）：.ba 是小周期（32 格）的高通 FFT 斜率。
    // LOD 换算：两层的 worldPerPixel 相同，detailLod = baseLod + log2(基础纹素/细节纹素)
    // = baseLod + log2(tilePeriod / 32)。mip 链把细节在远处自然过滤到 0 均值
    // （斜率带符号，逐级平均后趋近静水），不需要额外的距离淡出。
    const float detailLod = clamp(
        waveLod + log2(max(tilePeriod / WATER_FFT_DETAIL_TILE, 1.0e-4)),
        0.0,
        8.0
    );
    const vec2 detailUv = fract(worldXZ / WATER_FFT_DETAIL_TILE);
    const vec2 detailSlope =
        textureLod(waterFftSlope, detailUv, detailLod).ba;

 // 掠射角看水面（远海）时把波面法线整体拉平 —— 抑制远处高频波走样
    // 的手段；mip 链再从"采样侧"补一层抗走样，两者叠加。基础层与细节层同乘。
    return (slope + detailSlope) * horizonFade;
}
#endif // WATER_WAVES_FFT_SWELL

// ---------------------------------------------------------------------------
// 自然波面入口：调用点（rchit / rgen）契约不变。实现见下 —— 有描述符（可见水面）
// 走 FFT 涌浪；无描述符（体积光波闪）走解析细波层。
// 旧注释"解析波面已删、返回零斜率"描述的是更早的占位实现，已不适用。
// out 参数 cameraDistance 恒写 0：调用方按 0 的语义工作。
// ---------------------------------------------------------------------------
// 2026-10-06 近水掉帧降本：带八度封顶的变体。无封顶的同名入口是所有既有调用点的
// 行为契约，逐位不变。
// 2026-10-15 退役：本函数原是一条 `#if/#elif` 后端天梯（DIRROT/GUNNELL/FBM/AFL/
//   NOISE/SEASCAPE/FFT）。用户定调"全删，只留 ROUTE C" ⇒ 天梯收成 ROUTE C
//   + FFT 涌浪层；其余分支的源码已从本文件移除（回退靠 git 历史）。
// 2026-10-17：可见水面定档 FFT 涌浪，ROUTE C 整体 `#if 0` 屏蔽；
// 无描述符消费者改走解析细波层（见下面的 #else 分支）。
vec2 waterNaturalWaveGradientCapped(
    vec2 worldXZ,
    out float cameraDistance,
    int octaveCap,
    float horizonFade,
    float waveLod
)
{
    // cameraDistance 保持 0：调用方（rchit 的雨滴涟漪、rgen 的焦散）一直按 0 的
    // 语义工作，这里不改那个行为（距离只在函数内部用局部变量算）。
    cameraDistance = 0.0;

 // 安全阀：y <= 0 = GUI「振幅」 为 0 ⇒ 静水；
    // 同时保证绝不去采样一个未写入的 binding 59。
    const float amplitude = cam.waterFftParams.y;
    if(amplitude <= 0.0)
    {
        return vec2(0.0);
    }

#if WATER_WAVES_FFT_SWELL
 // 2026-10-17 可见水面（有描述符）：**唯一**后端 = 
    // Tessendorf FFT 海洋波面法线（见 waterFftSwellSlope 的移植注释）。
 // 本身没有解析细波层，故不再叠加层。
    return waterFftSwellSlope(worldXZ, horizonFade, waveLod);
#else
 // 无描述符消费者（体积光波闪）拿不到 binding 59：退回解析细波层（
    // 移植），保证水下波闪仍与波面同源。
    return waterSundialWaveSlopeCapped(worldXZ, amplitude, octaveCap);
#endif
}

// 2026-10-15 完整质量路径的八度硬上限。上限 16 让 UBO「波数」旋钮（1..12）不被
// 截断 —— ROUTE C 的 count 直接 clamp 到 min(octaveCap, 16)，用户把「波数」拉满
// 也不会被这条上限吃掉。
const int WATER_WAVE_FULL_OCTAVE_CAP = 16;

vec2 waterNaturalWaveGradient(vec2 worldXZ, out float cameraDistance, float horizonFade, float waveLod)
{
    return waterNaturalWaveGradientCapped(
        worldXZ,
        cameraDistance,
        WATER_WAVE_FULL_OCTAVE_CAP,
        horizonFade,
        waveLod
    );
}

// ===========================================================================
// 波面混合 —— 参考的 HPWaterFluidWorldNormalBlend 是"XZ 相加、Y 相乘"：
//     float2 xz = n1.xz + n2.xz;  float y = n1.y * n2.y;  normalize(xz.x, y, xz.y)
// 这里给出它在**斜率空间**的精确等价（免去来回换法线，只要 2 次 sqrt）：
//   a = sqrt(1+|g1|²)、b = sqrt(1+|g2|²) ⇒ N1=(g1/a, 1/a)、N2=(g2/b, 1/b)
//   blended ∝ (g1/a + g2/b, 1/(ab)) ⇒ slope = g1·b + g2·a  （Y 分量恒为 1）
// 语义：两波叠加处比"直接相加"更陡、更集中（参考的观感）。
// mode=0 ⇒ 逐位旧档（直接相加）。旋钮：cam.waterWaveParams.z。
// 注：自然波面删除后，这里退化为"交互场单独成形"（slope0 恒为 0）。
// ===========================================================================
vec2 waterBlendWaveSlopes(vec2 slope0, vec2 slope1, float mode)
{
    if(mode < 0.001)
    {
        return slope0 + slope1;
    }
    float a = sqrt(1.0 + dot(slope0, slope0));
    float b = sqrt(1.0 + dot(slope1, slope1));
    vec2 blended = slope0 * b + slope1 * a;
    return mix(slope0 + slope1, blended, clamp(mode, 0.0, 1.0));
}

// 波面混合模式的集中读取（消费点不必各自 clamp；0 = 旧档）。
float waterWaveBlendMode()
{
    return clamp(cam.waterWaveParams.z, 0.0, 1.0);
}

// Persistent Eclipse-style interaction field.  The physical water plane is
// unchanged; only its optical normal receives the stored height slope.
//
// `stateAndBorderMask` receives the simulation-active flag times the window
// border fade.  Elevation gating is left to the caller because the closest-hit
// path keys it off the surface it is shading, while the caustic path keys it
// off the water surface the camera ray entered.
vec2 waterInteractionSlope(vec2 worldXZ, out float stateAndBorderMask)
{
    stateAndBorderMask = 0.0;

// 2026-10-15 退役重构：无描述符消费者（体积光波闪，WATER_WAVE_FIELD_NO_DESCRIPTORS）
// 拿不到 binding 33（交互场）⇒ 本函数对它恒返回零斜率。体积光波闪只要解析细波
// 斜率，不消费交互场，所以这条短路是**语义等价**的，不是降级。
#if !WATER_INTERACTION_WAVES || defined(WATER_WAVE_FIELD_NO_DESCRIPTORS)
    // 2026-10-03 交互波场退出水面着色（见文件头 WATER_INTERACTION_WAVES 注释）：
    // 窗口边缘的直线伪影 + 全部旋钮退役 ⇒ 恒返回 0。
    return vec2(0.0);
#else
    float worldTexel = cam.waterSimSurfaceAndOrigin.w;
    if(cam.waterSimState.y == 0u || worldTexel <= 0.0)
    {
        return vec2(0.0);
    }

    vec2 simulationUV =
        (worldXZ - cam.waterSimSurfaceAndOrigin.yz) /
        (worldTexel * 512.0);
    // ★ 2026-10-03 修复"窗口边缘直线"：淡出带从 2.5%（0.8 格）加宽到 12%
    //（≈3.8 格）。原来的窄淡出让斜率在一步内从全场值跳到 0，窗口边缘扫过水面
    // 时就是一条笔直的明暗交界线（没下水也可见，因为风源修复后窗口常开）。
    // 12% 恰好覆盖 sim 侧的海绵吸收带（默认 10%）⇒ 两边同步归零，肉眼无过渡痕。
    vec2 simulationBorder = smoothstep(
        vec2(0.0), vec2(0.12), simulationUV
    ) * (1.0 - smoothstep(
        vec2(0.88), vec2(1.0), simulationUV
    ));
    stateAndBorderMask = simulationBorder.x * simulationBorder.y;
    if(stateAndBorderMask <= 0.0)
    {
        return vec2(0.0);
    }

    float stateLayer = float(cam.waterSimState.x);
    vec2 uvStep = vec2(1.0 / 512.0);
    float heightLeft = texture(
        waterSimulationState,
        vec3(simulationUV - vec2(uvStep.x, 0.0), stateLayer)
    ).r;
    float heightRight = texture(
        waterSimulationState,
        vec3(simulationUV + vec2(uvStep.x, 0.0), stateLayer)
    ).r;
    float heightDown = texture(
        waterSimulationState,
        vec3(simulationUV - vec2(0.0, uvStep.y), stateLayer)
    ).r;
    float heightUp = texture(
        waterSimulationState,
        vec3(simulationUV + vec2(0.0, uvStep.y), stateLayer)
    ).r;

    // The simulation stores height in abstract pressure units.  This
    // conversion affects only the optical slope and was chosen to keep entry
    // rings and movement wakes clearly visible without moving the static BLAS
    // water surface.
    //
    // 2026-10-02 参考重构（HPWater / HPWaterFluidHeightToNormal）：
    //   参考的法线强度是**中心差分的严格标定** 1/(2Δ)（Δ = 每纹素世界尺寸）：
    //       dX = (hLeft - hRight) * rcp(worldSize * 2)
    //   我们是 (hRight - hLeft) * (0.19 / worldTexel) —— 符号约定相反（调用方按
    //   N = normalize(-g.x, 1, -g.y) 组装），系数 0.19 是实机标定值。
    //   x = 0 ⇒ 逐位旧档；x = 1 ⇒ 参考口径（0.5/worldTexel，约 2.6× 更陡）。
    //   y 是额外倍率（GUI「交互波强度」）。
    float calibration = clamp(cam.waterWaveParams.x, 0.0, 1.0);
    float strength = clamp(cam.waterWaveParams.y, 0.0, 2.0);
    float interactionScale = mix(0.19, 0.5, calibration) * strength;
    return vec2(
        heightRight - heightLeft,
        heightUp - heightDown
    ) * (interactionScale / max(worldTexel, 1.0e-4));
#endif
}
