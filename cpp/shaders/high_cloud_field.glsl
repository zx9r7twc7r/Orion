#ifndef HIGH_CLOUD_FIELD_GLSL
#define HIGH_CLOUD_FIELD_GLSL

#if 0
const float HC_PI = 3.14159265359;
const float HC_EXTINCTION = 0.0010;
const float HC_BASE_FREQ = 0.0000055;
const float HC_OCT2 = 7.5;
const float HC_OCT_DETAIL = 60.0;
const float HC_ANISO = 0.42;
const float HC_MAX_DISTANCE = 200000.0;
const float HC_MIN_VIEW_COS = 0.05;
const float HC_MIN_LIGHT_COS = 0.15;
const float HC_DIRECT_GAIN = 0.80;
const float HC_SKY_GAIN = 1.50;

float hcPhase(float c, float g)
{
    float g2 = g * g;
    float denom = max(1.0 + g2 - 2.0 * g * c, 1.0e-4);
    return (1.0 - g2) / (4.0 * HC_PI * pow(denom, 1.5));
}

// 云壳球心：与 tyndallSphereRoots 同一约定（见 tyndall_cloud_field.glsl）。
vec3 hcSphereCenter()
{
    return vec3(
        cam.cloudParams3.x,
        cam.cloudParams0.x - TYNDALL_RADIUS,
        cam.cloudParams3.y
    );
}

// 单层形状。coord = 投影到该层基准球面的世界 XZ（格），
// altitudeFraction = 层内高度 [0,1]，cheap = 跳过细节八度（光 march 用）。
// 返回 [0, inf) 的单位密度。
float highCloudShape(
    vec2 coord,
    float altitudeFraction,
    float coverage,
    float size,
    float detailStrength,
    float curlStrength,
    vec2 wind,
    float footprint,
    bool cheap)
{
    float scale = max(size, 0.05);
    vec2 p = coord + wind;
    vec2 pAniso = vec2(p.x * HC_ANISO, p.y);

    float f0 = HC_BASE_FREQ / scale;
    float f1 = f0 * HC_OCT2;
    float f2 = f0 * HC_OCT_DETAIL;

    float lod1 = tyndallNoiseLod(footprint, f1, 64.0);
    vec3 curl = textureLod(cloudCurlNoise, vec3(pAniso * f1, 0.5), lod1).xyz
        * 2.0 - 1.0;
    vec2 warp = curl.xy * curlStrength;

    float a = textureLod(
        cloudShapeNoise,
        vec3(pAniso * f0 + warp * 0.35, 0.29),
        tyndallNoiseLod(footprint, f0, 128.0)
    ).r;
    float b = textureLod(
        cloudShapeNoise,
        vec3(pAniso * f1 + warp * 0.90 + 0.31, 0.71),
        tyndallNoiseLod(footprint, f1, 128.0)
    ).r;
    float shape = a * 0.72 + b * 0.28;

    float threshold = mix(0.82, 0.16, clamp(coverage, 0.0, 1.0));
    float density = smoothstep(threshold, threshold + 0.16, shape);
    if(density <= 0.0) return 0.0;

    if(!cheap && detailStrength > 0.001)
    {
        float lod2 = tyndallNoiseLod(footprint, f2, 64.0);
        vec3 dc = textureLod(cloudCurlNoise, vec3(pAniso * f2, 0.5), lod2).xyz
            * 2.0 - 1.0;
        float d1 = textureLod(
            cloudShapeNoise,
            vec3(pAniso * f2 + dc.xy * curlStrength * 0.60, 0.47),
            lod2
        ).r;
        float d2 = textureLod(
            cloudShapeNoise,
            vec3(pAniso * (f2 * 2.3) + dc.xy * curlStrength, 0.83),
            tyndallNoiseLod(footprint, f2 * 2.3, 128.0)
        ).r;
        density -= (d1 * 0.70 + d2 * 0.30) * detailStrength * 0.35;
    }

    density = max(density, 0.0);
    density *= mix(0.30, 1.0, smoothstep(0.0, 0.65, altitudeFraction))
        * (1.0 - smoothstep(0.78, 1.0, altitudeFraction));
    return max(density, 0.0);
}

