#ifndef AURORA_COMMON_GLSL
#define AURORA_COMMON_GLSL

const float AURORA_AMBIENT_STRENGTH = 0.06;
const vec3 AURORA_LIGHT_COLOR = vec3(0.35, 1.00, 0.55);
const float AURORA_EXPOSURE = 0.95;

const float AURORA_LAYER_BASE = 0.80;
const float AURORA_LAYER_GROW = 0.002;
const float AURORA_STEPS = 50.0;
const float AURORA_NOISE_SCALE = 0.06;
const float AURORA_WARP_SPEED = 0.06;
const float AURORA_DITHER = 0.006;
const float AURORA_GAIN = 1.8;
const float AURORA_TIME_PER_FRAME = 1.0 / 60.0;

const mat2 AURORA_M2 = mat2(0.80, 0.60, -0.60, 0.80);

// nimitz 的按角度旋转矩阵（原版 mm2(a)）。
mat2 auroraRot(float a)
{
    float c = cos(a);
    float s = sin(a);
    return mat2(c, -s, s, c);
}

// 周期三角波：abs(fract(x)-.5) 折叠。clamp 到 [0.01,0.49] 是为了避开 0 与 0.5
// 两个退化点（原版就这么写的，去掉会出现细黑线）。
float auroraTri(float x)
{
    return clamp(abs(fract(x) - 0.5), 0.01, 0.49);
}

vec2 auroraTri2(vec2 p)
{
    return vec2(
        auroraTri(p.x) + auroraTri(p.y),
        auroraTri(p.y + auroraTri(p.x)));
}

// 5 阶旋转域扭曲噪声。返回前用 1./pow(rz*29., 1.3) 陡峭化 —— 这一下是画龙点睛：
// 把平滑噪声压成"边缘如刀削、中心高亮爆发"的等离子体丝状结构。
float auroraTriNoise2d(vec2 p, float time)
{
    float z = 1.8;
    float z2 = 2.5;
    float rz = 0.0;
    p *= auroraRot(p.x * AURORA_NOISE_SCALE);
    vec2 bp = p;
    for (float i = 0.0; i < 5.0; i++)
    {
        vec2 dg = auroraTri2(bp * 1.85) * 0.75 * auroraRot(time * AURORA_WARP_SPEED);
        p -= dg / z2;
        bp *= 1.3;
        z2 *= 0.45;
        z *= 0.42;
        p *= 1.21 + (rz - 1.0) * 0.02;
        rz += auroraTri(p.x + auroraTri(p.y)) * z;
        p *= -AURORA_M2;
    }
    return clamp(1.0 / pow(rz * 29.0, 1.3), 0.0, 0.55);
}
// 逐像素抖动种子。
// 原版用 gl_FragCoord.xy，但本头文件同时被 rmiss（光线生成阶段，没有
// gl_FragCoord）与 sky_atmosphere.comp（计算阶段，只有 gl_GlobalInvocationID）
// 包含，像素内建不通用。改用**视线方向**的高频哈希：相邻像素方向差约 1e-3 rad，
// 乘 512 后在哈希里完全去相关，同样能打散步进色带；而且它与像素内建无关 ⇒
// LUT 与可见天空用的是同一个种子，两者天然对齐（这一点比"必须用屏幕坐标"更重要）。
float auroraDither(vec3 rd)
{
    vec2 sp = vec2(
        dot(rd, vec3(127.1, 311.7, 74.7)),
        dot(rd, vec3(269.5, 183.3, 246.1))) * 512.0;
    return fract(sin(sp.x + sp.y) * 43758.5453123);
}

// nimitz 的极光体积步进。rd 必须已归一化（调用方契约），且 rd.y > -0.02。
// strength = 已算好的总门控（生物群系 × 夜间 × 天气 × 曝光）。
vec3 auroraRaymarch(vec3 rd, float strength, float time)
{
    float dither = AURORA_DITHER * auroraDither(rd);

    vec4 col = vec4(0.0);
    vec4 avgCol = vec4(0.0);
    for (float i = 0.0; i < AURORA_STEPS; i++)
    {
        float of = dither * smoothstep(0.0, 15.0, i);
        float pt = (AURORA_LAYER_BASE + pow(i, 1.4) * AURORA_LAYER_GROW)
            / (rd.y * 2.0 + 0.4) - of;
        vec3 bpos = pt * rd;
        float rzt = auroraTriNoise2d(bpos.zx, time);
        vec4 col2 = vec4(0.0, 0.0, 0.0, rzt);
        col2.rgb = (sin(1.0 - vec3(2.15, -0.5, 1.2) + i * 0.043) * 0.5 + 0.5) * rzt;
        avgCol = mix(avgCol, col2, 0.5);
        col += avgCol * exp2(-i * 0.065 - 2.5) * smoothstep(0.0, 5.0, i);
    }
    col *= clamp(rd.y * 15.0 + 0.4, 0.0, 1.0);
    return col.rgb * (AURORA_GAIN * strength);
}

// 该视线方向上的极光辐射（0 = 不画）。sunElevation < 0 表示太阳在地平线下。
// 签名与门控语义被三个消费者共用，改动必须同时验证 rmiss / comp / rgen。
vec3 evaluateAurora(vec3 rayDir, float sunElevation)
{
    float strength = cam.auroraParams.x * cam.auroraParams.y;
    if(strength <= 0.001)
    {
        return vec3(0.0);
    }
    if(abs(cam.weatherParams.z - 1.0) >= 0.25)
    {
        return vec3(0.0);
    }
    float night = smoothstep(0.06, -0.20, sunElevation);
    float weather = 1.0 - clamp(cam.weatherParams.x, 0.0, 1.0) * 0.85;
    float gate = strength * night * weather;
    if(gate <= 0.001)
    {
        return vec3(0.0);
    }
    if(rayDir.y <= -0.02)
    {
        return vec3(0.0);
    }
    float time = float(cam.frameCount) * AURORA_TIME_PER_FRAME * cam.auroraParams.z;
    return auroraRaymarch(rayDir, gate * AURORA_EXPOSURE, time);
}

#endif
