#ifndef SKY_CHAPMAN_COMMON_GLSL
#define SKY_CHAPMAN_COMMON_GLSL

#ifndef SKY_CHAPMAN_ALTITUDE
#define SKY_CHAPMAN_ALTITUDE 1
#endif
#if SKY_CHAPMAN_ALTITUDE

const vec3 SKY_CHAPMAN_RAYLEIGH_COLUMN = vec3(0.0440, 0.1040, 0.1792);
const float SKY_CHAPMAN_MIE_COLUMN = 0.0216;
const float SKY_CHAPMAN_RAYLEIGH_H = 8000.0;
const float SKY_CHAPMAN_MIE_H = 1200.0;
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

#endif
#endif