// 单层：球壳求交 → 单次采样 → 解析薄板光学厚 → 可选光 march。
// 返回 vec4(预乘散射 rgb, 透射率)；hit = 是否命中。
// lp1 = (覆盖率, 密度, 高度, 水平尺寸)；lp2 = (细节, 卷曲, 风速, 风向)。
vec4 highCloudLayer(
    vec3 origin,
    vec3 direction,
    vec3 lightDir,
    float pixelAngle,
    vec4 lp1,
    vec4 lp2,
    float thickness,
    float brightness,
    int lightSteps,
    float sunLevel,
    vec3 sunTint,
    vec3 skyColor,
    out float hitDistance,
    out bool hit)
{
    hit = false;
    hitDistance = 1.0e6;

    float coverage = lp1.x;
    if(coverage <= 0.001) return vec4(0.0, 0.0, 0.0, 1.0);

    float altitude = cam.cloudParams0.y + lp1.z;
    float radiusInner = TYNDALL_RADIUS + altitude;

    float radiusMid = radiusInner + 0.5 * thickness;
    vec2 midRoots;
    if(!tyndallSphereRoots(origin, direction, radiusMid, midRoots))
        return vec4(0.0, 0.0, 0.0, 1.0);
    float t = midRoots.x > 0.0 ? midRoots.x : midRoots.y;
    if(t <= 0.0 || t > HC_MAX_DISTANCE) return vec4(0.0, 0.0, 0.0, 1.0);
#if 0
    vec2 roots;
    if(!tyndallSphereRoots(origin, direction, radiusInner + thickness, roots))
        return vec4(0.0, 0.0, 0.0, 1.0);
    float t = max(roots.x, 0.0);
    if(roots.y <= t || t > HC_MAX_DISTANCE) return vec4(0.0, 0.0, 0.0, 1.0);
#endif

    float windAngle = radians(lp2.w);
    vec2 wind = vec2(cos(windAngle), sin(windAngle)) * lp2.z * tyndallTime();

    vec3 center = hcSphereCenter();
    vec3 samplePos = origin + direction * t;
    vec3 radial = samplePos - center;
    float rl = max(length(radial), 1.0);
    float altitudeFraction = clamp(
        (rl - radiusInner) / max(thickness, 1.0), 0.0, 1.0);
    vec2 coord = center.xz + radial.xz * (radiusInner / rl);

    float footprint = t * max(pixelAngle, 0.0);
    float d = highCloudShape(
        coord,
        altitudeFraction,
        coverage,
        lp1.w,
        lp2.x,
        lp2.y,
        wind,
        footprint,
        false
    );
    if(d <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);

    float extinction = HC_EXTINCTION * lp1.y;
    float viewOD = extinction * d * thickness / max(abs(direction.y), HC_MIN_VIEW_COS);
    float trans = exp(-max(viewOD, 0.0));
    float opacity = 1.0 - trans;
    if(opacity < 0.002) return vec4(0.0, 0.0, 0.0, 1.0);
#if 0
    float scatterIntegral = opacity / max(extinction, 1.0e-4);
#endif
    float scatterIntegral = opacity;

    float lightOD;
    if(lightSteps > 0)
    {
        float range = min(thickness / max(abs(lightDir.y), HC_MIN_LIGHT_COS), 40000.0);
        const float growth = 1.5;
        float coeff
            = (growth - 1.0) / (pow(growth, float(lightSteps)) - 1.0) / growth;
        float stepLen = range * coeff;
        vec3 lp = samplePos;
        float w = stepLen;
        lightOD = 0.0;
        for(int s = 0; s < lightSteps; ++s)
        {
            lp += lightDir * w;
            vec3 lrad = lp - center;
            float lrl = max(length(lrad), 1.0);
            float lfrac = (lrl - radiusInner) / max(thickness, 1.0);
            if(lfrac > 0.0 && lfrac < 1.0)
            {
                vec2 lcoord = center.xz + lrad.xz * (radiusInner / lrl);
                lightOD += highCloudShape(
                               lcoord,
                               lfrac,
                               coverage,
                               lp1.w,
                               lp2.x,
                               lp2.y,
                               wind,
                               footprint,
                               true
                           )
                    * w;
            }
            w *= growth;
        }
        lightOD *= extinction;
    }
    else
    {
        lightOD = extinction * d * thickness * 0.60 / max(abs(lightDir.y), HC_MIN_LIGHT_COS);
    }

    float cosTheta = dot(direction, lightDir);
    float powder = mix(
        8.0 * (1.0 - exp(-40.0 * d)),
        1.0,
        pow(clamp(cosTheta * 0.5 + 0.5, 0.0, 1.0), 1.5)
    );
    float phase = mix(hcPhase(cosTheta, 0.60), hcPhase(cosTheta, -0.15), 0.25);

    vec3 direct = sunTint * sunLevel * exp(-max(lightOD, 0.0)) * phase * powder
        * HC_DIRECT_GAIN;
    vec3 skyLit = skyColor * HC_SKY_GAIN
        * exp(-extinction * d * thickness * 0.33);
    vec3 rgb = (direct + skyLit) * scatterIntegral * brightness;

    hit = true;
    hitDistance = t;
    return vec4(rgb, trans);
}

