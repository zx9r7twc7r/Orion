// ===========================================================================
// 2026-10-06：星图（rt_star_map.png）的**单一真源**采样代码。
//
// 原先这些函数只住在 rt_test.rmiss 里，于是"星图只出现在可见天空、不参与光照"
// ——而 sky_atmosphere.comp 又需要把星图算进天空 LUT（让它真正成为光源的一
// 部分，被解析天空反射/环境项读到）。两处必须逐位一致，所以抽到这里共用。
//
// 消费方需要自备：
//   1) 一个名为 `starMap` 的 sampler2D（各自 set/binding 不同：rmiss 是 RT 集的
//      binding 44，sky_atmosphere.comp 是它自己那套 set 的 binding 6）；
//   2) 常量 SOLAR_LATITUDE_35_SIN / _COS（各 shader 里本来就有）；
//   3) 能读到 cam.historyParams.w（星空自转相位）。
// 本文件不做 include，避免把别人的相机块声明拖进来。
// ===========================================================================
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

    // 1) 世界 -> 天极朝上。Rx(55°)；注意 cos55° = sin35°、sin55° = cos35°。
    vec3 poleUp = vec3(
        dir.x,
        dir.y * SOLAR_LATITUDE_35_SIN - dir.z * SOLAR_LATITUDE_35_COS,
        dir.y * SOLAR_LATITUDE_35_COS + dir.z * SOLAR_LATITUDE_35_SIN
    );

    // 2) 绕天极做日周自转（XZ 平面内极角减去 dayAngle）。
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

// =====================================================
// 星空贴图（夜晚天空）
//
// rt_star_map.png 存的是 pow(linear, 1/2.2)：该星图中位亮度只有 0.008，直接
// 按线性存 8bit 会把银河（占 92.7% 的像素）压进个位数级别、严重断层。
//
// 解码之后还必须再抬一次对比度。星图的漫射背景（银河弥散光）中位亮度 0.008，
// 原样叠加会把夜空整体抬到约 11% 灰（显示域），完全不像夜晚；抬到
// STAR_MAP_CONTRAST 次方后空天空降到约 1.3%、亮银河约 18%，而亮星仍保持满
// 量程，才是"黑底 + 星 + 可见银河带"的观感。两个指数合成一次 pow。
// =====================================================
const float STAR_MAP_GAMMA = 2.2;
const float STAR_MAP_CONTRAST = 2.0;
const float STAR_MAP_EXPONENT = STAR_MAP_GAMMA * STAR_MAP_CONTRAST;
// 星图满量程即纯白亮星；另给一个总增益便于整体调亮。
const float STAR_MAP_GAIN = 1.0;
// 兜底开关：贴图缺失或想回到旧观感时置 false，走程序化 hash 星场。
const bool STAR_MAP_ENABLED = true;

vec3 evaluateStarMapRate(vec3 dir, float rate)
{
    // 与程序化星场共用同一自转，见 starSphereSampleDirection()。
    dir = starSphereSampleDirectionRate(dir, rate);

    vec2 uv = directionToEquirect(dir);
    // directionToEquirect 的 v=1 才是 +Y，而等距柱状星图的顶端（贴图 v=0）
    // 才是天极，所以这里翻转。
    uv.y = 1.0 - uv.y;

    // LOD 0：miss 着色器里的隐式导数是实现相关的；LUT pass 里则是因为
    // 该 pass 本来就按 texel 中心取方向，不需要导数。
    vec3 texel = textureLod(starMap, uv, 0.0).rgb;
    return pow(max(texel, vec3(0.0)), vec3(STAR_MAP_EXPONENT)) * STAR_MAP_GAIN;
}

vec3 evaluateStarMap(vec3 dir)
{
    return evaluateStarMapRate(dir, 1.0);
}

#endif // STAR_MAP_COMMON_GLSL
