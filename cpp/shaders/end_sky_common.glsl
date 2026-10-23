// ===========================================================================
// end_sky_common.glsl —— 末地天空的**单一真源**。
//
// 2026-10-18：背景换成 Shadertoy "Star Nest"（Pablo Roman Andrioli, MIT）的
// 体积分形星空；原"图集银河 + 程序化银河"整段退役，保留在文件末尾的 #if 0
// 里（含恢复说明）。可见天空（rt_test.rmiss）、天空 LUT（sky_atmosphere.comp）
// 与光源球的环境反射必须共用本文件的同一函数，否则反射里的星空会与眼睛
// 看到的错位 —— 与 star_map_common.glsl / aurora_common.glsl 同一个理由。
//
// 消费方需要自备：
//   1) 已 include "star_map_common.glsl"（starSphereSampleDirectionRate）与
//      "sky_model_common.glsl"（END_SKY_SPIN_RATE）；
//   2) 能读到 cam.historyParams.w（星空自转相位）。
// 本文件不 include 别人，避免把相机块声明拖进不需要它的消费者。
// 图集 sampler `endSkyAtlas` 不再被天空使用（地球/云代码仍保留声明）。
// ===========================================================================
#ifndef END_SKY_COMMON_GLSL
#define END_SKY_COMMON_GLSL

// =====================================================
// Star Nest 移植（2026-10-18）
//
// 原作是屏幕空间 shader：uv -> dir、鼠标旋转、相机在 from 处穿过分形。
// miss/LUT 只有世界方向 ⇒ 用射线方向替换 uv：
//   · 先按末地星图同一套自转（END_SKY_SPIN_RATE）转进星图标架；
//   · 再套原作 iMouse 在屏幕正中时的固定旋转 a1=0.5 / a2=0.8；
//   · from 的平移仍由时间驱动，保留原作"缓慢穿越"的运动感。
// 方向归一化后视场边缘没有原作 uv 的透视拉伸；分形的"屏幕中心"落在
// rot1/rot2 的逆变换方向上，刻意不与天顶对齐（星云本身无中心，不影响观感）。
// 性能：VOLSTEPS × ITERATIONS 是每像素最贵的一段；GI/反射逃逸也走这里，
// 卡顿时优先降 END_STAR_NEST_VOLSTEPS（20 -> 14/12）。
// =====================================================
#ifndef END_STAR_NEST_ENABLE
#define END_STAR_NEST_ENABLE 1
#endif

#if END_STAR_NEST_ENABLE
#ifndef END_STAR_NEST_VOLSTEPS
#define END_STAR_NEST_VOLSTEPS 20
#endif
#ifndef END_STAR_NEST_ITERATIONS
#define END_STAR_NEST_ITERATIONS 17
#endif
// 总增益：1.0 = 原作 v * 0.01。游戏里嫌暗/过曝只动这一个数。
#ifndef END_STAR_NEST_STRENGTH
#define END_STAR_NEST_STRENGTH 1.0
#endif

const float END_STAR_NEST_FORMUPARAM = 0.53;
const float END_STAR_NEST_STEPSIZE = 0.1;
const float END_STAR_NEST_TILE = 0.85;
const float END_STAR_NEST_SPEED = 0.010;
const float END_STAR_NEST_BRIGHTNESS = 0.0015;
const float END_STAR_NEST_DARKMATTER = 0.300;
const float END_STAR_NEST_DISTFADING = 0.730;
const float END_STAR_NEST_SATURATION = 0.850;