// 高层平面云总入口。direction 与 lightDir 必须已归一化。
// pixelAngle = 2·|projInverse[1][1]|/屏幕高度（每像素角尺寸）；传 0 表示不做
// 距离预过滤（反射路径沿用旧档）。
vec4 sampleHighCloud(
    vec3 origin,
    vec3 direction,
    vec3 lightDir,
    float pixelAngle,
    out float distance)
{
    distance = 1.0e6;
    vec4 hc0 = cam.highCloudParams0;
    if(hc0.x < 0.5 || abs(cam.weatherParams.z - 1.0) >= 0.25)
        return vec4(0.0, 0.0, 0.0, 1.0);

    float brightness = hc0.y;
    float thickness = max(hc0.z, 1.0);
    int lightSteps = clamp(int(hc0.w + 0.5), 0, 8);

    float elevation = sin(fract(cam.historyParams.w) * 6.2831853) * 0.819152;
    vec3 sunTint = mix(
        vec3(1.0, 0.66, 0.40),
        vec3(0.91, 0.95, 1.0),
        smoothstep(0.0, 0.5, elevation)
    );
    float daylight = mix(0.012, 1.0, smoothstep(-0.10, 0.15, elevation));
    float fire = (1.0 - smoothstep(0.04, 0.22, elevation)) * 1.8;
    sunTint += vec3(1.0, 0.12, 0.015) * fire;
    float rain = clamp(cam.weatherParams.x, 0.0, 1.0);
    float sunLevel = daylight * mix(1.0, 0.55, rain);

    vec3 skyColor = mix(
        mix(vec3(0.10, 0.14, 0.26), vec3(0.62, 0.76, 1.0),
            smoothstep(-0.12, 0.18, elevation)),
        vec3(0.42, 0.34, 0.52),
        (1.0 - smoothstep(0.0, 0.22, abs(elevation))) * 0.6
    );

    vec4 lp1[2] = vec4[2](cam.highCloudParams1, cam.highCloudParams3);
    vec4 lp2[2] = vec4[2](cam.highCloudParams2, cam.highCloudParams4);

    vec4 layer[2];
    float layerDistance[2];
    bool layerHit[2];
    for(int L = 0; L < 2; ++L)
    {
        layer[L] = highCloudLayer(
            origin,
            direction,
            lightDir,
            pixelAngle,
            lp1[L],
            lp2[L],
            thickness,
            brightness,
            lightSteps,
            sunLevel,
            sunTint,
            skyColor,
            layerDistance[L],
            layerHit[L]
        );
    }

    int front = -1;
    int back = -1;
    if(layerHit[0] && layerHit[1])
    {
        bool zeroNearer = layerDistance[0] <= layerDistance[1];
        front = zeroNearer ? 0 : 1;
        back = zeroNearer ? 1 : 0;
    }
    else if(layerHit[0]) front = 0;
    else if(layerHit[1]) front = 1;
    if(front < 0) return vec4(0.0, 0.0, 0.0, 1.0);
    if(back < 0)
    {
        distance = layerDistance[front];
        return vec4(layer[front].rgb, layer[front].a);
    }

    distance = layerDistance[front];
    vec3 scattering = layer[front].rgb + layer[front].a * layer[back].rgb;
    float transmittance = layer[front].a * layer[back].a;
    return vec4(scattering, transmittance);
}
#endif

