#ifndef SKY_SUN_COMMON_GLSL
#define SKY_SUN_COMMON_GLSL

#ifndef SKY_SUN_TRANSMITTANCE_COLOR
#define SKY_SUN_TRANSMITTANCE_COLOR 1
#endif

#if SKY_SUN_TRANSMITTANCE_COLOR

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

#endif
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
    vec3 saturated = max(mix(vec3(luma), baseColor, 1.0 + 1.0 * w), vec3(0.0));
    return saturated * mix(vec3(1.0), vec3(1.32, 0.62, 0.30), w);
}

#endif
#endif