vec3 endStarNestRadiance(vec3 rayDir, float skyTime)
{
    vec3 dir = starSphereSampleDirectionRate(rayDir, END_SKY_SPIN_RATE);

    // 原作 iMouse 在屏幕正中（a1 = .5 + 0，a2 = .8 + 0）。
    const float A1 = 0.5;
    const float A2 = 0.8;
    mat2 rot1 = mat2(cos(A1), sin(A1), -sin(A1), cos(A1));
    mat2 rot2 = mat2(cos(A2), sin(A2), -sin(A2), cos(A2));
    dir.xz *= rot1;
    dir.xy *= rot2;
    dir = normalize(dir);

    float time = skyTime * END_STAR_NEST_SPEED + 0.25;
    vec3 from = vec3(1.0, 0.5, 0.5);
    from += vec3(time * 2.0, time, -2.0);
    from.xz *= rot1;
    from.xy *= rot2;

    float s = 0.1;
    float fade = 1.0;
    vec3 v = vec3(0.0);
    for(int r = 0; r < END_STAR_NEST_VOLSTEPS; r++)
    {
        vec3 p = from + s * dir * 0.5;
        p = abs(vec3(END_STAR_NEST_TILE) -
            mod(p, vec3(END_STAR_NEST_TILE * 2.0)));
        float pa = 0.0;
        float a = 0.0;
        for(int i = 0; i < END_STAR_NEST_ITERATIONS; i++)
        {
            p = abs(p) / dot(p, p) - END_STAR_NEST_FORMUPARAM;
            a += abs(length(p) - pa);
            pa = length(p);
        }
        float dm = max(0.0, END_STAR_NEST_DARKMATTER - a * a * 0.001);
        a *= a * a;
        if(r > 6) fade *= 1.0 - dm;
        v += fade;
        v += vec3(s, s * s, s * s * s * s) * a *
            END_STAR_NEST_BRIGHTNESS * fade;
        fade *= END_STAR_NEST_DISTFADING;
        s += END_STAR_NEST_STEPSIZE;
    }
    v = mix(vec3(length(v)), v, END_STAR_NEST_SATURATION);
    vec3 radiance = v * 0.01 * END_STAR_NEST_STRENGTH;
    // 显示端把 NaN/Inf 写成纯黑；分形在极端迭代下偶尔会溢出，这里直接归零。
    if(any(isnan(radiance)) || any(isinf(radiance)))
    {
        return vec3(0.0);
    }
    return max(radiance, vec3(0.0));
}
#endif // END_STAR_NEST_ENABLE

// 末地天空辐射的唯一入口（当前 = Star Nest）。可见天空 / LUT / 球体反射都走它。
vec3 endSkyRadiance(vec3 rayDir, float skyTime)
{
#if END_STAR_NEST_ENABLE
    return endStarNestRadiance(rayDir, skyTime);
#else
    return vec3(0.0);
#endif
}

// ===========================================================================
// 2026-10-18 退役：图集银河 + 程序化银河（Shadertoy "Spiral galaxy" CC0）。
// 恢复方式：把下面 #if 0 改成 #if 1，并把三个调用点从 endSkyRadiance 换回
// endSkyGalaxyRadiance（rt_test.rmiss 可见天空 + 球体 env 反射、
// sky_atmosphere.comp 天空 LUT）。旧档逐位保留，不要删除。
// ===========================================================================
#if 0

// 2026-10-09 图集银河提亮曲线（见 endSkyGalaxyRadiance 的说明）：1.0 = 不处理
// （回到"图集那层几乎全黑、程序化银河的暗区就是纯黑"的旧观感）。
#ifndef END_ATLAS_GALAXY_LIFT
#define END_ATLAS_GALAXY_LIFT 0.65
#endif

// 图集四格是**紧邻**的，LINEAR 过滤在格子边界上会把隔壁格的像素混进来。
// 实测（离线圆盘渲染）：等距圆柱的极点行正好落在子图上下边，于是极区被混进
// 隔壁格的亮色 ⇒ 盘缘一圈亮弧。半纹素内缩把采样钉在自己格内；量级：8192 宽
// ⇒ 1 纹素 = 1.2e-4（图集 U）。
const float END_ATLAS_INSET = 2.5e-4;
float endAtlasInset(float x)
{
    return x * (1.0 - 2.0 * END_ATLAS_INSET) + END_ATLAS_INSET;
}