const float PLANE_CLOUD_PI = 3.14159265359;
const float PLANE_CLOUD_OCTAVE_SCALE = 2.8;
const float PLANE_CLOUD_OCTAVE_FADE = 0.45;
#ifndef PLANE_CLOUD_NOISE_FREQ
#define PLANE_CLOUD_NOISE_FREQ 0.0001
#endif
const float PLANE_CLOUD_NOISE_SLICE = 0.37;
const float PLANE_CLOUD_REGION_FREQ = 0.00012;
const float PLANE_CLOUD_MAX_DISTANCE = 300000.0;
const int PLANE_CLOUD_MAX_OCTAVES = 8;
const float PLANE_CLOUD_PHASE_G = 0.60;
const float PLANE_CLOUD_PHASE_GAIN = 3.2;
const float PLANE_CLOUD_POWDER = 2.0;
const float PLANE_CLOUD_AMBIENT_GAIN = 0.45;
const vec3 PLANE_CLOUD_AIR_OPTICAL = vec3(0.42, 0.20, 0.10);

// 2D 噪声：cloudShapeNoise 的 z 切片。lod 由 tyndallNoiseLod 按像素脚印给出。
float planeCloudNoise2D(vec2 p, float lod)
{
    return textureLod(cloudShapeNoise, vec3(p, PLANE_CLOUD_NOISE_SLICE), lod).r;
}

// 单瓣 Henyey-Greenstein（与体积云的 hgPhase 同式；此处独立定义，避免依赖
// 调用方在 include 之前已声明 hgPhase）。
float planeCloudPhase(float cosine, float g)
{
    float g2 = g * g;
    float denom = max(1.0 + g2 - 2.0 * g * cosine, 1.0e-4);
    return (1.0 - g2) / (4.0 * PLANE_CLOUD_PI * pow(denom, 1.5));
}

