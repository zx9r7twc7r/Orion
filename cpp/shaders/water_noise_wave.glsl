
#include "water_psrdnoise.glsl"

// 单个噪声波倍频程对水面斜坡 (∂h/∂x, ∂h/∂z) 的贡献。
// octave: 0 = λ≈8.4 格（大涌的形状变化层）、1 = λ≈3.7 格（主导细波）、
//         2 = λ≈1.75 格（近场碎浪）。
vec2 waterNoiseOctaveSlope(vec2 worldXZ, float waveTime, int octave)
{
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
        amplitude = 0.042 * clamp(cam.waterStyleParams1.y, 0.0, 2.0);
        domainX = vec2(0.8775826, 0.4794255);
        domainY = vec2(-0.4794255, 0.8775826);
        drift = vec2(0.9701425, 0.2425356);
        driftPhase = 22.0;
        swirl = 2.7;
    }
    else if(octave == 1)
    {
        k = 1.7;
        amplitude = 0.017 * clamp(cam.waterStyleParams1.z, 0.0, 2.0);
        domainX = vec2(-0.5048461, 0.8632094);
        domainY = vec2(-0.8632094, -0.5048461);
        drift = vec2(-0.3713907, -0.9284767);
        driftPhase = 50.0;
        swirl = -3.6;
    }
    else
    {
        k = 3.6;
        amplitude = 0.005 * clamp(cam.waterStyleParams1.w, 0.0, 2.0);
        domainX = vec2(0.9315684, -0.3635721);
        domainY = vec2(0.3635721, 0.9315684);
        drift = vec2(0.7071068, -0.7071068);
        driftPhase = 105.0;
        swirl = 4.5;
    }
    vec2 samplePoint =
        vec2(dot(worldXZ, domainX), dot(worldXZ, domainY)) * k +
        drift * (waveTime * driftPhase);

    vec2 rawGradient;
    psrdnoise(samplePoint, vec2(0.0), waveTime * swirl, rawGradient);

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
