// ===========================================================================
// 2026-10-06 极光（雪地 / 云杉群系）
//
// 重构（2026-10-06 二版）：算法整体换成 nimitz《Auroras》(ShaderToy XtGGRt)。
//   旧版是我自己拼的"极光卵环带 + 双层 2D 噪声"，本质是**贴上天球的一层贴花**：
//   没有厚度、没有体积感，帘幕只会整片平移，贴近地平线的透视压缩也是错的。
//   nimitz 那套是**真正的体积薄层**，四件事各自解决一个问题：
//     ① triNoise2d —— 周期三角波折叠 + 5 阶旋转域扭曲（domain warping）。用
//        abs(fract(x)-.5) 取代三角函数/Perlin，既有刀削般锐利的丝状边缘又便宜；
//     ② 50 阶电离层光线步进 —— 沿视线在薄层里切 50 片，片高用 pow(i,1.4)
//        非线性分布（低空密、高空疏），模拟发光等离子体的空间厚度；
//     ③ 视线方向高频哈希抖动 —— 打散离散步进产生的阶梯色带（banding）；
//     ④ 高度光谱 —— 一行 sin 造出低空 557.7nm 氧绿 → 高空 630nm 氧红 +
//        391.4nm 氮紫的地球物理分色。
//   本文件只取"极光层"这一块：原作的冰湖倒影与 4 阶星空我们另有实现，不重复。
//
// 为什么单独一个头、而不是塞进 sky_model_common.glsl：
//   本文件读 CameraUBO 的**尾部字段** cam.auroraParams（偏移 1312，2026-10-06
//   追加）。GLSL 的函数体即使没被调用也照样要过类型检查，而 sky_model_common.glsl
//   的其它消费者（volumeclouds / 体积光 / 高层云 …）的 cam 块**没有**声明这一格
//   ⇒ 放进去会直接编译失败。所以单开一个文件。
//
// 消费者（三个，签名与门控语义必须保持一致）：
//   rt_test.rmiss       —— 可见天空，直接叠加到 skyColor；
//   sky_atmosphere.comp —— 天空 LUT（极光从此是真实光，不再是装饰）；
//   rt_test.rgen        —— 只取 AURORA_LIGHT_COLOR / AURORA_AMBIENT_STRENGTH，
//                          把极光算进夜天光（雪面泛青的来源）。
//
// 数据来源：Java 按玩家所在生物群系（寒冷 / 雪地 / 云杉针叶林）把强度写进
// CameraUBO.auroraParams.x（NativeVulkan.setAuroraStrength）。本文件只负责画，
// 门控（夜间 / 雨雪压制 / 维度）也在这里，主机侧不重复。
// ===========================================================================
#ifndef AURORA_COMMON_GLSL
#define AURORA_COMMON_GLSL

// ---------------------------------------------------------------------------
// 门控 / 曝光
// ---------------------------------------------------------------------------
// 极光作为**光源**的贡献（rt_test.rgen 的夜天光颜色）。
// 量级刻意很小：极光照度远低于月光，观感是"雪面泛青、阴影里也能看出极光在动"。
// 0 = 极光只当天空装饰（回到 2026-10-06 之前的行为）。
const float AURORA_AMBIENT_STRENGTH = 0.06;
// 极光的青绿偏色（557.7nm 氧绿 + 一点青）。直接加在夜天光颜色上。
const vec3 AURORA_LIGHT_COLOR = vec3(0.35, 1.00, 0.55);
// 总曝光。nimitz 原版自带 *1.8 的口径（见 AURORA_GAIN），这里再给一个独立旋钮，
// 方便把整体亮度和我们原来的天空口径对齐。想整体压暗/提亮只改这一个数。
const float AURORA_EXPOSURE = 0.95;

// ---------------------------------------------------------------------------
// 电离层薄层（数值沿用原版，与我们的世界尺度解耦）
// ---------------------------------------------------------------------------
// 极光贴在 100~400km 高空：玩家走几米带来的视差约 1e-5 rad，完全不可见 ⇒
// 不需要相机世界坐标（三个消费者里只有 rmiss 拿得到，comp/rgen 拿不到），
// 直接把射线起点取在"虚拟地面" ro = (0,0,0) 即可，观感与真实视差无差别。
// 这也让 LUT 与可见天空天然对齐 —— 它们必须逐位一致，见 evaluateAurora 注释。
const float AURORA_LAYER_BASE = 0.80;    // 原版 .8：薄层下界
const float AURORA_LAYER_GROW = 0.002;   // 原版 pow(i,1.4)*.002：上界增长
const float AURORA_STEPS = 50.0;         // 原版 50 阶（想省性能就往下调）
const float AURORA_NOISE_SCALE = 0.06;   // 原版 0.06：坐标旋转 + 帘幕横向尺度
const float AURORA_WARP_SPEED = 0.06;    // 原版 0.06：域扭曲随时间的旋转速度
const float AURORA_DITHER = 0.006;       // 原版 0.006：屏幕空间抖动幅度
const float AURORA_GAIN = 1.8;           // 原版 *1.8
// 每帧推进的"秒数"（按 60fps 折算）。原版用 iTime（秒）驱动域扭曲旋转，
// 而这三个消费者手里只有帧计数 cam.frameCount ⇒ 按 60fps 折成秒：帧率更高时
// 帘幕会略快，可用 auroraParams.z（时间倍率，默认 1.0）校正。
const float AURORA_TIME_PER_FRAME = 1.0 / 60.0;