vec4 sampleHighCloud(
    vec3 origin,
    vec3 direction,
    vec3 lightDir,
    float pixelAngle,
    out float distance)
{
    distance = 1.0e6;

    vec4 hc0 = cam.highCloudParams0;
    if(hc0.x < 0.5)
        return vec4(0.0, 0.0, 0.0, 1.0);
    if(abs(cam.weatherParams.z - 1.0) >= 0.25)
        return vec4(0.0, 0.0, 0.0, 1.0);

    float heightOffset = hc0.y;
    float scale = max(hc0.z, 0.01);
    float speed = hc0.w;

    vec4 hc1 = cam.highCloudParams1;
    float densityKnob = max(hc1.x, 0.0);
    float coverage = clamp(hc1.y, 0.0, 1.0);
    int octaves = clamp(int(hc1.z + 0.5), 1, PLANE_CLOUD_MAX_OCTAVES);
    float fadeDistance = max(hc1.w, 1.0);
    float brightness = max(cam.highCloudParams2.x, 0.0);
    if(densityKnob <= 0.0 || brightness <= 0.0)
        return vec4(0.0, 0.0, 0.0, 1.0);

    float planeAltitude = cam.cloudParams0.x + cam.cloudParams0.y + heightOffset;
    float shellRadius = TYNDALL_RADIUS + (planeAltitude - cam.cloudParams0.x);
    vec2 roots;
    if(!tyndallSphereRoots(origin, direction, shellRadius, roots))
        return vec4(0.0, 0.0, 0.0, 1.0);
    float t = roots.x > 0.0 ? roots.x : roots.y;
    if(t <= 0.0 || t > PLANE_CLOUD_MAX_DISTANCE)
        return vec4(0.0, 0.0, 0.0, 1.0);

    vec3 hitPos = origin + direction * t;

    vec2 wind = vec2(1.0, 0.5) * (speed * tyndallTime() * 20.0);
    vec2 cloudPos = hitPos.xz + wind;
    vec2 octaveWind = wind * PLANE_CLOUD_OCTAVE_SCALE;
    float frequency = PLANE_CLOUD_NOISE_FREQ / scale;
    float lod = tyndallNoiseLod(t * max(pixelAngle, 0.0), frequency, 128.0);

    float accum = 0.0;
    float weight = 1.0;
    float weights = 0.0;
    for(int i = 0; i < PLANE_CLOUD_MAX_OCTAVES; ++i)
    {
        if(i >= octaves) break;
        accum += weight * planeCloudNoise2D(cloudPos * frequency, lod);
        cloudPos = cloudPos * PLANE_CLOUD_OCTAVE_SCALE + octaveWind;
        weights += weight;
        weight *= PLANE_CLOUD_OCTAVE_FADE;
    }
    accum /= max(weights, 1.0e-4);

    float region = tyndallRegion(vec3(hitPos.x * PLANE_CLOUD_REGION_FREQ,
                                      0.37,
                                      hitPos.z * PLANE_CLOUD_REGION_FREQ)).x;
    accum *= mix(0.55, 1.35, region);

    float cloudAmount = clamp(accum + coverage - 1.0, 0.0, 1.0);
    if(cloudAmount <= 0.0)
        return vec4(0.0, 0.0, 0.0, 1.0);

    cloudAmount *= exp2(-t / fadeDistance);
    if(cloudAmount <= 0.0)
        return vec4(0.0, 0.0, 0.0, 1.0);
    float opacity = 1.0 - exp2(-densityKnob * cloudAmount);
    float alpha = opacity * opacity;
    if(alpha < 0.0015)
        return vec4(0.0, 0.0, 0.0, 1.0);
    distance = t;

    float elevation = sin(fract(cam.historyParams.w) * 6.2831853) * 0.819152;
    vec3 sunTint = mix(vec3(1.0, 0.66, 0.40), vec3(0.91, 0.95, 1.0),
                       smoothstep(0.0, 0.5, elevation));
    float daylight = mix(0.012, 1.0, smoothstep(-0.10, 0.15, elevation));
    float fire = (1.0 - smoothstep(0.04, 0.22, elevation)) * 1.8;
    sunTint += vec3(1.0, 0.12, 0.015) * fire;
    float rain = clamp(cam.weatherParams.x, 0.0, 1.0);
    float sunLevel = daylight * mix(1.0, 0.55, rain);

    vec3 skyColor = mix(
        mix(vec3(0.10, 0.14, 0.26), vec3(0.62, 0.76, 1.0),
            smoothstep(-0.12, 0.18, elevation)),
        vec3(0.42, 0.34, 0.52),
        (1.0 - smoothstep(0.0, 0.22, abs(elevation))) * 0.6);

    vec3 localUp = normalize(hitPos -
        vec3(cam.cloudParams3.xy, cam.cloudParams0.x - TYNDALL_RADIUS));
    float sunPathCos = max(dot(lightDir, localUp), 0.02);
    vec3 airExtinction = exp(-PLANE_CLOUD_AIR_OPTICAL * (1.0 / sunPathCos - 1.0));

    float viewCos = clamp(dot(direction, lightDir), -1.0, 1.0);
    float phase = planeCloudPhase(viewCos, PLANE_CLOUD_PHASE_G);
    float powder = 1.0 - exp(-PLANE_CLOUD_POWDER * cloudAmount);

    vec3 direct = sunTint * airExtinction * sunLevel *
        phase * PLANE_CLOUD_PHASE_GAIN * powder;
    vec3 ambient = skyColor * PLANE_CLOUD_AMBIENT_GAIN;
    vec3 color = (direct + ambient) * brightness;

    return vec4(color * alpha, 1.0 - alpha);
}

#endif
