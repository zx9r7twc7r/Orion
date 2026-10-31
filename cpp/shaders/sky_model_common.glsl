#ifndef SKY_MODEL_COMMON_GLSL
#define SKY_MODEL_COMMON_GLSL

#include "sky_sun_common.glsl"

vec3 sunDirection()
{
    const float TWO_PI = 6.28318530718;
    float angle = fract(cam.historyParams.w) * TWO_PI;
    return normalize(vec3(cos(angle), 0.81915204429 * sin(angle),
        0.57357643635 * sin(angle)));
}


const vec3 END_SKY_POLE = vec3(0.0, 0.57357643635, -0.81915204429);
const float END_SKY_SPIN_RATE = 2.0;
const float END_EARTH_PHASE_OFFSET = 0.75;
const float END_EARTH_TILT = 0.40840704497;
const float END_EARTH_ANGULAR_RADIUS = 0.4363;

// 黄道（太阳日轨所在的平面）上的单位方向，t 以"圈"为单位。
vec3 endEclipticDirection(float t)
{
    const float TWO_PI = 6.28318530718;
    float a = fract(t) * TWO_PI;
    return normalize(vec3(cos(a), 0.81915204429 * sin(a),
        0.57357643635 * sin(a)));
}

// 地球方向（现在**会动**：2 圈/周期，沿黄道）。
vec3 endEarthDirection()
{
    return endEclipticDirection(
        cam.historyParams.w * END_SKY_SPIN_RATE + END_EARTH_PHASE_OFFSET);
}

// 地球自转轴。两个要求：
//   1) 与地球方向**刚性**同步（都随星空前进），并且**始终垂直**于地球方向
//      ⇒ 视面中心永远落在赤道附近。原实现用常量轴 (0.3978,0.9175,0) 几乎就是
//      世界竖直方向，与地球方向夹角只有 41° ⇒ 视面中心在 48.7°S、最北只看到
//      41°N，玩家永远只能看到"南极 + 南大洋"，认不出是地球。
//   2) 保留 23.4° 轴倾角，看起来像真实行星。
// 构造：黄道上超前 90° 的方向，再朝天极倾斜 23.4°（于是 ⊥ 地球方向，恒成立）。
vec3 endEarthAxis()
{
    float t = cam.historyParams.w * END_SKY_SPIN_RATE +
        END_EARTH_PHASE_OFFSET + 0.25;
    return normalize(cos(END_EARTH_TILT) * endEclipticDirection(t) +
        sin(END_EARTH_TILT) * END_SKY_POLE);
}

#ifndef END_ORB_ENABLE
#define END_ORB_ENABLE 1
#endif

#if END_ORB_ENABLE
const float END_ORB_ANGULAR_RADIUS = 0.4581;
const vec3  END_ORB_GLOW_COLOR = vec3(1.0, 0.62, 0.2);
const float END_ORB_HALO_STRENGTH = 0.9;
const float END_ORB_AURA_STRENGTH = 1.5;
const float END_ORB_HALO_SIGMA = 0.014;
const float END_ORB_AURA_SIGMA = 0.087;
const float END_ORB_IOR = 1.46;
const float END_ORB_MILK = 0.008;
const float END_ORB_PLASMA = 3.5;
const float END_ORB_SMOKE = 0.0;
const float END_ORB_GLYPH_GLOW = 6.0;
const float END_ORB_RUNES = 18.0;
const float END_ORB_BAND_R = 1.035;
const float END_ORB_BAND_H = 0.17;
const int   END_ORB_VOLUME_STEPS = 32;
const float END_ORB_ANIM_RATE = 24.0;

// 光源球方向：2026-10-18 用户定档"末地正中央上空，只自转不公转" ⇒ 固定天顶。
// **六个方向副本（rgen/rmiss/rchit×2/water_caustics + 这里）必须同一向量**，
// 否则可见球与直射光方向分家。球内部的自转仍由 tDay/spinA 驱动（见 rmiss）。
vec3 endOrbDirection()
{
#if 0
    const float TWO_PI = 6.28318530718;
    float a = fract(cam.historyParams.w * 29.5) * TWO_PI;
    return vec3(0.5 * cos(a), 0.86602540378, 0.5 * sin(a));
#else
    return vec3(0.0, 1.0, 0.0);
#endif
}
#endif
const float END_SUN_ANGULAR_RADIUS = 0.0046250;
const float END_SUN_DIAMETER_DEGREES = 0.53;
const float END_SPACE_STRENGTH = 1.25;
const float END_STAR_STRENGTH = 0.15;
const vec3 END_GALAXY_LIGHT_COLOR = vec3(0.62, 0.72, 1.00);
const float END_GALAXY_AMBIENT_LIFT = 0.10;
const float END_EARTH_SRGB = 2.2;
const float END_NIGHT_BASE = 0.006;
const float END_NIGHT_LIGHTS_STRENGTH = 8.0;
const float END_NIGHT_POLAR_FADE = 0.92;
const vec3 END_EARTH_DAYLIGHT = vec3(1.35, 1.32, 1.27);
const float END_DAY_RAMP_TOP = 0.25;
const float END_CLOUD_ALBEDO = 0.80;
const float END_EARTHSHINE_STRENGTH = 0.03;
const float END_SUN_GLOW_SCALE = 0.0;