// =====================================================
// 2026-10-08 末地程序化银河（Shadertoy "Spiral galaxy" CC0 移植，作背景）。
// 原作是站在银河盘上方 0.525、离核 1.5 处的相机；miss shader 只有方向没有
// 位置 ⇒ 把原相机几何搬进一个与视线无关的"银河标架"：盘面法线 GAL_N、
// 盘内核心方位 GAL_U，相机在原点、盘面在脚下 GAL_H、核心在盘内 GAL_D 处。
// GAL_H/GAL_D 同时乘 END_GALAXY_SCALE ⇒ 角尺寸 ×1/SCALE（0.42 ≈ 全跨 ~90°）。
// 自转沿用星图相位 starSphereSampleDirectionRate(END_SKY_SPIN_RATE)，与被替换
// 的图集银河同一约定。原 postProcess 的 vignette 是屏幕空间项，方向背景无
// 意义，丢弃；pow(0.75)/对比度/降饱和保留。
// =====================================================
#ifndef END_GALAXY_ENABLE
#define END_GALAXY_ENABLE 1
#endif

#if END_GALAXY_ENABLE
// 2026-10-09：1.0 -> 1.5（用户："末地的银河太暗了"）。这是程序化银河的总增益，
// 与 GUI"银河叠加强度"（weight，见下方 GAL_MIX）相乘 ⇒ 两条腿都能单独调：
// 想更亮改这里，想在图集星空与银河之间改配比就动 GUI 滑块。
#define END_GALAXY_STRENGTH 1.5
// 核心球辉光（原作被注释掉的一行，0.9·colDust·exp(-10l²)）。高度场在核心
// 是 SABS 谷底 ⇒ 无它时核心是一块暗斑、外圈套尘埃辉光，游戏里被自动曝光
// 压成"黑球"（2026-10-08 实测）。置 0 回原作观感。
#define END_GALAXY_CORE_GLOW 0.9
// 高度场 octave 数（原作 11）。每个盘面命中光线要算 5 次高度场（本体+法线
// 4 邻），GI/反射光线逃逸时同样命中 ⇒ 降这里是最直接的性能旋钮。
#ifndef END_GALAXY_OCTAVES
#define END_GALAXY_OCTAVES 11
#endif

// 运行时三滑块（2026-10-08）：宿主每帧经 cam.galaxyParams 推来
//   x = 大小倍率（原 END_GALAXY_SCALE；调小 = 银河更大）
//   y = 倾斜角（银河盘面与水平面的夹角，度）
//   z = 朝向（核心方位的世界方位角，度）
// x <= 0 视为"宿主没推过设置"（旧 DLL 配新 spv）：三个一起回落到编译期默认，
// 保证 GAL_N 不会吃到 0 长度塌成 NaN（整片天空会跟着黑）。
#define END_GALAXY_SCALE    0.85   // 默认大小（0.42 视野糊进内部；0.7 为上一档）
#define END_GALAXY_TILT_DEG 30.0   // 默认倾斜：盘面与水平面成 30° 二面角
#define END_GALAXY_YAW_DEG  -45.0  // 默认朝向：核心方位 ≈ -45°（旧走向）
// 叠加权重（2026-10-08 由"替换式"改成叠加后的新旋钮）：0 = 只剩图集星空，
// 1 = 图集星空 + 满亮银河。默认取量程中点，两边都留同样多的余量。
#define END_GALAXY_MIX      0.5
float GAL_SCALE;
float GAL_H;
float GAL_D;
vec3 GAL_N;
vec3 GAL_U;
float GAL_MIX;

