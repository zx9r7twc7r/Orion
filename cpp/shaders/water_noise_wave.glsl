// ===========================================================================
// water_noise_wave.glsl —— 水面噪声波场（2026-09-30 去重复轮）
//
// 为什么有这个文件：旧水面 = 纯 Gerstner 平面波叠加，每条波带每 λ 格精确
// 重复一次，域扭曲只能挪波峰位置、改不了"同一朵浪花平铺"（用户三轮截图
// 追打的就是这个）。噪声波在空间上**非周期**——每个波峰形状都不同，
// 是唯一在所有尺度上根治重复的办法。
//
// 结构：三个倍频程的 psrdnoise（见 water_psrdnoise.glsl，MIT，stegu）：
//   域旋转（每档不同角度，倍频程之间永不重对齐）
//   × 平流（沿各自方向、按深水色散 √k 配速，波峰速度 ~2-4 格/s）
//   × 梯度旋转（psrdnoise 的 alpha 参数 = Perlin/Neyret flow-noise：
//     波峰在时间上缓慢变形而非纯平移，进一步去重复）
// 倍频程时间倍率全部低于 water_wave_field.glsl 记载的 167× 时间混叠界。
//
// 焦散曲率预算（broken 段注释的约定）：Σk²·A = 0.75²·0.042 + 1.7²·0.017 +
// 3.6²·0.005 ≈ 0.138（2026-09-30 晚：用户反馈"太碎"，频谱往粗挪 ——
// 大涌 0.030→0.042、主导 0.022→0.017、最细 0.011→0.005），连同保留的
// 两条耳语级 Gerstner 长波合计 ~0.15，F' ≈ 1.15。想抬幅值先算 k²·A。
//
// 依赖：waveTime 由调用方传入（三处消费点各自已乘「波速」滑杆）；
// 倍频程幅值倍率读 cam.waterStyleParams1.yzw（「大涌起伏/细波纹理/碎浪
// 颗粒」三根滑杆，0-2，出厂 1.0）—— 因此 include 必须在 cam 声明之后
// （与 water_wave_field.glsl 同一约定），消费者 CameraProperties 块须声明
// 到 waterStyleParams1。binding 零新增。
// ===========================================================================

#include "water_psrdnoise.glsl"

// 单个噪声波倍频程对水面斜坡 (∂h/∂x, ∂h/∂z) 的贡献。
// octave: 0 = λ≈8.4 格（大涌的形状变化层）、1 = λ≈3.7 格（主导细波）、
//         2 = λ≈1.75 格（近场碎浪）。
vec2 waterNoiseOctaveSlope(vec2 worldXZ, float waveTime, int octave)
{
    // 域旋转矩阵（每档差 ~120°，倍频程之间永不重对齐）
    vec2 domainX;
    vec2 domainY;
    float k;
    float amplitude;
    vec2 drift;
    float driftPhase;
    float swirl;
    if(octave == 0)
    {
        k = 0.75;
        // 「大涌起伏」滑杆（waterStyleParams1.y，0-2，出厂 1.0）
        amplitude = 0.042 * clamp(cam.waterStyleParams1.y, 0.0, 2.0);
        domainX = vec2(0.8775826, 0.4794255);   // rot(0.5)
        domainY = vec2(-0.4794255, 0.8775826);
        drift = vec2(0.9701425, 0.2425356);
        driftPhase = 22.0;
        swirl = 2.7;
    }
    else if(octave == 1)
    {
        k = 1.7;
        // 「细波纹理」滑杆（waterStyleParams1.z）
        amplitude = 0.017 * clamp(cam.waterStyleParams1.z, 0.0, 2.0);
        domainX = vec2(-0.5048461, 0.8632094);  // rot(2.1)
        domainY = vec2(-0.8632094, -0.5048461);
        drift = vec2(-0.3713907, -0.9284767);
        driftPhase = 50.0;
        swirl = -3.6;
    }
    else
    {
        k = 3.6;
        // 「碎浪颗粒」滑杆（waterStyleParams1.w）
        amplitude = 0.005 * clamp(cam.waterStyleParams1.w, 0.0, 2.0);
        domainX = vec2(0.9315684, -0.3635721);  // rot(4.2)
        domainY = vec2(0.3635721, 0.9315684);
        drift = vec2(0.7071068, -0.7071068);
        driftPhase = 105.0;
        swirl = 4.5;
    }

    // 旋转域 × 波数，再沿 drift 平流（深水色散 √k 折算进 driftPhase）
    vec2 samplePoint =
        vec2(dot(worldXZ, domainX), dot(worldXZ, domainY)) * k +
        drift * (waveTime * driftPhase);

    vec2 rawGradient;
    // period = 0 ⇒ 非周期模式（既不重复也更快）；swirl = 梯度慢旋转
    psrdnoise(samplePoint, vec2(0.0), waveTime * swirl, rawGradient);

    // 链式法则：h = A·n(k·R·p + t)，∂h/∂p = A·k·Rᵀ·∇n
    return amplitude * k *
        vec2(dot(domainX, rawGradient), dot(domainY, rawGradient));
}

// 三倍频程之和 = 噪声波场的完整斜坡贡献。
vec2 waterNoiseWaveGradient(vec2 worldXZ, float waveTime)
{
    return waterNoiseOctaveSlope(worldXZ, waveTime, 0) +
        waterNoiseOctaveSlope(worldXZ, waveTime, 1) +
        waterNoiseOctaveSlope(worldXZ, waveTime, 2);
}
