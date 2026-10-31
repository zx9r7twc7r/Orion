#ifndef END_SKY_COMMON_GLSL
#define END_SKY_COMMON_GLSL

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
    if(any(isnan(radiance)) || any(isinf(radiance)))
    {
        return vec3(0.0);
    }
    return max(radiance, vec3(0.0));
}
#endif
// 末地天空辐射的唯一入口（当前 = Star Nest）。可见天空 / LUT / 球体反射都走它。
vec3 endSkyRadiance(vec3 rayDir, float skyTime)
{
#if END_STAR_NEST_ENABLE
    return endStarNestRadiance(rayDir, skyTime);
#else
    return vec3(0.0);
#endif
}

#if 0

#ifndef END_ATLAS_GALAXY_LIFT
#define END_ATLAS_GALAXY_LIFT 0.65
#endif

const float END_ATLAS_INSET = 2.5e-4;
float endAtlasInset(float x)
{
    return x * (1.0 - 2.0 * END_ATLAS_INSET) + END_ATLAS_INSET;
}

#ifndef END_GALAXY_ENABLE
#define END_GALAXY_ENABLE 1
#endif

#if END_GALAXY_ENABLE
#define END_GALAXY_STRENGTH 1.5
#define END_GALAXY_CORE_GLOW 0.9
#ifndef END_GALAXY_OCTAVES
#define END_GALAXY_OCTAVES 11
#endif

#define END_GALAXY_SCALE    0.85
#define END_GALAXY_TILT_DEG 30.0
#define END_GALAXY_YAW_DEG  -45.0
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
        gMix = clamp(cam.galaxyParams.w, 0.0, 1.0);
    }
    GAL_SCALE = gScale;
    GAL_MIX = gMix;
    GAL_H = 0.525 * gScale;
    GAL_D = 1.5 * gScale;
    float gElev = radians(90.0 - gTilt);
    float gAz = radians(gYaw);
    float cE = cos(gElev);
    GAL_N = normalize(vec3(cE * cos(gAz), sin(gElev), cE * sin(gAz)));
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
    float dgal = abs(rdl.y) < 1.0e-5 ? -1.0 : -GAL_H / rdl.y;

    vec3 col = vec3(0.0);
    if(dgal > 0.0)
    {
        vec3 p = dgal * rdl;
        col = galShade(vec2(p.x, p.z + GAL_D), dgal, rdl, galT);
    }

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

    col = pow(clamp(col, 0.0, 1.0), vec3(0.75));
    col = col * 0.6 + 0.4 * col * col * (3.0 - 2.0 * col);
    col = mix(col, vec3(dot(col, vec3(0.33))), -0.4);
    return col;
}
#endif
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
    vec3 atlasLifted = pow(clamp(atlasGalaxyRgb, 0.0, 1.0),
        vec3(END_ATLAS_GALAXY_LIFT));
    vec3 radiance = atlasLifted * END_SPACE_STRENGTH;
#if END_GALAXY_ENABLE
    galaxyApplyParams();
    vec3 galRd = starSphereSampleDirectionRate(rayDir, END_SKY_SPIN_RATE);
    vec3 rdl = vec3(-dot(galRd, cross(GAL_N, GAL_U)),
        dot(galRd, GAL_N),
        -dot(galRd, GAL_U));
    radiance += galRender(rdl, galaxyTime) * END_GALAXY_STRENGTH * GAL_MIX;
#endif
    return radiance;
}

#endif
#endif