void galaxyApplyParams()
{
    float gScale = END_GALAXY_SCALE;
    float gTilt = END_GALAXY_TILT_DEG;
    float gYaw = END_GALAXY_YAW_DEG;
    float gMix = END_GALAXY_MIX;
    if(cam.galaxyParams.x > 0.0)
    {
        gScale = cam.galaxyParams.x;
        gTilt = cam.galaxyParams.y;
        gYaw = cam.galaxyParams.z;
        // w = 0 是合法值（= 关银河、只看图集），所以它不能自己当哨兵；
        // 整组由 x 判"宿主有没有推过设置"，旧 DLL 配新 spv 时四个一起回落。
        gMix = clamp(cam.galaxyParams.w, 0.0, 1.0);
    }
    GAL_SCALE = gScale;
    GAL_MIX = gMix;
    GAL_H = 0.525 * gScale;
    GAL_D = 1.5 * gScale;
    // 盘面与水平面夹角 tilt ⇒ 法线仰角 = 90° - tilt。默认档（30°/-45°）下
    // cE=0.5、仰角 60° ⇒ GAL_N = normalize(0.354, 0.866, -0.354)，与初版逐位一致。
    float gElev = radians(90.0 - gTilt);
    float gAz = radians(gYaw);
    float cE = cos(gElev);
    GAL_N = normalize(vec3(cE * cos(gAz), sin(gElev), cE * sin(gAz)));
    // 盘内"核心方位"（世界水平面内，恒与 GAL_N 垂直）。
    GAL_U = normalize(cross(vec3(0.0, 1.0, 0.0), GAL_N));
}

vec2 galToPolar(vec2 p)
{
    return vec2(length(p), atan(p.y, p.x));
}

vec2 galToRect(vec2 p)
{
    return p.x * vec2(cos(p.y), sin(p.y));
}

vec2 galMod2(inout vec2 p, vec2 size)
{
    vec2 c = floor((p + size * 0.5) / size);
    p = mod(p + size * 0.5, size) - size * 0.5;
    return c;
}

