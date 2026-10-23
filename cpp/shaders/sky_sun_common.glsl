// sky_sun_common.glsl
// ---------------------------------------------------------------------------
// 阳光颜色单一真源 —— 借鉴自 / 光影包的思路层公式
// （闭式透射阳光色 + 臭氧吸收折进消光系数），全部为公式层重写，未引入任何
// 外包代码。
//
// 物理式：sunColor = exp(-od * (airmass(elev) - 1))
//   * 天顶锚定（airmass - 1）：本工程太阳最高 0.819，此时 base ≈ (0.996,
//     0.992, 0.990)，正午能量标定（直射 1.2208 等）完全不受影响；
//   * 日落变红由 airmass 增长自动给出，与 rt_test.rgen 里 aerial perspective
//     （Atmosphere A1）同一条 Kasten 近似 1/(h+0.135) ⇒ 直射阳光与天空模型
//     永远自洽（这是借鉴的核心收益：消灭"手调渐变与天空模型漂移"）；
//   * 臭氧：吸收截面 (2.1, 5.0, 0.2)e-6/m（Thalman 实测，文献值）折进消光，
//     8 km Rayleigh 标高下 = (0.0168, 0.0400, 0.0016) 光学深度。绿光被吸收
//     ⇒ 地平线阳光更红、暮色更饱和；天顶处影响可忽略。
//
// 消费方（5 处；暮色调色板 / 天气调制逻辑留在各文件不动）：
//   rt_test.rgen            getTimeDrivenSkyLightColor()
//   rt_test.rmiss           tyndallReflectionSunColor()
//   volumetric_clouds.comp  sunColor()
//   volumetric_light.comp   sunColor()
//   sky_atmosphere.comp     sunColor()
// 回退：任一消费处把 SKY_SUN_TRANSMITTANCE_COLOR 置 0，即逐字节回到旧
// 手调渐变路径（#else 分支保留原文）。
// ---------------------------------------------------------------------------
#ifndef SKY_SUN_COMMON_GLSL
#define SKY_SUN_COMMON_GLSL

// 1 = 物理闭式透射阳光色；0 = 旧手调渐变（回退档）。
#ifndef SKY_SUN_TRANSMITTANCE_COLOR
#define SKY_SUN_TRANSMITTANCE_COLOR 1
#endif

#if SKY_SUN_TRANSMITTANCE_COLOR

// 垂直柱光学深度 = Rayleigh(β·H) + Mie(β·H) + 臭氧：
//   R: 5.5e-6*8000  + 18e-6*1200 + 2.1e-6*8000 = 0.0440 + 0.0216 + 0.0168
//   G: 13.0e-6*8000 + 18e-6*1200 + 5.0e-6*8000 = 0.1040 + 0.0216 + 0.0400
//   B: 22.4e-6*8000 + 18e-6*1200 + 0.2e-6*8000 = 0.1792 + 0.0216 + 0.0016
const vec3 SKY_SUN_OPTICAL_DEPTH = vec3(0.0824, 0.1656, 0.2024);

// 与 rt_test.rgen 的 atmosphereAirMass() 同式（Kasten 近似）。两处必须同步，
// 改一处必须改另一处。
float skySunAirMass(float elevation)
{
    float h = clamp(elevation, -0.065, 1.0);
    return min(24.0, 1.0 / max(0.045, h + 0.135));
}

// 物理基色：elevation 传太阳（或夜间月亮）方向的 y 分量即可。
// 天顶锚定（airmass - 1）⇒ 正午 ≈ vec3(1.0)。
vec3 skySunPhysicalBaseColor(float elevation)
{
    float mass = skySunAirMass(elevation);
    return exp2(-1.44269504089 * SKY_SUN_OPTICAL_DEPTH * (mass - 1.0));
}

#endif // SKY_SUN_TRANSMITTANCE_COLOR

// ---------------------------------------------------------------------------
// 八相日循环 / 日照金山（SKY_DAY_PHASE，默认 1；置 0 即逐字节回到上一档）。
//
// 一天八相全部是太阳高度角的连续函数，相邻相之间用 smoothstep 交接，无任何
// 硬切：夜晚 → 拂晓 → 黎明 → 早晨 → 中午 → 下午 → 日落 → 傍晚 → 夜晚。
//   夜晚        e < -0.22   冷月蓝（沿用各消费处既有夜色）
//   拂晓/傍晚   -0.22..0    夜蓝向暮色调色板连续过渡（rgen 拂晓分支）
//   黎明/日落   -0.04..0.22 黄金时刻：金色高饱和（日照金山），叠加暮色调色板
//   早晨/下午   0.06..0.45  物理透射基色的自然暖白渐变（能量随高度角上升）
//   中午        e > 0.45    中性白（天顶锚定 ≈ vec3(1.0)，正午标定不动）
// 全部为闭式标量数学（smoothstep/mix/exp），在既有颜色函数内每像素求值一次，
// 零额外 trace、零查表、零采样 ⇒ 对帧率无影响。
// ---------------------------------------------------------------------------
#ifndef SKY_DAY_PHASE
#define SKY_DAY_PHASE 1
#endif
#if SKY_DAY_PHASE

// 黄金时刻权重：黎明/日落两相（太阳贴地平的低角度段）达到峰值，升入
// 早晨/下午后平滑退场。与暮色调色板同带衔接。
float skySunGoldenHourWeight(float elevation)
{
    return smoothstep(-0.035, 0.005, elevation)
        * (1.0 - smoothstep(0.06, 0.22, elevation));
}

// 日照金山：物理透射基色在低角度偏"褐玫瑰"，金山时刻的受光应是高饱和
// 金色（红顶蓝底）。做法 = 亮度锚定的饱和度拉伸 + 金色相偏移，负值钳零
// （黄昏调色板蓝通道被拉伸后可能过零）。输出仍进各消费处的 rain/thunder
// 调制，恶劣天气自动压回灰色。
vec3 skySunGoldenTint(vec3 baseColor, float elevation)
{
    float w = skySunGoldenHourWeight(elevation);
    if(w <= 0.0)
    {
        return baseColor;
    }
    float luma = dot(baseColor, vec3(0.2126, 0.7152, 0.0722));
    // 2026-09-30 用户定档"纯橘偏红"：旧目标 (1.30,0.90,0.55) G 通道 0.90
    // 是"金黄"观感的来源（色相 ~28°），拉到 (1.32,0.62,0.30)（色相 ~21°，
    // 橘红）；饱和度拉伸 0.9→1.0 补回低角度物理基色偏褐的饱和度。
    // 此处是五处消费点（rgen/rmiss/clouds/light/sky_atmosphere）的单一真源，
    // 改一处全树生效。
    vec3 saturated = max(mix(vec3(luma), baseColor, 1.0 + 1.0 * w), vec3(0.0));
    return saturated * mix(vec3(1.0), vec3(1.32, 0.62, 0.30), w);
}

#endif // SKY_DAY_PHASE

#endif // SKY_SUN_COMMON_GLSL