const float END_CLOUD_SPIN_MULTIPLIER = 1.15;
const float END_CLOUD_OPACITY = 0.90;
const float END_CLOUD_THRESHOLD_LO = 0.06;
const float END_CLOUD_THRESHOLD_HI = 0.71;

// 1 = 太阳完全可见；0 = 被地球完全挡住（月食）。只认**真实**太阳方向：
// 夜里 rmiss/rgen 会把方向翻到"月亮"，那不是日食的几何。
// earthDir 必须由调用方传 endEarthDirection() —— 地球现在是动的，不能当常量。
float endEarthEclipseFactor(vec3 sunDir, vec3 earthDir)
{
    if(abs(cam.weatherParams.z - 3.0) >= 0.25)
    {
        return 1.0;
    }
    float angle = acos(clamp(dot(sunDir, earthDir), -1.0, 1.0));
    return smoothstep(END_EARTH_ANGULAR_RADIUS - END_SUN_ANGULAR_RADIUS,
                      END_EARTH_ANGULAR_RADIUS + END_SUN_ANGULAR_RADIUS, angle);
}

// 地球被照亮的比例：0 = 全食（地球只有暗面朝我们）、1 = 满地。
float endEarthPhase(vec3 sunDir, vec3 earthDir)
{
    return 0.5 * (1.0 - dot(sunDir, earthDir));
}

const vec3 TWILIGHT_DAWN_TINT = vec3(1.00, 0.48, 0.24);
const vec3 TWILIGHT_DUSK_TINT = vec3(1.00, 0.45, 0.08);
const vec3 TWILIGHT_PREDAWN_TINT = vec3(0.62, 0.50, 0.86);

float twilightEveningWeight()
{
    const float TWO_PI = 6.28318530718;
    return smoothstep(0.20, -0.20, cos(fract(cam.historyParams.w) * TWO_PI));
}

// 拂晓窗口：上升侧、太阳高度角 -0.30..-0.02。
float twilightPredawnWeight(float sunElevation)
{
    return (1.0 - twilightEveningWeight()) *
        smoothstep(-0.30, -0.10, sunElevation) *
        (1.0 - smoothstep(-0.10, -0.02, sunElevation));
}

// 曙暮光色相：黄昏橘红、黎明橘粉、拂晓冷紫。
vec3 twilightPalette(float sunElevation)
{
    return mix(
        mix(TWILIGHT_DAWN_TINT, TWILIGHT_DUSK_TINT, twilightEveningWeight()),
        TWILIGHT_PREDAWN_TINT,
        twilightPredawnWeight(sunElevation)
    );
}

// 阳光色核心。goldenStrength：日照金山混入强度（1.0 全档 / 0.5 云半档）。
// goldenStrength=1.0 时 mix(x,y,1.0) 逐位等于 y（0·x+y，IEEE 精确），与
// 旧直写 skySunGoldenTint(...) 语义一致。
vec3 skyModelSunColor(vec3 lightDirection, float goldenStrength)
{
#if SKY_SUN_TRANSMITTANCE_COLOR
    vec3 color = skySunPhysicalBaseColor(lightDirection.y);
#else
    float highSun = smoothstep(0.08, 0.68, max(lightDirection.y, 0.0));
    vec3 color = mix(vec3(1.0, 0.45, 0.18), vec3(1.0, 0.96, 0.90), highSun);
#endif
    float rain = clamp(cam.weatherParams.x, 0.0, 1.0);
    float thunder = clamp(cam.weatherParams.y, 0.0, 1.0);
    float sunset = 1.0 - smoothstep(0.04, 0.22, lightDirection.y);
    vec3 twilightTint = twilightPalette(lightDirection.y);
    color = mix(color, twilightTint, sunset * 0.78);
#if SKY_DAY_PHASE
    color = mix(color, skySunGoldenTint(color, lightDirection.y), goldenStrength);
#endif
    return color * mix(1.0, 0.35, rain) *
        mix(vec3(1.0), vec3(0.72, 0.78, 0.86), thunder);
}

// ---------------------------------------------------------------------------
// 2026-10-21 雷暴频闪（整场脉冲）
//
// weatherParams.y（原版 thunder，[0,1]）门控：每个 ~0.6 s 间隔按哈希决定要不要
// 打一次闪，主闪 ~0.08 s 指数衰减，0.14 s 后一次较弱的回声。返回 [0,1]，乘上
// 各消费点自己的颜色/强度就是一次天光闪。
//
// 时钟用 cam.frameCount：没有新增 uniform，且同一帧里天空 LUT / 云 / 视线天空
// 读的是同一个值 ⇒ 三处同步同一次闪。按 60 Hz 折算（帧率翻了闪频跟着翻，可接受）。
// 非雷暴（thunder <= 0）逐位返回 0 ⇒ 晴天/普通雨天画面的每一步都不受影响。
// ---------------------------------------------------------------------------
float lightningFlashEnvelope()
{
    float thunder = clamp(cam.weatherParams.y, 0.0, 1.0);
    if(thunder <= 0.001)
    {
        return 0.0;
    }
    float t = float(cam.frameCount) * (1.0 / 60.0);
    float scaled = t * 1.6;
    float cell = floor(scaled);
    float f = fract(scaled);
    float h = fract(sin(cell * 12.9898 + 4.1414) * 43758.5453);
    float gate = step(0.62, h);
    float a = exp(-f * 42.0);
    float b = 0.55 * exp(-max(f - 0.14, 0.0) * 55.0);
    return gate * (a + b) * thunder;
}

#endif
