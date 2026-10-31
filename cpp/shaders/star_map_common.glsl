#ifndef STAR_MAP_COMMON_GLSL
#define STAR_MAP_COMMON_GLSL

// 与 sky_atmosphere.comp 的 directionForPixel 严格互逆：那一边从 +x 轴朝 +z
// 方向写 azimuth = u * 2π。这里少了常数 0.5 的偏移就会把天空半转并镜像太阳辉光。
vec2 directionToEquirect(vec3 dir)
{
    const float TWO_PI = 6.28318530718;
    float u = fract(atan(dir.z, dir.x) / TWO_PI);
    float v = asin(clamp(dir.y, -1.0, 1.0)) / 3.14159265359 + 0.5;
    return vec2(u, v);
}

// =====================================================
// 星空日周自转
//
// 日月/大气/云走的是 getTimeDrivenSunDirection() 的"北纬 35° 赤道仪"：
// 轨道平面由 东=(1,0,0) 与 (0, cos35, sin35) 张成，自转轴即北纬 35° 天极
// P = (0, sin35, -cos35)。星空必须绕同一条轴转，否则一天下来星空相对日月会
// 漂移几十度（哈希噪声看不出来，真实星图的银河带会像天花板一样绕头顶转）。
//
// 做法：先把世界方向转进"天极朝上"的星图坐标系（Rx(55°) 把 P 送到
// (0,1,0)，55° = 90° - 35°），再绕新的 +Y 轴自转。自转角取 -dayAngle，使
// 太阳在星图上的采样点恒为 (1,0,0)，即星空与日月刚性绑定。
//
// rate：自转速率（圈/周期）。主世界传 1.0（`w * 1.0` 会被常量折叠成 `w`，
// 与旧代码逐位一致）；末地传 END_SKY_SPIN_RATE —— 天空与地球刚性一致，太阳
// 相对星空逆向漂移就是"公转"。
// =====================================================
vec3 starSphereSampleDirectionRate(vec3 dir, float rate)
{
    const float TWO_PI = 6.28318530718;

    float dayAngle = fract(cam.historyParams.w * rate) * TWO_PI;
    float c = cos(dayAngle);
    float s = sin(dayAngle);

    vec3 poleUp = vec3(
        dir.x,
        dir.y * SOLAR_LATITUDE_35_SIN - dir.z * SOLAR_LATITUDE_35_COS,
        dir.y * SOLAR_LATITUDE_35_COS + dir.z * SOLAR_LATITUDE_35_SIN
    );

    return vec3(
        poleUp.x * c + poleUp.z * s,
        poleUp.y,
        -poleUp.x * s + poleUp.z * c
    );
}

vec3 starSphereSampleDirection(vec3 dir)
{
    return starSphereSampleDirectionRate(dir, 1.0);
}

const float STAR_MAP_GAMMA = 2.2;
const float STAR_MAP_CONTRAST = 2.0;
const float STAR_MAP_EXPONENT = STAR_MAP_GAMMA * STAR_MAP_CONTRAST;
const float STAR_MAP_GAIN = 1.0;
const bool STAR_MAP_ENABLED = true;

vec3 evaluateStarMapRate(vec3 dir, float rate)
{
    dir = starSphereSampleDirectionRate(dir, rate);

    vec2 uv = directionToEquirect(dir);
    uv.y = 1.0 - uv.y;

    vec3 texel = textureLod(starMap, uv, 0.0).rgb;
    return pow(max(texel, vec3(0.0)), vec3(STAR_MAP_EXPONENT)) * STAR_MAP_GAIN;
}

vec3 evaluateStarMap(vec3 dir)
{
    return evaluateStarMapRate(dir, 1.0);
}

#endif