// 原版里 `p *= -m2` 用的固定旋转矩阵。
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
        // 每阶的方向扰动 + 随时间旋转 ⇒ 帘幕像丝绸一样翻滚而不是整块平移。
        vec2 dg = auroraTri2(bp * 1.85) * 0.75 * auroraRot(time * AURORA_WARP_SPEED);
        p -= dg / z2;                  // 域扭曲：把扰动直接写回下一阶的采样坐标
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
        // 抖动只在高阶（远处）起作用：低阶是近景，抖了反而闪。
        float of = dither * smoothstep(0.0, 15.0, i);
        // 非线性高度映射：片高 = BASE + pow(i,1.4)*GROW；除以 (rd.y*2+0.4) 把
        // 高度换算成沿视线的距离 —— 地平线方向被拉长（透视压缩），天顶方向收短。
        float pt = (AURORA_LAYER_BASE + pow(i, 1.4) * AURORA_LAYER_GROW)
            / (rd.y * 2.0 + 0.4) - of;
        // ro = (0,0,0)：见文件头"为什么不需要相机世界坐标"。
        // 只取 bpos.zx（水平面）⇒ 帘幕是竖直的，竖直条纹来自"同一像素在不同片高
        // 上采到不同的水平坐标"，这正是原版窗帘感的来源。
        vec3 bpos = pt * rd;
        float rzt = auroraTriNoise2d(bpos.zx, time);
        vec4 col2 = vec4(0.0, 0.0, 0.0, rzt);
        // 高度光谱：i 增大（更高）时三通道相位平滑旋转。i=0 时绿通道 ~1.0（557.7nm
        // 氧绿），i=49 时红蓝协同增强（630nm 氧红 + 391.4nm 氮紫）。
        col2.rgb = (sin(1.0 - vec3(2.15, -0.5, 1.2) + i * 0.043) * 0.5 + 0.5) * rzt;
        // 沿视线的滑动平均：把 50 片连成一片连续的帷幔，而不是 50 张离散薄片。
        avgCol = mix(avgCol, col2, 0.5);
        // exp2 模拟 Beer-Lambert 自吸收；smoothstep(0,5,i) 压掉最前面几片的硬边。
        col += avgCol * exp2(-i * 0.065 - 2.5) * smoothstep(0.0, 5.0, i);
    }
    // 贴地渐隐：原版的 clamp(rd.y*15+.4) —— 在 rd.y = -0.027 处归零，
    // 到 rd.y = 0.04 满值。
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
    // 只在主世界出极光：末地有自己的银河/地球，下界看不到天。
    if(abs(cam.weatherParams.z - 1.0) >= 0.25)
    {
        return vec3(0.0);
    }
    // 夜间门控 + 雨雪压制。极光是热层现象，云雨一盖就什么都看不见。
    float night = smoothstep(0.06, -0.20, sunElevation);
    float weather = 1.0 - clamp(cam.weatherParams.x, 0.0, 1.0) * 0.85;
    float gate = strength * night * weather;
    if(gate <= 0.001)
    {
        return vec3(0.0);
    }
    // 薄层步进的除数 (rd.y*2 + 0.4) 在 rd.y <= -0.2 时过零；而地面以下的视线本来
    // 也不该有极光（原版的 clamp(rd.y*15+.4) 在 rd.y < -0.027 就归零了）。这里按
    // 同一个阈值早退，既不引入新的硬边，也省掉"贴地掠射视线"的 50 阶步进。
    if(rayDir.y <= -0.02)
    {
        return vec3(0.0);
    }
    float time = float(cam.frameCount) * AURORA_TIME_PER_FRAME * cam.auroraParams.z;
    return auroraRaymarch(rayDir, gate * AURORA_EXPOSURE, time);
}

#endif // AURORA_COMMON_GLSL
