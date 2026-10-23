// sky_chapman_common.glsl
// ---------------------------------------------------------------------------
// 逐样本「大气斜程高度增益」。
//
// 为什么单独一个头、而不是塞进 sky_sun_common.glsl：那条头被 6 个模块 include
// （rgen / ser.rgen / rmiss / volumetric_clouds / volumetric_light / sky_atmosphere），
// 而 glslang 会把**未被引用**的全局常量也编进模块 —— 实测只要往那条头里加东西，
// 六个 spv 的 md5 全部改变（即使只有一个模块真的调用）。本头只被
// volumetric_clouds.comp include ⇒ 改这里**只**需要重编那一个 spv。
//
// 背景：云的直射光色此前只有**每像素**那一份 —— directLight 里的 sunColor() 是
// 以**地面**为参考的 Kasten 平面平行空气质量。但云在 2~4 km 上，阳光到达它
// **没有**穿过云底以下那一截大气 ⇒ 高空云在低太阳时该比地面暗得晚、且更少红化。
// 本函数给出比值 k ≥ 1：
//     k(alt, μ) = 云高度斜程透过率 ÷ 地面高度斜程透过率
// 与已有的「光路红化」（sunRedEnd / sunPathRedden，都以地面/每像素为参考）正交，
// 乘在同一个 accumulator 上，可各自单独关。
//
// 为什么用 Chapman 而不是 Kasten：Kasten 的 1/(h+0.135) 在 μ→0 时发散（工程里
// 24 是硬钳）；真实斜程受球面曲率约束，掠射时收敛到 √(πR/2H)（Rayleigh H=8 km
// ⇒ ≈35）。Python 数值积分真值（R=6371 km，单指数）：
//     μ=1 → 1.00   μ=0.2 → 4.86   μ=0.1 → 9.06
//     μ=0.05 → 15.1   μ=0.01 → 28.2   μ=0 → 35.1
// 关键实测性质：该因子对观测高度几乎无关（h 从 0 到 8 km，μ=0.1 处变化 <0.02%）
// ⇒ **高度效应全部落在下面的「避免柱」上**，斜程因子只是 μ 的函数。
//
// 实现：ch(μ) = inversesqrt(μ² + ε)，ε = 2H/(πR) 逐分量取各自的地平上界 ⇒
// 掠射端逐位等于真值上界，μ>0.05（太阳仰角 >3°）内误差 <6%，全程平滑无拐点；
// 实测最大相对误差 12%（仅 μ<0.01，太阳贴地平、直射已近 0 的区间）。
//
// 臭氧刻意不进「避免柱」：臭氧片在 15~35 km，云（<10 km）全在它**下方**，
// 一列也没躲开 ⇒ 对高度增益贡献 ~0（其消光仍留在 sunColor 的垂直光深里）。
//
// 消费方：volumetric_clouds.comp（逐样本）。置 SKY_CHAPMAN_ALTITUDE 0 即整段不
// 参与编译（回退档）；旋钮为 0 时调用方也不调用本函数 ⇒ 逐位旧档。
// ---------------------------------------------------------------------------
#ifndef SKY_CHAPMAN_COMMON_GLSL
#define SKY_CHAPMAN_COMMON_GLSL

#ifndef SKY_CHAPMAN_ALTITUDE
#define SKY_CHAPMAN_ALTITUDE 1
#endif
#if SKY_CHAPMAN_ALTITUDE

// Rayleigh 侧的垂直柱光深 β·H（与 sky_sun_common.glsl 的 SKY_SUN_OPTICAL_DEPTH
// 分解同源；臭氧项刻意不含，见上）。Mie 三通道同值。
const vec3 SKY_CHAPMAN_RAYLEIGH_COLUMN = vec3(0.0440, 0.1040, 0.1792);
const float SKY_CHAPMAN_MIE_COLUMN = 0.0216;
const float SKY_CHAPMAN_RAYLEIGH_H = 8000.0;
const float SKY_CHAPMAN_MIE_H = 1200.0;
// ε = 2H/(πR)，R = 6371 km：
//   Rayleigh 2·8000/(π·6371000) = 7.994e-4 ⇒ 掠射上界 √(1/ε) ≈ 35.4（真值 35.1）
//   Mie      2·1200/(π·6371000) = 1.199e-4 ⇒ 掠射上界 ≈ 91.3
const vec3 SKY_CHAPMAN_EPS_RAYLEIGH = vec3(7.994e-4);
const float SKY_CHAPMAN_EPS_MIE = 1.199e-4;

// altitude 为世界高度（格，海平面 = 0；调用方减 63 之后再传），mu = 光方向 y（>0）。
// 返回逐通道增益，altitude = 0 处逐位 = vec3(1.0)。
vec3 skyChapmanAltitudeGain(float altitude, float mu)
{
    float h = max(altitude, 0.0);
    float mu2 = max(mu, 0.0);
    mu2 *= mu2;
    vec3 rayAvoided = SKY_CHAPMAN_RAYLEIGH_COLUMN
        * (1.0 - exp2(-1.44269504089 * h / SKY_CHAPMAN_RAYLEIGH_H))
        * inversesqrt(vec3(mu2) + SKY_CHAPMAN_EPS_RAYLEIGH);
    float mieAvoided = SKY_CHAPMAN_MIE_COLUMN
        * (1.0 - exp2(-1.44269504089 * h / SKY_CHAPMAN_MIE_H))
        * inversesqrt(mu2 + SKY_CHAPMAN_EPS_MIE);
    return exp2(1.44269504089 * (rayAvoided + vec3(mieAvoided)));
}

#endif // SKY_CHAPMAN_ALTITUDE

#endif // SKY_CHAPMAN_COMMON_GLSL
