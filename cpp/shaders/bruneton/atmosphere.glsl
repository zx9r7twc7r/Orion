// ===========================================================================
// atmosphere.glsl —— 本工程的大气参数（takram DEFAULT，km 单位）+ LUT 尺寸
// 常量 + 光度换算。数值与 takram AtmosphereParameters.DEFAULT 逐项一致
// （ meters × METER_TO_LENGTH_UNIT(1/1000) = km）。
// 必须在 bruneton/{common,precompute,runtime}.glsl 之前 include
// （LUT 尺寸常量被它们引用）。
// ===========================================================================
#ifndef BRUNETON_ATMOSPHERE
#define BRUNETON_ATMOSPHERE

#include "definitions.glsl"

// LUT 尺寸（与 C++ 侧纹理创建严格一致）
const int TRANSMITTANCE_TEXTURE_WIDTH = 256;
const int TRANSMITTANCE_TEXTURE_HEIGHT = 64;
const int SCATTERING_TEXTURE_R_SIZE = 32;
const int SCATTERING_TEXTURE_MU_SIZE = 128;
const int SCATTERING_TEXTURE_MU_S_SIZE = 32;
const int SCATTERING_TEXTURE_NU_SIZE = 8;
const int SCATTERING_TEXTURE_WIDTH =
    SCATTERING_TEXTURE_NU_SIZE * SCATTERING_TEXTURE_MU_S_SIZE;
const int SCATTERING_TEXTURE_HEIGHT = SCATTERING_TEXTURE_MU_SIZE;
const int SCATTERING_TEXTURE_DEPTH = SCATTERING_TEXTURE_R_SIZE;
const int IRRADIANCE_TEXTURE_WIDTH = 64;
const int IRRADIANCE_TEXTURE_HEIGHT = 16;

// 大气参数（takram DEFAULT；km 单位）。
// 这些数值决定天色的物理正确性 —— 日出/日落的红、正午的蓝、暮色全都
// 由它们 + 多次散射推导，不再有手调暮色盘。想调天色：改这里的参数
// （例如 rayleigh_scattering 抬高 = 更蓝的天），重跑预计算。
const AtmosphereParameters ATMOSPHERE = AtmosphereParameters(
    // solar_irradiance (W/m²/nm @ 680/550/440nm)
    IrradianceSpectrum(1.474, 1.8504, 1.91198),
    // sun_angular_radius (rad)
    Angle(0.004675),
    // bottom_radius (km) —— 与旧穹顶 EARTH_RADIUS 一致
    Length(6360.0),
    // top_radius (km) —— 60km 大气厚
    Length(6420.0),
    // rayleigh_density: 两层剖面（指数 1/(-0.125) = 8km 标高）
    DensityProfile(DensityProfileLayer[2](
        DensityProfileLayer(0.0, 0.0, 0.0, 0.0, 0.0),
        DensityProfileLayer(0.0, 1.0, -0.125, 0.0, 0.0))),
    // rayleigh_scattering (/km)
    // 2026-10-03 蔚蓝档（用户：「白天的大气散射有点太白了，想偏蔚蓝点」）：
    // takram DEFAULT (0.005802, 0.013558, 0.0331) 均匀 ×1.2，蓝通道再额外
    // ×1.125（净 ×1.35）。均匀分量抬瑞利消光（正午透射略暖、日出更红，物理
    // 后果），蓝通道额外量负责色相向蔚蓝偏移。天顶亮度升约 ×1.224，由
    // sky_atmosphere.comp 的 SKY_ATM_EXPOSURE 反比补偿（0.0004 → 0.00033），
    // 亮度档位不变、色相变蓝。米氏白雾的削减不在本文件（见 sky_atmosphere
    // .comp 七修：日冕滑杆接线，可运行时 A/B，不用反复重跑预计算）。
    ScatteringSpectrum(0.006962, 0.016270, 0.044685),
    // mie_density: 1.2km 标高
    DensityProfile(DensityProfileLayer[2](
        DensityProfileLayer(0.0, 0.0, 0.0, 0.0, 0.0),
        DensityProfileLayer(0.0, 1.0, -0.833333, 0.0, 0.0))),
    // mie_scattering (/km)
    ScatteringSpectrum(0.003996, 0.003996, 0.003996),
    // mie_extinction (/km)
    ScatteringSpectrum(0.00444, 0.00444, 0.00444),
    // mie_phase_function_g
    Number(0.8),
    // absorption_density (ozone): 25km 处峰值、15km 尺度的梯形剖面
    DensityProfile(DensityProfileLayer[2](
        DensityProfileLayer(25.0, 0.0, 0.0, 1.0 / 15.0, -2.0 / 3.0),
        DensityProfileLayer(0.0, 0.0, 0.0, -1.0 / 15.0, 8.0 / 3.0))),
    // absorption_extinction (/km)
    ScatteringSpectrum(0.00065, 0.001881, 0.000085),
    // ground_albedo
    DimensionlessSpectrum(0.1),
    // mu_s_min = cos(120°)
    Number(-0.5)
);

// 光度换算（takram AtmosphereParameters 太阳/天空辐射度→亮度系数）
const vec3 SUN_SPECTRAL_RADIANCE_TO_LUMINANCE =
    vec3(98242.786222, 69954.398112, 66475.012354);
const vec3 SKY_SPECTRAL_RADIANCE_TO_LUMINANCE =
    vec3(114974.916437, 71305.954816, 65310.548555);

#endif // BRUNETON_ATMOSPHERE
