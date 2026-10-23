// ===========================================================================
// 2026-10-03 FFT 频谱海面（Tessendorf / Phillips 谱）—— 公共声明与数学
//
// 为什么这么做：波方程场只能覆盖玩家周围 ±16 格（512²×0.0625），而"水面一直太
// 安静"的主要原因是可见水面大部分在窗口外。FFT 频谱海面是**按世界坐标平铺**的，
// 全距离生效，且完全无状态演化在 GPU 上（谱 → IFFT → 斜率图），着色端只要 1 次
// 取样。
//
// 布局约定（必须与宿主 WaterFftPushConstants 逐字对应）：
//   layout(push_constant) 16 × 4 字节 = 64 字节。
//
// 数学约定：
// · 格点索引 m ∈ [-N/2, N/2)，波数 k = m·(L/N)，L = 250（
//     GetWaveFieldCoord，**不含 2π**）
//   · 深水色散 ω = sqrt(g·|k|)，g = 9.80665
//   · h̃(k,t) = h0(k)·e^{iωt} + conj(h0(-k))·e^{-iωt}（保证时域输出为实数）
//   · h0(k)   = (1/√2)(ξr + iξi)·sqrt(P(k))，ξ ~ N(0,1)（hash 得到的确定性高斯）
// · P(k) = A/k⁴·exp(-1/(k²L²))·(k̂·ŵ)²（Phillips；
//     A = 5500e-9·(1+3·rain)，V = 4+rain，L = V²/g，ŵ = normalize(1,1)）
//   · IFFT 用 radix-2 蝶形，**输入按变换轴位反转**（谱生成趟就按 X 位反转写入，
//     省掉一次 X 位反转 pass；Y 的位反转由 mode=2 一趟完成），输出为自然序。
// · **不做 1/N² 归一化**：finalize 里 visualScale
// 直接充当 WAVE_HEIGHT_MULT。
//
// 性能：N=256 ⇒ 每趟 65536 线程；基础层 19 趟（1 谱 + 8 行 + 1 位反转 + 8 列 + 1
// finalize），默认 30 Hz 更新 ⇒ 全帧摊销约 0.05 ms。
//
// 2026-10-19 波面细分（细节层）：同一条管线、同一张斜率图再跑一条 **细节层**
// 链（1 谱 + 8 行 + 1 位反转 + 8 列，省掉独立 finalize）：
//   · 状态图扩到 4 层：0/1 = 基础层（128 格平铺）乒乓，2/3 = 细节层乒乓；
//   · 细节层用较小的世界平铺周期（32 格）与谱高通过滤（minWaveRadius）⇒
//     只生成 0.25~1 格的亚格小波，比基础层的 1 格下限更细；
//   · 共用一次 finalize：基础斜率写 .rg，细节斜率写 .ba（原来的 .b 高度未使用）。
// 着色端对同一张图的 .rg/.ba 用两套 UV/LOD 各取一次，叠加后仍是一次法线。
// 成本：37 趟调度（基础 19 + 细节 18）≈ 0.1 ms / 全帧（30 Hz），不新增纹理/binding。
// ===========================================================================

#ifndef WATER_FFT_COMMON_GLSL
#define WATER_FFT_COMMON_GLSL

// 频谱分辨率（**必须与宿主 WATER_FFT_RESOLUTION 一致**，改这里要同步改宿主）
// 2026-10-15 重构定档：128 → 256（domain/平铺周期 128 格 ⇒ 纹素 0.5 格、最小波长
// 1 格）。旧 128/N=128 + 16 格周期的组合是当年"水面平/壁纸化"的根因（16 格整图案
// 每 16 格重复；CPU 复现见 cpp/.tmp/watergunnell/fftprev.png 的 d16 面板）。
#define WATER_FFT_N 256u
#define WATER_FFT_LOG2_N 8
#define WATER_FFT_PI 3.14159265358979
#define WATER_FFT_GRAVITY 9.81

// 2026-10-17：波数跨度 L（Tessendorf.glsl GetWaveFieldCoord
// 里的 vec2 L = vec2(250,250)）。波数 k = m·(L/N)，**不含 2π**（谱把
// |k| 直接当波数用，色散 ω=√(g|k|) 亦然）。
#define WATER_FFT_K_SPAN 250.0

// mode：0 = 谱生成（含 X 位反转写入），1 = 蝶形（axis/stageLength），
//       2 = Y 位反转，3 = finalize（高度 → 光学斜率图）
const int WATER_FFT_MODE_SPECTRUM = 0;
const int WATER_FFT_MODE_BUTTERFLY = 1;
const int WATER_FFT_MODE_BITREVERSE_Y = 2;
const int WATER_FFT_MODE_FINALIZE = 3;

layout(push_constant) uniform WaterFftPush
{
    int sourceLayer;       // 读层（image2DArray 的 layer）
    int destinationLayer;  // 写层
    int stageLength;       // 蝶形跨度（2,4,...,N）；mode 1 使用
    int mode;              // 见上面的常量
    int axis;              // 0 = 沿 X（行），1 = 沿 Y（列）；mode 1 使用
    int detailSourceLayer; // mode 3：细节层高度场的读层（基础层 = sourceLayer）
    float timeSeconds;     // 演化时间（秒）：ω·t 的相位
    float windSpeed;       // （2026-10-17 起未用：风固定为 (1,1)）
    float rainStrength;    // 降雨强度（A/V 随雨增大；宿主写 weatherParams.x）
    float spectrumScale;   // （未用：谱振幅 A 内置）
    float visualScale;     // 基础层斜率输出倍率
    float choppiness;      // 波峰锐化（非线性陡化；0 = 纯正弦）
    float texelWorldSize;  // 基础层每纹素的世界尺寸 = 平铺周期 / N
    // 2026-10-19 细节层（亚格级小波，烘进同一张斜率图的 .ba）：
    float detailTexelWorldSize; // 细节层每纹素世界尺寸 = 细节平铺周期 / N
    float detailVisualScale;    // 细节层输出倍率（0 = 细节层关闭）
    float minWaveRadius;        // 谱高通过滤：|m| 小于该值的格点置 0
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

    const vec2 wind = vec2(0.70710678, 0.70710678);   // normalize(vec2(1,1))
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
    // 2026-10-19 细节层高通过滤：只保留 |m| >= minWaveRadius 的格点。
    // 基础层传 0（不过滤）；细节层传 32 ⇒ 波长 <= 细节平铺周期/32 = 1 格，
    // 与基础层（最小 ~1 格）几乎不重叠，叠上去是纯粹的亚格级细波。
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

#endif // WATER_FFT_COMMON_GLSL
