#ifndef BRUNETON_ATMOSPHERE
#define BRUNETON_ATMOSPHERE

#include "definitions.glsl"

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

const AtmosphereParameters ATMOSPHERE = AtmosphereParameters(
    IrradianceSpectrum(1.474, 1.8504, 1.91198),
    Angle(0.004675),
    Length(6360.0),
    Length(6420.0),
    DensityProfile(DensityProfileLayer[2](
        DensityProfileLayer(0.0, 0.0, 0.0, 0.0, 0.0),
        DensityProfileLayer(0.0, 1.0, -0.125, 0.0, 0.0))),
    ScatteringSpectrum(0.006962, 0.016270, 0.044685),
    DensityProfile(DensityProfileLayer[2](
        DensityProfileLayer(0.0, 0.0, 0.0, 0.0, 0.0),
        DensityProfileLayer(0.0, 1.0, -0.833333, 0.0, 0.0))),
    ScatteringSpectrum(0.003996, 0.003996, 0.003996),
    ScatteringSpectrum(0.00444, 0.00444, 0.00444),
    Number(0.8),
    DensityProfile(DensityProfileLayer[2](
        DensityProfileLayer(25.0, 0.0, 0.0, 1.0 / 15.0, -2.0 / 3.0),
        DensityProfileLayer(0.0, 0.0, 0.0, -1.0 / 15.0, 8.0 / 3.0))),
    ScatteringSpectrum(0.00065, 0.001881, 0.000085),
    DimensionlessSpectrum(0.1),
    Number(-0.5)
);

const vec3 SUN_SPECTRAL_RADIANCE_TO_LUMINANCE =
    vec3(98242.786222, 69954.398112, 66475.012354);
const vec3 SKY_SPECTRAL_RADIANCE_TO_LUMINANCE =
    vec3(114974.916437, 71305.954816, 65310.548555);

#endif