float galRand(vec2 co)
{
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void galRot(inout vec2 p, float a)
{
    float c = cos(a);
    float s = sin(a);
    p = vec2(c * p.x + s * p.y, -s * p.x + c * p.y);
}

// 原作 noise1：四个余弦的乘积（tm 让旋臂缓慢呼吸）。
float galNoise1(vec2 p, float tm)
{
    p *= tanh(0.1 * length(p));
    return cos(p.x) * cos(p.y)
        * cos(p.x * sqrt(3.5) + tm) * cos(p.y * sqrt(1.5) + tm);
}

// 原作 twirl：极角按半径扭转（twirly=2.5），z 是 octave 的频率缩放。
vec2 galTwirl(vec2 p, float a, float z)
{
    vec2 pp = galToPolar(p);
    pp.y += pp.x * 2.5 + a;
    p = galToRect(pp);
    return p * z;
}

float galHeight(vec2 p, float galT)
{
    float ang = atan(p.y, p.x);
    float l = length(p);
    float sp = mix(1.0,
        pow(0.75 + 0.25 * sin(2.0 * (ang + l * 2.5)), 3.0), tanh(6.0 * l));
    float s = 0.0;
    float a = 1.0;
    float f = 15.0;
    float d = 0.0;
    for(int i = 0; i < END_GALAXY_OCTAVES; ++i)
    {
        s += a * galNoise1(galTwirl(p, 0.0025 * float(i) * galT, f), 0.1 * galT);
        a *= sqrt(0.45);
        f *= sqrt(2.0);
        d += a;
    }
    s *= sp;
    // 原作 SABS(x, 0.5) = mix(x*x+0.25, |x|, step(0, |x|-0.5))。
    float x = -0.25 + s / d;
    float hSabs = mix(x * x + 0.25, abs(x), step(0.0, abs(x) - 0.5));
    return hSabs * exp(-5.5 * l * l);
}

vec3 galNormal(vec2 p, float galT)
{
    vec2 eps = vec2(0.000125, 0.0);
    vec3 n;
    n.x = galHeight(p - eps.xy, galT) - galHeight(p + eps.xy, galT);
    n.y = 2.0 * eps.x;
    n.z = galHeight(p - eps.yx, galT) - galHeight(p + eps.yx, galT);
    return normalize(n);
}

vec3 galStars(vec2 p)
{
    vec2 pp = galToPolar(p);
    pp.x /= (1.0 + length(pp.x)) * 0.5;
    p = galToRect(pp);

    float sz = 0.0075;
    vec3 s = vec3(10000.0);
    for(int i = 0; i < 3; ++i)
    {
        galRot(p, 0.5);
        vec2 ip = p;
        vec2 n = galMod2(ip, vec2(sz));
        float r = galRand(n);
        vec2 o = -1.0 + 2.0 * vec2(r, fract(r * 1000.0));
        s.x = min(s.x, length(ip - 0.25 * sz * o));
        s.yz = n * 0.1;
    }
    return s;
}

void galRaySphere(vec3 ro, vec3 rd, vec3 center, float radius, inout vec2 hits)
{
    hits = vec2(-1.0);
    vec3 m = ro - center;
    float b = dot(m, rd);
    float c = dot(m, m) - radius * radius;
    if(c > 0.0 && b > 0.0)
    {
        return;
    }
    float discr = b * b - c;
    if(discr < 0.0)
    {
        return;
    }
    float s = sqrt(discr);
    hits = vec2(-b - s, -b + s);
}

vec3 galShade(vec2 p, float dgal, vec3 rdl, float galT)
{
    galRot(p, 0.05 * galT);

    float h = galHeight(p, galT);
    vec3 s = galStars(p);
    float th = tanh(h);
    vec3 n = galNormal(p, galT);

    vec3 p3 = vec3(p.x, th, p.y);
    // 原作 lp1 == lp2（同一点），且 diff1 项已被注释 ⇒ 单光源即可。
    vec3 ld = normalize(vec3(0.0, 0.5, 0.0) - p3);

    float l = length(p);
    float tl = tanh(l);

    float diff2 = max(dot(ld, n), 0.0);

    vec3 col = vec3(0.0);
    col += vec3(0.5, 0.5, 0.75) * h;
    col += 0.25 * pow(diff2, 4.0);
    col += pow(vec3(0.5) * h,
        n.y * 1.75 * mix(vec3(0.5, 1.0, 1.5), vec3(0.5, 1.0, 1.5).zyx, 1.25 * tl));
    col += END_GALAXY_CORE_GLOW * vec3(1.0, 0.9, 0.75) * exp(-10.0 * l * l);

    float sr = galRand(s.yz);
    float si = pow(th * sr, 0.25) * 0.001;
    vec3 scol = sr * 5.0 * exp(-2.5 * l * l)
        * tanh(pow(si / s.x, 2.5))
        * mix(vec3(0.5, 0.75, 1.0), vec3(1.0, 0.75, 0.5), sr * 0.6);
    scol = clamp(scol, 0.0, 1.0);
    col += scol * smoothstep(0.0, 0.35, 1.0 - n.y);

    // 尘埃辉光：视线在触到盘面之前先穿过尘埃高度层时叠加（原相机在盘上方
    // 0.525，这里在盘上方 GAL_H，层高 h 仍是银河坐标不动）。
    float ddust = (h - GAL_H) / rdl.y;
    if(ddust < dgal)
    {
        float t = dgal - ddust;
        col += 0.7 * vec3(1.0, 0.9, 0.75) * (1.0 - exp(-2.0 * t));
    }

    return col;
}

vec3 galRender(vec3 rdl, float galT)
{
    // rdl.y≈0 与盘面平行无交点；置负走"盘面未命中"分支。
    float dgal = abs(rdl.y) < 1.0e-5 ? -1.0 : -GAL_H / rdl.y;

    vec3 col = vec3(0.0);
    if(dgal > 0.0)
    {
        vec3 p = dgal * rdl;
        // 核心在标架内 (0, -GAL_H, -GAL_D)，故盘面银河坐标 = p.xz 平移 GAL_D。
        col = galShade(vec2(p.x, p.z + GAL_D), dgal, rdl, galT);
    }

    // 核心尘埃球（原作半径 0.125，随银河整体放大 ×1/SCALE）。
    vec2 cg;
    galRaySphere(vec3(0.0), rdl, vec3(0.0, -GAL_H, -GAL_D),
        0.125 * GAL_SCALE, cg);

    float t = 0.0;
    if(dgal > 0.0 && cg.x > 0.0)
    {
        float t0 = max(dgal - cg.x, 0.0);
        float t1 = cg.y - cg.x;
        t = min(t0, t1);
    }
    else if(cg.x < cg.y)
    {
        t = cg.y - cg.x;
    }
    col += 1.7 * vec3(1.0, 0.9, 0.75) * (1.0 - exp(-1.0 * t));

    // postProcess（去 vignette）。
    col = pow(clamp(col, 0.0, 1.0), vec3(0.75));
    col = col * 0.6 + 0.4 * col * col * (3.0 - 2.0 * col);
    col = mix(col, vec3(dot(col, vec3(0.33))), -0.4);
    return col;
}
#endif // END_GALAXY_ENABLE
// 图集银河格的象限 UV（8192x4096 图集的左上格：u/v 都乘 0.5）。
// 与可见天空**同一套映射**（方向 -> 星图标架 -> 等距圆柱 -> 天极翻转 ->
// 半纹素内缩），这是"LUT 里的银河与眼睛看到的银河对齐"的前提。
vec2 endGalaxyAtlasUv(vec3 rayDir)
{
    vec2 uv = directionToEquirect(
        starSphereSampleDirectionRate(rayDir, END_SKY_SPIN_RATE));
    uv.y = 1.0 - uv.y;
    return vec2(endAtlasInset(uv.x) * 0.5, endAtlasInset(uv.y) * 0.5);
}

// 末地天空辐射 = 图集银河（底，见下面那条提亮曲线）× END_SPACE_STRENGTH
//              + 程序化银河（叠加，权重 = GUI"银河叠加强度"）
// atlasGalaxyRgb 由调用方自己采样 endSkyAtlas（各文件 binding 不同）后传进来，
// 于是可见天空与天空 LUT 的天空构成逐位一致。**只应在末地分支里调用**
// （非末地没有银河，主世界/下界一分不能变）。
vec3 endSkyGalaxyRadiance(vec3 rayDir, vec3 atlasGalaxyRgb, float galaxyTime)
{
    // 2026-10-09 图集银河提亮曲线（实测：这张子图**几乎是全黑的** —— 左上象限
    // 均值 0.0046、最亮一行 0.032、银河带约 0.01~0.03）。也就是说"银河全景"这层
    // 背景在末地实际上不可见，天空亮度几乎全部来自程序化银河 ⇒ **程序化银河的
    // 暗区（盘外、SABS 谷的暗带）就是纯黑**，看上去像"天上一个黑球/黑斑"
    // （用户报的"球形区域"；也是他先前说"银河太暗"的真正原因）。这里给图集加一条
    // 提亮曲线：pow 0.65 把 0.005 抬到 ≈0.035、银河带抬到 ≈0.11，而星点
    // （1.0）保持 1.0 不炸白 ⇒ 末地任何方向都有一层淡淡的银河/星尘底，
    // 程序化银河的暗带变成"暗带"而不是"黑洞"。想要原来那种纯黑太空就设回 1.0。
    vec3 atlasLifted = pow(clamp(atlasGalaxyRgb, 0.0, 1.0),
        vec3(END_ATLAS_GALAXY_LIFT));
    vec3 radiance = atlasLifted * END_SPACE_STRENGTH;
#if END_GALAXY_ENABLE
    galaxyApplyParams();
    vec3 galRd = starSphereSampleDirectionRate(rayDir, END_SKY_SPIN_RATE);
    // 世界 -> 银河标架：ŷ=GAL_N、ẑ=-GAL_U ⇒ 核心落在局部 (0,-GAL_H,-GAL_D)。
    vec3 rdl = vec3(-dot(galRd, cross(GAL_N, GAL_U)),
        dot(galRd, GAL_N),
        -dot(galRd, GAL_U));
    radiance += galRender(rdl, galaxyTime) * END_GALAXY_STRENGTH * GAL_MIX;
#endif
    return radiance;
}

#endif // 0 —— 2026-10-18 退役的银河实现（勿删）

#endif // END_SKY_COMMON_GLSL