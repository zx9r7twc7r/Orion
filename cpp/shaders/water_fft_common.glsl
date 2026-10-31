
#ifndef WATER_FFT_COMMON_GLSL
#define WATER_FFT_COMMON_GLSL

#define WATER_FFT_N 256u
#define WATER_FFT_LOG2_N 8
#define WATER_FFT_PI 3.14159265358979
#define WATER_FFT_GRAVITY 9.81

#define WATER_FFT_K_SPAN 250.0

const int WATER_FFT_MODE_SPECTRUM = 0;
const int WATER_FFT_MODE_BUTTERFLY = 1;
const int WATER_FFT_MODE_BITREVERSE_Y = 2;
const int WATER_FFT_MODE_FINALIZE = 3;

layout(push_constant) uniform WaterFftPush
{
    int sourceLayer;
    int destinationLayer;
    int stageLength;
    int mode;
    int axis;
    int detailSourceLayer;
    float timeSeconds;
    float windSpeed;
    float rainStrength;
    float spectrumScale;
    float visualScale;
    float choppiness;
    float texelWorldSize;
    float detailTexelWorldSize;
    float detailVisualScale;
    float minWaveRadius;
} fftPush;
// 8 位位反转（LOG2_N 固定为 8；N 改动时这里要同步）
uint waterFftBitReverse(uint value)
{
    uint result = 0u;
    for(int bit = 0; bit < WATER_FFT_LOG2_N; ++bit)
    {
        result = (result << 1u) | (value & 1u);
        value >>= 1u;
    }
    return result;
}

vec2 waterFftComplexMul(vec2 a, vec2 b)
{
    return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

// 存储索引 → 格点索引（有符号波数索引 m ∈ [-N/2, N/2)）
//
// ★ 2026-10-03 修正（"水面还是平的"的两个真因之一）：这里**必须是环绕**（wrap），
// 而**不是**简单的 "索引 - N/2"。原因：N/2 平移只等于给空间场乘一个 (-1)^(x+z)
// 的棋盘相位因子，于是每一个设计好的长波都被搬到 Nyquist 附近 ⇒ 输出退化成噪点。
// 用 python/numpy 复刻整条链实测：旧的 "减 N/2" 写法输出谱重心 |fx|=53/64（Nyquist），
// 改成环绕后重心 11/64（长波）。同一份数值实验也顺便证明了位反转 + 蝶形链与直接
// 求和 DFT 逐位一致（相对误差 ~1e-15）。
vec2 waterFftGridIndex(uvec2 store)
{
    const int n = int(WATER_FFT_N);
    int x = int(store.x);
    int z = int(store.y);
    x = (x < (n >> 1)) ? x : x - n;
    z = (z < (n >> 1)) ? z : z - n;
    return vec2(float(x), float(z));
}

// 格点索引 → 物理波数。Tessendorf.glsl 的 GetWaveFieldCoord：k = m·(L/N)，
// L=250、N=1024、**不含 2π**。⚠ 波数步长固定为 L/1024，
// **与本站 FFT 分辨率 WATER_FFT_N 无关**：若误用 K_SPAN/WATER_FFT_N(=256) 步长会粗 4×，
// 丢掉承载主要能量的低频波（k≈0.43 的 ~5 格涌浪），水面会近乎全平。
// 再按 (10/period) 缩放：宿主把世界平铺周期 P 经 texelWorldSize = P/N 传进来
//（⇒ P = texelWorldSize·N），固定 10 格；缩放后 k = κ·(2500/(1024·2π))
// 与 P 无关，物理谱一致 —— 改平铺周期只改世界域大小/重复感，不改波尺寸。
vec2 waterFftWaveVector(vec2 gridIndex)
{
    const float tilePeriod = max(fftPush.texelWorldSize * float(WATER_FFT_N), 1.0e-3);
    return gridIndex * (WATER_FFT_K_SPAN / 1024.0) * (10.0 / tilePeriod);
}

// 确定性高斯对（谱的实虚部）：Box-Muller，按格点索引稳定 ⇒ 每帧同一组随机相位
vec2 waterFftGaussianPair(vec2 gridIndex)
{
    float unit1 = fract(sin(dot(gridIndex, vec2(12.9898, 78.233))) * 43758.5453);
    float unit2 = fract(sin(dot(gridIndex, vec2(39.3468, 11.1351))) * 24634.6345);
    float radius = sqrt(max(-2.0 * log(max(unit1, 1.0e-6)), 0.0));
    float angle = 2.0 * WATER_FFT_PI * unit2;
    return vec2(radius * cos(angle), radius * sin(angle));
}

// Phillips 谱：返回 h0(k)（复数振幅）。
// **2026-10-17 **：严格照 Tessendorf.glsl
//   ComputePhilipsSpectrum：A = 5500e-9·(1+3·rain)，V = 4+rain，g = 9.80665，
//   L = V²/g，wind = normalize(vec2(1,1))，P = A/k⁴·exp(−1/(k²L²))·(k̂·ŵ)²。
//   ComputeInitialWaveAmplitudes：h0 = (1/√2)(ξr + iξi)·√P。
// gauss 由调用方传入 —— 对 ±k 复用同一组 rand（ComputeWaveAmplitudes）。
vec2 waterFftPhillips(vec2 gridIndex, vec2 gauss)
{
    vec2 waveVector = waterFftWaveVector(gridIndex);
    float amplitude = length(waveVector);
    if(amplitude < 1.0e-6)
    {
        return vec2(0.0);
    }

    const vec2 wind = vec2(0.70710678, 0.70710678);
    const float gravity = 9.80665;
    float spectrumA = 5500.0e-9 * (1.0 + 3.0 * fftPush.rainStrength);
    float windVelocity = 4.0 + fftPush.rainStrength;
    float largestWave = (windVelocity * windVelocity) / gravity;
    float alignment = dot(waveVector / amplitude, wind);
    float spectrum = spectrumA /
        (amplitude * amplitude * amplitude * amplitude) *
        exp(-1.0 / ((amplitude * largestWave) * (amplitude * largestWave))) *
        (alignment * alignment);

    return inversesqrt(2.0) * gauss * sqrt(max(spectrum, 0.0));
}

// 演化后的频谱 h̃(k,t)（第 1 趟写入的就是它）：
//   h̃ = h0(k)·e^{iωt} + conj(h0(−k))·e^{−iωt}，ω = √(g|k|)。
// ComputeWaveAmplitudes：±k 复用同一组 gauss。
vec2 waterFftEvolvedSpectrum(vec2 gridIndex)
{
    if(length(gridIndex) < fftPush.minWaveRadius)
    {
        return vec2(0.0);
    }

    vec2 waveVector = waterFftWaveVector(gridIndex);
    float omega = sqrt(9.80665 * length(waveVector));

    vec2 gauss = waterFftGaussianPair(gridIndex);
    vec2 positive = waterFftPhillips(gridIndex, gauss);
    vec2 negative = waterFftPhillips(-gridIndex, gauss);

    float cosine = cos(omega * fftPush.timeSeconds);
    float sine = sin(omega * fftPush.timeSeconds);

    vec2 forwardPhase = vec2(cosine, sine);
    vec2 backwardPhase = vec2(cosine, -sine);
    vec2 negativeConjugate = vec2(negative.x, -negative.y);

    return waterFftComplexMul(positive, forwardPhase) +
           waterFftComplexMul(negativeConjugate, backwardPhase);
}

#endif
