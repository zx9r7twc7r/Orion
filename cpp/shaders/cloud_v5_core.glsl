#ifndef CLOUD_HP_CORE_GLSL
#define CLOUD_HP_CORE_GLSL

const float HP_WEATHER_TILE = 96000.0;
const float HP_BASE_TILE    = 8000.0;
const float HP_EROSION_TILE = 1400.0;
#define HP_DENSITY_THRESHOLD     0.1
#define HP_DENSITY_BASE_THRESHOLD 0.41
#define HP_PRESENCE_SOFT         0.10
#define HP_PHIFWD_OMEGA0         0.999
#define HP_PHIFWD_KAPPA_SCALE    sqrt(3.0 * (1.0 - HP_PHIFWD_OMEGA0))
#define HP_CONE_RATIO            2.0
#define HP_CONE_MIN_STEP         5.0
#define HP_CONE_MAX_DISTANCE     6000.0
struct HPCloudConfig
{
    float bottomHeight;
    float topHeight;
    float planetRadius;
    float coverage;
    float densityMult;
    float noiseScale;
    float detailScale;
    float detailStrength;
    float typeBias;
    int   viewSteps;
    int   lightSteps;
    float shadowDistance;
    int   msOctaves;
    float msAttenuation;
    float msContribution;
    float msEccentricity;
    float luminanceScale;
    float forwardEcc;
    float backwardEcc;
    float phaseMix;
    vec3  sunDir;
    vec3  sunColor;
    vec3  ambientTop;
    vec3  ambientBottom;
    vec3  groundAlbedo;
    float groundBounce;
    float viewDistance;
    float fadeStart;
    float minTransmittance;
    float atmosphereDensity;
    float time;
    float jitter;
    vec3  cameraAlt;

    float detailStrengthCu;
    float detailStrengthTcu;
    float detailStrengthCb;
    float bottomSmoothHeight;
    float wispyEdgeWidth;
    float wispyReach;
    float wispyTopHeight;
    float wispyTopHardness;
    float wispyWeight;
    float billowyWeight;
    float loCoverTopMax;
    float loCoverTopStr;
    float covMapScale;
    float scWorleyStrength;
    float scHeightScale;
    float scCellThickStr;
    float powderIntensity;
    float aoUpwardScale;
    float phiFwdIntensity;
    float phiFwdDepthPow;
    float phiFwdDepthBias;
    float phiFwdCompress;
    float phiFwdBoundary;
    float phiFwdMSBuild;
    float scatterODScale;
    float scatterCurvePow;
    float lightErosion;
    float lightReuse;
};

// ---------------------------------------------------------------- 工具
// 出厂配置（HanPi 注释里的推荐默认值）。
// 为什么要有这个函数：GLSL 的 struct 没有成员默认值，调用方只要漏填一个字段
// 就是**未定义行为**（实测：mode 13 诊断切片漏了 covMapScale ⇒ coverage=0 ⇒
// 整屏全黑；游戏端漏了 ambientTopMult 之类则完全静默）。所有构造点一律从
// hpDefaultConfig() 起手，再覆盖自己要改的字段。
HPCloudConfig hpDefaultConfig()
{
    HPCloudConfig c;
    c.bottomHeight = 1800.0;
    c.topHeight = 3600.0;
    c.planetRadius = 6360000.0;

    c.coverage = 0.5;
    c.densityMult = 0.004;
    c.noiseScale = 1.0;
    c.detailScale = 1.0;
    c.detailStrength = 1.0;
    c.typeBias = 0.0;

    c.viewSteps = 96;
    c.lightSteps = 8;
    c.shadowDistance = 0.0;

    c.msOctaves = 2;
    c.msAttenuation = 0.5;
    c.msContribution = 0.5;
    c.msEccentricity = 0.5;
    c.luminanceScale = 1.0;

    c.forwardEcc = 0.85;
    c.backwardEcc = 0.3;
    c.phaseMix = 0.5;

    c.sunDir = vec3(0.0, 1.0, 0.0);
    c.sunColor = vec3(0.0);
    c.ambientTop = vec3(0.0);
    c.ambientBottom = vec3(0.0);
    c.groundAlbedo = vec3(0.0);
    c.groundBounce = 0.0;

    c.viewDistance = 0.0;
    c.fadeStart = 0.3;
    c.minTransmittance = 0.003;
    c.atmosphereDensity = 0.0;
    c.time = 0.0;
    c.jitter = 0.5;
    c.cameraAlt = vec3(0.0);
    c.detailStrengthCu = 0.55;
    c.detailStrengthTcu = 0.70;
    c.detailStrengthCb = 0.85;
    c.bottomSmoothHeight = 0.08;
    c.wispyEdgeWidth = 0.40;
    c.wispyReach = 0.15;
    c.wispyTopHeight = 0.75;
    c.wispyTopHardness = 2.0;
    c.wispyWeight = 1.0;
    c.billowyWeight = 1.0;
    c.loCoverTopMax = 2.2;
    c.loCoverTopStr = 1.0;
    c.covMapScale = 1.0;
    c.scWorleyStrength = 0.25;
    c.scHeightScale = 0.35;
    c.scCellThickStr = 0.6;

    c.powderIntensity = 1.0;
    c.aoUpwardScale = 1.0;
    c.phiFwdIntensity = 0.6;
    c.phiFwdDepthPow = 1.0;
    c.phiFwdDepthBias = 0.0;
    c.phiFwdCompress = 1.0;
    c.phiFwdBoundary = 0.0;
    c.phiFwdMSBuild = 1.0;
    c.scatterODScale = 0.10;
    c.scatterCurvePow = 1.0;

    c.lightErosion = 0.0;
    c.lightReuse = 2.0;
    return c;
}

float hpSaturate(float v) { return clamp(v, 0.0, 1.0); }


// HanPi DensityRemap(x, a, b, c, d)
float hpRemap(float x, float a, float b, float c, float d)
{
    float denom = b - a;
    if(abs(denom) < 1.0e-6)
        return (x < a) ? c : d;
    return (((x - a) / denom) * (d - c)) + c;
}

// HanPi HenyeyGreenstein（归一化，1/4π）
float hpHG(float cosAngle, float g)
{
    float g2 = g * g;
    return (1.0 / (4.0 * 3.14159265359)) * (1.0 - g2) /
        pow(max(1.0 + g2 - 2.0 * g * cosAngle, 1.0e-4), 1.5);
}

// HanPi PowderEffect（smoothstep(0.5, -0.5, cos)——背光侧生效）
float hpPowderEffect(float cloudDensity, float cosAngle, float intensity)
{
    float powder = 1.0 - exp(-cloudDensity * 4.0);
    powder = hpSaturate(powder * 2.0);
    return mix(1.0, mix(1.0, powder, smoothstep(0.5, -0.5, cosAngle)), intensity);
}

// 球壳求交（近交点；数值稳定写法——相机在层上俯视时 b、s 量级 3.6e6 相消）
float hpIntersectShell(float originRadius, float rayDirY, float sphereRadius)
{
    float b = originRadius * rayDirY;
    float c0 = originRadius * originRadius - sphereRadius * sphereRadius;
    float d = b * b - c0;
    if(d < 0.0) return -1.0;
    float s = sqrt(d);
    float q = -(b + (b >= 0.0 ? s : -s));
    if(abs(q) < 1.0e-9) return 0.0;
    return c0 / q;
}

float hpNormalizedHeight(vec3 pos, HPCloudConfig cfg)
{
    float h = max(cfg.topHeight - cfg.bottomHeight, 1.0);
    return (length(pos) - (cfg.planetRadius + cfg.bottomHeight)) / h;
}

float hpSlab(HPCloudConfig cfg)
{
    return max(cfg.topHeight - cfg.bottomHeight, 1.0);
}

// ---------------------------------------------------------------------------
// Profile LUT（程序化）。HanPi 的 _CloudLutTexture：UV=(lutSampleHeight,
// radialDist)，RGB=(Cu, Tcu, Cb) 三型剖面。仓内无 LUT 资产，这里按三型积云
// 的公认形状解析实现；radial 用天气图 B 通道（ScMask）驱动——云团核心
// （Cb 偏向）到边缘（Cu 偏向）剖面渐变。
// ---------------------------------------------------------------------------
// Cu 通道单独成函数：参考的 Sc 混合是 `lerp(heightGradient, profiles.r, scStr)`，
// 即在**同一个 lutSampleHeight** 上取 LUT 的 R(Cu) 通道。我们原来用
// `hpProfileLUT(lutSampleHeight, radial, 0.0)` 再算一遍（等于 lo = mix(cu,cb,radial)），
// 既多一次 6×smoothstep 的完整廓形求值（光步里每步都要付），语义也不对。
float hpProfileCu(float h)
{
    return smoothstep(0.02, 0.10, h) * (1.0 - smoothstep(0.28, 0.60, h));
}

float hpProfileLUT(float h, float radial, float type)
{
    float cu  = hpProfileCu(h);
    float tcu = smoothstep(0.02, 0.09, h) * (1.0 - smoothstep(0.42, 0.72, h));
    float cb  = smoothstep(0.01, 0.07, h) * (1.0 - smoothstep(0.55, 0.85, h));
    float radialCore = 1.0 - clamp(radial, 0.0, 1.0);
    float lo  = mix(cu, cb, radialCore);
    float gradient;
    if(type < 0.5)
        gradient = mix(lo, tcu, clamp(type * 2.0, 0.0, 1.0));
    else
        gradient = mix(tcu, cb, clamp((type - 0.5) * 2.0, 0.0, 1.0));
    return gradient;
}

void hpEvaluateCloudProperties(vec3 positionAlt, float slabH, float noiseMipOffset,
    bool simpleMode, HPCloudConfig cfg, sampler2D weatherTex, sampler3D baseTex,
    sampler3D erosionTex, out float outDensity, out float outLocalHeight)
{
    outDensity = 0.0;
    outLocalHeight = 0.0;

    float h = (length(positionAlt + vec3(0.0, cfg.planetRadius, 0.0))
        - (cfg.planetRadius + cfg.bottomHeight)) / max(slabH, 1.0);
    if(h <= 0.0 || h >= 1.0) return;

    vec2 windUV = vec2(0.862, 0.507) * (14.0 * 0.35 * cfg.time);
    vec2 weatherUV = (positionAlt.xz - windUV) / HP_WEATHER_TILE;
    vec4 weather = texture(weatherTex, weatherUV);
    float cloudType   = weather.g;
    float scAtlasMask = weather.b;

    float coverageRaw    = weather.r;
#if HP_DIAG_UNIFORM_COVERAGE
    coverageRaw = 0.62;
#endif
    float coverage       = hpSaturate(coverageRaw * cfg.coverage * cfg.covMapScale);
    float coverageHeight = hpSaturate(coverageRaw * cfg.coverage * cfg.covMapScale);

    float presT = hpSaturate((cfg.coverage - 0.10) / 0.80);
    float presCut = pow(1.0 - presT, 5.0);
    float presence = smoothstep(presCut - HP_PRESENCE_SOFT,
        presCut + HP_PRESENCE_SOFT, coverageRaw);
    if(presence <= 0.002)
        return;
    float scStr = cfg.scWorleyStrength * scAtlasMask;
    float scCell = hpSaturate(weather.b * 1.25);

    float scCoverage = hpSaturate(coverageRaw * cfg.coverage * (cfg.covMapScale * 1.1));
    coverage = mix(coverage, scCoverage * scCell, scStr);

    float loCoverTopScale = 1.0 + 1.2 * hpSaturate(coverageHeight);
    float heightForLUT = h / (1.0 + (loCoverTopScale - 1.0) * h);

    float scCompressedHeight = hpSaturate(h / max(cfg.scHeightScale, 0.05));
    float lutSampleHeight = mix(heightForLUT, scCompressedHeight, scStr);
    outLocalHeight = lutSampleHeight;

    float hpRadial = 0.5;
    float heightGradient = hpProfileLUT(lutSampleHeight, hpRadial, cloudType);
    if(scStr > 0.0)
        heightGradient = mix(heightGradient, hpProfileCu(lutSampleHeight), scStr);
    heightGradient *= 1.0 - smoothstep(0.82, 1.0, h);

    vec3 windOffset = vec3(windUV.x, 0.0, windUV.y);
    float minNoiseScale = HP_BASE_TILE / (6.0 * slabH);
    float noiseScaleClamped = max(cfg.noiseScale, minNoiseScale);
    vec3 baseUVW = positionAlt / (HP_BASE_TILE / max(noiseScaleClamped, 0.02))
        + windOffset / HP_BASE_TILE;
    float baseNoiseR = textureLod(baseTex, baseUVW, max(noiseMipOffset, 0.0)).r;
    float baseShape = pow(abs(baseNoiseR), 0.6);

    float bottomNoiseFade = pow(hpSaturate(lutSampleHeight
        / max(cfg.bottomSmoothHeight, 0.001)), 2.0);
    baseShape = mix(1.0, baseShape, bottomNoiseFade);

    float billowy = 0.0;
    float wispy = 0.0;
    if(!simpleMode)
    {
        float detailTile = HP_EROSION_TILE / max(cfg.detailScale, 0.02);
        vec3 detailWindOffset = vec3(windUV.x, 0.0, windUV.y) * 0.9
            + vec3(0.0, cfg.time * 6.0, 0.0);
        vec3 detailUVW = vec3(positionAlt.x, -positionAlt.y, positionAlt.z) / detailTile
            + detailWindOffset / detailTile;
        vec4 d = texture(erosionTex, detailUVW);
        billowy = (d.b * 0.8 + d.a * 0.9) * bottomNoiseFade * clamp(cfg.billowyWeight, 0.0, 2.0);
        wispy   = (d.r * 0.7 + d.g * 0.8) * bottomNoiseFade * clamp(cfg.wispyWeight, 0.0, 2.0);
    }
    float base_cloud = 0.0;
    {
        float scCellSafe = max(scCell, 0.001);
        float scCellShaped = scCellSafe * scCellSafe;
        float scCellFactor = mix(1.0, scCellShaped, 0.6);
        float heightClip = mix(1.0, scCellFactor, scStr);

        float threshold = HP_DENSITY_BASE_THRESHOLD;

        float detailStrength;
        if(cloudType < 0.5)
            detailStrength = mix(cfg.detailStrengthCu, cfg.detailStrengthTcu, cloudType * 2.0);
        else
            detailStrength = mix(cfg.detailStrengthTcu, cfg.detailStrengthCb,
                (cloudType - 0.5) * 2.0);
        detailStrength = mix(detailStrength, 0.45, scStr);
        detailStrength *= clamp(cfg.detailStrength, 0.0, 2.0);

        float erosionBillowy = min(billowy * detailStrength, 0.95);
        float erosionWispy   = min(wispy   * detailStrength, 0.95);
        float erodedBillowy = hpSaturate(hpRemap(baseShape, erosionBillowy, 1.0, 0.0, 1.0));
        float erodedWispy   = hpSaturate(hpRemap(baseShape, erosionWispy,   1.0, 0.0, 1.0));

        float wispyThreshold = threshold - clamp(cfg.wispyReach, 0.0, 0.5);
        float edgeSoftness = 0.25;
        float densityBillowy = hpSaturate(hpRemap(erodedBillowy, threshold,
            threshold + edgeSoftness, 0.0, 1.0));
        float densityWispy = hpSaturate(hpRemap(erodedWispy, wispyThreshold,
            wispyThreshold + edgeSoftness, 0.0, 1.0));

        densityBillowy *= heightGradient;
        densityWispy   *= heightGradient;

        float wispyT = hpSaturate((h - clamp(cfg.wispyTopHeight, 0.0, 0.99))
            / max(1.0 - clamp(cfg.wispyTopHeight, 0.0, 0.99), 0.001));
        float wispyHeightMask = pow(1.0 - wispyT, max(cfg.wispyTopHardness * 10.0, 0.01));
        densityWispy *= wispyHeightMask;

        float billowyByShape = smoothstep(0.0, max(cfg.wispyEdgeWidth, 0.01), densityBillowy);
        base_cloud = mix(densityWispy, densityBillowy, billowyByShape) * heightClip;

        float typeDensityMult;
        if(cloudType < 0.5)
            typeDensityMult = mix(1.0, 1.25, cloudType * 2.0);
        else
            typeDensityMult = mix(1.25, 1.5, (cloudType - 0.5) * 2.0);
        base_cloud *= typeDensityMult;
    }

    float density = max(0.0, base_cloud);
    density *= presence;
    density *= 1.0 - hpSaturate(0.35 * (1.0 - pow(hpSaturate(coverage), 2.0)));

    density *= cfg.densityMult;

    if(cfg.viewDistance > 1.0)
    {
        float camDist = length(positionAlt - cfg.cameraAlt);
        float fadeT = smoothstep(cfg.viewDistance * clamp(cfg.fadeStart, 0.0, 0.99),
            cfg.viewDistance, camDist);
        density *= 1.0 - fadeT;
    }

    outDensity = max(0.0, density);
}

// [HanPi] HP_EvaluateHpHpPhiFwdTopHeightProxy —— 用低云天气图重建的"柱厚度代理"，
// 只服务于 phi_fwd 的边界受光判断与云底软高度，不参与密度本身。
// 参考用 LoCoverTopMax/LoCoverTopStr 对 coverage 做一次 Möbius 抬升后归一化；
// 我们原来写死 `coverageHeight * 1.2 / 2.0`（只对 max=2.2/str=1 的默认档近似）。
float hpPhiFwdTopProxy(vec2 worldXZ, HPCloudConfig cfg, sampler2D weatherTex)
{
    vec2 windUV = vec2(0.862, 0.507) * (14.0 * 0.35 * cfg.time);
    vec2 uv = (worldXZ - windUV) / HP_WEATHER_TILE;
    float ch = hpSaturate(texture(weatherTex, uv).r * cfg.coverage * cfg.covMapScale);
    float scale = mix(1.0, max(cfg.loCoverTopMax, 1.0),
        ch * clamp(cfg.loCoverTopStr, 0.0, 1.0));
    return hpSaturate(ch * scale / max(cfg.loCoverTopMax, 1.0));
}

vec3 hpEvaluateSunLuminance(vec3 positionAlt, float currentLocalHeight,
    HPCloudConfig cfg, vec3 sunColor, float phaseFunction[3],
    sampler2D weatherTex, sampler3D baseTex, sampler3D erosionTex,
    out float lightExtinctionOD, out float phiFwd)
{
    float totalLightDistance = 0.0;
    vec3 luminance = vec3(0.0);
    lightExtinctionOD = 0.0;
    phiFwd = 0.0;

    float originRadius = length(positionAlt + vec3(0.0, cfg.planetRadius, 0.0));
    float topRadius = cfg.planetRadius + cfg.topHeight;
    float b = originRadius * cfg.sunDir.y;
    float c0 = originRadius * originRadius - topRadius * topRadius;
    float disc = max(b * b - c0, 0.0);
    totalLightDistance = -b + sqrt(disc);


    {
        float coverDist = clamp(totalLightDistance, 0.0, HP_CONE_MAX_DISTANCE);
        int numSteps = clamp(cfg.lightSteps, 4, 12);
        float r = HP_CONE_RATIO;
        float w0 = coverDist * (r - 1.0) / max(pow(r, float(numSteps)) - 1.0, 1.0e-4);
        w0 = max(w0, HP_CONE_MIN_STEP);

        float extinctionSum = 0.0;
        float kappaODSum = 0.0;
        float T_cum = 1.0;
        float curWidth = w0;
        float cumDist = 0.0;

        float slabH = hpSlab(cfg);
        float topProxy = 0.0;
        if(cfg.phiFwdIntensity > 0.0)
            topProxy = hpPhiFwdTopProxy(positionAlt.xz, cfg, weatherTex);
        float boundaryConfidence = 1.0;
        if(cfg.phiFwdBoundary > 0.01)
        {
            float sampleStep = clamp(HP_WEATHER_TILE * 0.001, 25.0, 200.0);
            float hN[4];
            vec2 offs[4] = vec2[4](vec2(-sampleStep, 0.0), vec2(sampleStep, 0.0),
                vec2(0.0, -sampleStep), vec2(0.0, sampleStep));
            for(int k = 0; k < 4; ++k)
                hN[k] = hpPhiFwdTopProxy(positionAlt.xz + offs[k], cfg, weatherTex);
            float dHdx = (hN[1] - hN[0]) * slabH / max(2.0 * sampleStep, 1.0);
            float dHdz = (hN[3] - hN[2]) * slabH / max(2.0 * sampleStep, 1.0);
            vec3 topNormalWS = normalize(vec3(-dHdx, 1.0, -dHdz));
            float nDotL = dot(topNormalWS, cfg.sunDir);
            float boundaryLitWarp = hpSaturate((nDotL + 0.5) / 1.5);
            boundaryConfidence = mix(1.0, boundaryLitWarp,
                clamp(cfg.phiFwdBoundary, 0.0, 1.0));
        }

        float phiFwdBottomHeight = currentLocalHeight + cfg.phiFwdDepthBias;
        float phiFwdBottomSoftHeight = max(cfg.bottomSmoothHeight * mix(1.0, 4.0, topProxy), 0.001);
        float srcBottomConfidence = (cfg.phiFwdDepthPow > 0.0)
            ? 1.0 - exp(-max(phiFwdBottomHeight, 0.0) / phiFwdBottomSoftHeight
                * cfg.phiFwdDepthPow)
            : 1.0;
        float srcConfidence = boundaryConfidence * srcBottomConfidence;

        for(int j = 0; j < 12; ++j)
        {
            if(j >= numSteps) break;
            float stepWidth = min(curWidth, coverDist - cumDist);
            if(stepWidth <= 0.0) break;

            float dist = cumDist + stepWidth * 0.5;
            vec3 samplePosWS = positionAlt + cfg.sunDir * dist;

            float lightDensity; float lightLocalHeight;
            float lightMip = float(j) / max(float(numSteps - 1), 1.0) * 3.0;
            hpEvaluateCloudProperties(samplePosWS, slabH, lightMip,
                cfg.lightErosion <= 0.5, cfg,
                weatherTex, baseTex, erosionTex, lightDensity, lightLocalHeight);

            float sigma_t = lightDensity;
            float sigma_s = sigma_t * HP_PHIFWD_OMEGA0;
            float localOD = sigma_t * stepWidth;
            float qSrc = sigma_s * stepWidth;
            float invD = sigma_t;
            float kappaStep = localOD * HP_PHIFWD_KAPPA_SCALE;
            float kappaToCenter = kappaODSum + kappaStep * 0.5;
            float perSrcExp = exp(-kappaToCenter);
            float msBuild = 1.0 - exp(-(extinctionSum + localOD * 0.5) * max(cfg.phiFwdMSBuild, 0.0));
            float invR = 1.0 / max(dist, stepWidth * 0.5);
            phiFwd += T_cum * qSrc * invD * srcConfidence * msBuild * perSrcExp * invR;

            extinctionSum += localOD;
            kappaODSum += kappaStep;
            float T_step = exp(-localOD * (1.0 - HP_PHIFWD_OMEGA0));
            T_cum *= T_step;

            if(extinctionSum > 25.0) break;

            cumDist += stepWidth;
            curWidth *= HP_CONE_RATIO;
        }
        lightExtinctionOD = extinctionSum;

        vec3 extinction = vec3(1.0) * extinctionSum;
        for(int o = 0; o < 3; ++o)
        {
            if(o >= cfg.msOctaves) break;
            float attFactor = pow(clamp(cfg.msAttenuation, 0.05, 1.0), float(o));
            float conFactor = pow(clamp(cfg.msContribution, 0.05, 1.0), float(o));
            vec3 transmittance = exp(-extinction * attFactor);
            luminance += transmittance * sunColor * phaseFunction[o] * conFactor;
        }
    }

    return luminance * max(cfg.luminanceScale, 0.0);
}

void hpEvaluateCloudStep(float density, float localHeight, float slabH, float h,
    vec3 positionAlt, float stepSize, float relativeRayDistance,
    vec3 totalLuminance, float lightExtinctionOD, float phiFwd,
    HPCloudConfig cfg, inout vec3 inScattering, inout float transmittance)
{
    float extinction = density;
    float stepTransmittance = exp(-extinction * stepSize);
    float scatterOD = extinction * HP_PHIFWD_OMEGA0 * stepSize;
    float scatterSourceAmount = 1.0 - exp(-scatterOD / max(cfg.scatterODScale, 0.001));
    scatterSourceAmount = pow(hpSaturate(scatterSourceAmount),
        max(cfg.scatterCurvePow, 0.01));

    vec3 integScatt = totalLuminance * scatterSourceAmount;

    if(cfg.phiFwdIntensity > 0.0)
    {
        float phiFwdScalar = phiFwd * max(cfg.phiFwdIntensity, 0.0);
        float phiFwdMapped = (cfg.phiFwdCompress > 0.0)
            ? (1.0 - exp(-phiFwdScalar * cfg.phiFwdCompress)) / cfg.phiFwdCompress
            : phiFwdScalar;
        vec3 phiFwdLuminance = phiFwdMapped * cfg.sunColor;
        integScatt += phiFwdLuminance - phiFwdLuminance * stepTransmittance;
    }

    float sinElev = max(cfg.sunDir.y, 0.05);
    float zenithOD = lightExtinctionOD * sinElev;
    float upwardAO = exp(-zenithOD * max(cfg.aoUpwardScale, 0.0));
    vec3 ambientTerm = cfg.ambientTop * upwardAO
        + cfg.ambientBottom * (1.0 - hpSaturate(h));
    integScatt += ambientTerm * scatterSourceAmount;

    inScattering += integScatt * transmittance;
    transmittance *= stepTransmittance;
}

void hpEvaluateCloud(float density, float localHeight, float slabH, float h,
    vec3 positionAlt, float stepSize, float relativeRayDistance,
    HPCloudConfig cfg, float phaseFunction[3],
    sampler2D weatherTex, sampler3D baseTex, sampler3D erosionTex,
    inout vec3 inScattering, inout float transmittance)
{
    float lightExtinctionOD;
    float phiFwd;
    vec3 totalLuminance = hpEvaluateSunLuminance(positionAlt, localHeight, cfg,
        cfg.sunColor, phaseFunction, weatherTex, baseTex, erosionTex,
        lightExtinctionOD, phiFwd);
    hpEvaluateCloudStep(density, localHeight, slabH, h, positionAlt, stepSize,
        relativeRayDistance, totalLuminance, lightExtinctionOD, phiFwd, cfg,
        inScattering, transmittance);
}

vec4 gHpDebugInfo = vec4(0.0);

vec4 hpTraceVolumetricRay(vec3 rayOriginPlanet, vec3 rayDir, float maxDist,
    HPCloudConfig cfg, sampler2D weatherTex, sampler3D baseTex, sampler3D erosionTex,
    out float hitDistance)
{
    hitDistance = maxDist;

    cfg.cameraAlt = rayOriginPlanet - vec3(0.0, cfg.planetRadius, 0.0);

    if(cfg.viewDistance > 1.0) maxDist = min(maxDist, cfg.viewDistance);
    hitDistance = maxDist;

    const float slabThickness = hpSlab(cfg);
    const float originRadius = length(rayOriginPlanet);
    const float bottomRadius = cfg.planetRadius + cfg.bottomHeight;
    const float topRadius = cfg.planetRadius + cfg.topHeight;

    const float tBottom = hpIntersectShell(originRadius, rayDir.y, bottomRadius);
    const float tTop = hpIntersectShell(originRadius, rayDir.y, topRadius);

    float marchStart;
    float marchEnd;
    {
        bool insideShell = (tBottom <= 0.0 && tTop > 0.0);
        if(insideShell)
        {
            marchStart = 0.0;
            marchEnd = (rayDir.y >= 0.0) ? tTop : tBottom;
        }
        else
        {
            marchStart = max(min(tBottom, tTop), 0.0);
            marchEnd = max(tBottom, tTop);
        }
    }
    float totalDist = min(marchEnd - marchStart, maxDist - marchStart);
    gHpDebugInfo = vec4(marchStart, totalDist, 0.0, maxDist);
    if(totalDist <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);

    float stepLargeRaw = totalDist / max(float(cfg.viewSteps), 1.0);
    float stepLargeNearCap = slabThickness * 0.0625;
    float stepLargeFarCap = slabThickness * 0.5;
    float farDistRef = 50000.0;
    vec3 inScattering = vec3(0.0);
    float transmittance = 1.0;
    float firstStep = min(stepLargeRaw, stepLargeNearCap);
    float dist = cfg.jitter * firstStep;
    float hit = maxDist;

    float cosAngle = dot(rayDir, cfg.sunDir);
    float phaseFunction[3];
    for(int o = 0; o < 3; ++o)
    {
        float ecc = pow(clamp(cfg.msEccentricity, 0.1, 1.0), float(o));
        phaseFunction[o] = hpHG(cosAngle, cfg.forwardEcc * ecc)
            + hpHG(cosAngle, -cfg.backwardEcc * ecc);
    }

    int iterCount = 0;
    int maxIter = clamp(cfg.viewSteps, 8, 256) * 4;

    int lightStride = int(clamp(cfg.lightReuse, 1.0, 8.0));
    vec3 cachedLum = vec3(0.0);
    float cachedLightOD = 0.0;
    float cachedPhiFwd = 0.0;
    int lightTick = 0;

    while(dist < totalDist && iterCount < maxIter)
    {
        iterCount++;

        float distNorm = hpSaturate(dist / farDistRef);
        float absDist = marchStart + dist;
        float viewCap = absDist / 8.0;
        float slabCap = mix(stepLargeNearCap, stepLargeFarCap, distNorm * distNorm);
        float stepLarge = min(stepLargeRaw, min(viewCap, slabCap));
        float stepSmall = stepLarge * 0.25;

        vec3 pos = rayOriginPlanet + absDist * rayDir;
        vec3 posAlt = pos - vec3(0.0, cfg.planetRadius, 0.0);

        float simpleDensity; float simpleLocalHeight;
        hpEvaluateCloudProperties(posAlt, slabThickness, 1.0, true, cfg,
            weatherTex, baseTex, erosionTex, simpleDensity, simpleLocalHeight);

        if(simpleDensity > 0.001)
        {
            float fullDensity; float fullLocalHeight;
            hpEvaluateCloudProperties(posAlt, slabThickness, 0.0, false, cfg,
                weatherTex, baseTex, erosionTex, fullDensity, fullLocalHeight);

            if(fullDensity > 0.001)
            {
                hit = min(hit, absDist);

                float relDist = dist / max(totalDist, 1.0);
                if(lightTick <= 0)
                {
                    cachedLum = hpEvaluateSunLuminance(posAlt, fullLocalHeight, cfg,
                        cfg.sunColor, phaseFunction, weatherTex, baseTex, erosionTex,
                        cachedLightOD, cachedPhiFwd);
                    lightTick = lightStride;
                }
                --lightTick;
                hpEvaluateCloudStep(fullDensity, fullLocalHeight,
                    slabThickness, hpNormalizedHeight(pos, cfg), posAlt,
                    stepSmall, relDist, cachedLum, cachedLightOD, cachedPhiFwd,
                    cfg, inScattering, transmittance);

                if(transmittance < max(cfg.minTransmittance, 1.0e-4))
                {
                    transmittance = 0.0;
                    break;
                }
            }
            dist += stepSmall;
        }
        else
        {
            dist += stepLarge;
        }
    }


    hitDistance = hit;
    gHpDebugInfo.z = float(iterCount);
    gHpDebugInfo.w = hit;
    return vec4(inScattering, transmittance);
}

#ifdef V5_NOISE_BAKER

vec2 hpHash22(vec2 p)
{
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}
float hpHash13(vec3 p3in)
{
    vec3 p3 = fract(p3in * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

float hpWorley2D(vec2 p, float tile)
{
    vec2 cell = floor(p);
    vec2 f = fract(p);
    float best = 8.0;
    for(int y = -1; y <= 1; ++y)
    for(int x = -1; x <= 1; ++x)
    {
        vec2 o = vec2(float(x), float(y));
        vec2 jitter = hpHash22(mod(cell + o, vec2(tile)) + vec2(19.19, 7.7));
        vec2 d = o + jitter - f;
        best = min(best, dot(d, d));
    }
    return 1.0 - sqrt(best);
}

float hpValue2D(vec2 p, float tile)
{
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hpHash13(vec3(mod(cell, vec2(tile)) + 19.19, 7.7));
    float b = hpHash13(vec3(mod(cell + vec2(1, 0), vec2(tile)) + 19.19, 7.7));
    float c = hpHash13(vec3(mod(cell + vec2(0, 1), vec2(tile)) + 19.19, 7.7));
    float d = hpHash13(vec3(mod(cell + vec2(1, 1), vec2(tile)) + 19.19, 7.7));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 hpHash33(vec3 p);

float hpWorley3D(vec3 p, vec3 tile)
{
    vec3 cell = floor(p);
    vec3 f = fract(p);
    float best = 8.0;
    for(int z = -1; z <= 1; ++z)
    for(int y = -1; y <= 1; ++y)
    for(int x = -1; x <= 1; ++x)
    {
        vec3 o = vec3(float(x), float(y), float(z));
        vec3 c = mod(cell + o, tile) + 19.19;
        vec3 jitter = hpHash33(c) * 0.5 + 0.5;
        vec3 d = o + jitter - f;
        best = min(best, dot(d, d));
    }
    return 1.0 - sqrt(best);
}

vec3 hpHash33(vec3 p)
{
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx) * 2.0 - 1.0;
}
float hpPerlin3D(vec3 p, vec3 tile)
{
    vec3 cell = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n000 = dot(hpHash33(mod(cell + vec3(0, 0, 0), tile) + 19.19), f - vec3(0, 0, 0));
    float n100 = dot(hpHash33(mod(cell + vec3(1, 0, 0), tile) + 19.19), f - vec3(1, 0, 0));
    float n010 = dot(hpHash33(mod(cell + vec3(0, 1, 0), tile) + 19.19), f - vec3(0, 1, 0));
    float n110 = dot(hpHash33(mod(cell + vec3(1, 1, 0), tile) + 19.19), f - vec3(1, 1, 0));
    float n001 = dot(hpHash33(mod(cell + vec3(0, 0, 1), tile) + 19.19), f - vec3(0, 0, 1));
    float n101 = dot(hpHash33(mod(cell + vec3(1, 0, 1), tile) + 19.19), f - vec3(1, 0, 1));
    float n011 = dot(hpHash33(mod(cell + vec3(0, 1, 1), tile) + 19.19), f - vec3(0, 1, 1));
    float n111 = dot(hpHash33(mod(cell + vec3(1, 1, 1), tile) + 19.19), f - vec3(1, 1, 1));
    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
               mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}

// 2D **梯度**噪声（Perlin）。天气图专用。
// 为什么不能用 hpValue2D：value noise 的格胞内部是"平顶平台"，被 coverage 阈值
// 一切出来，等值线就是**轴对齐的方块** —— 云于是挤在一格一格的矩形区域里，
// 而且怎么调参数都改不掉形状（方块是晶格决定的，不是参数决定的）。
// 梯度噪声每个格胞内是线性斜坡、格点处为 0，等值线是曲线的，阈值化后没有平台。
// 平铺仍然成立：格点索引用 mod(cell, tile)。
float hpGradient2D(vec2 p, float tile)
{
    vec2 cell = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    vec2 g00 = hpHash22(mod(cell + vec2(0.0, 0.0), vec2(tile)) + 19.19) * 2.0 - 1.0;
    vec2 g10 = hpHash22(mod(cell + vec2(1.0, 0.0), vec2(tile)) + 19.19) * 2.0 - 1.0;
    vec2 g01 = hpHash22(mod(cell + vec2(0.0, 1.0), vec2(tile)) + 19.19) * 2.0 - 1.0;
    vec2 g11 = hpHash22(mod(cell + vec2(1.0, 1.0), vec2(tile)) + 19.19) * 2.0 - 1.0;
    float n00 = dot(g00, f - vec2(0.0, 0.0));
    float n10 = dot(g10, f - vec2(1.0, 0.0));
    float n01 = dot(g01, f - vec2(0.0, 1.0));
    float n11 = dot(g11, f - vec2(1.0, 1.0));
    return mix(mix(n00, n10, u.x), mix(n01, n11, u.x), u.y);
}

// ---- mode 0：weather 2D ----
vec4 hpRenderWeather(vec2 uv)
{
    const float tile = 8.0;
    vec2 p = uv * tile;
    vec2 warp = vec2(
        hpValue2D(p * 2.0 + 71.3, 16.0) - 0.5,
        hpValue2D(p * 2.0 + 137.9, 16.0) - 0.5) * 1.6;
    vec2 q = p + warp;
    float cov = 0.275 * (hpGradient2D(q * 2.0 + 3.1, 16.0) + 0.5)
              + 0.350 * (hpGradient2D(q * 4.0 + 17.9, 32.0) + 0.5)
              + 0.375 * (hpGradient2D(q * 8.0 + 41.3, 64.0) + 0.5);
    cov = clamp((cov - 0.5) * 2.4 + 0.66, 0.0, 1.0);
    float type = clamp(0.5 + 0.9 * (hpValue2D(p * 0.25 + 91.1, 2.0) - 0.5), 0.0, 1.0);
    float blob = 0.62 * hpWorley2D(p * 0.75, 6.0)
               + 0.38 * hpWorley2D(p * 1.5 + 7.31, 12.0);
    float scMask = smoothstep(0.55, 0.85, blob);
    return vec4(cov, type, scMask, 0.5);
}

// ---- mode 1：base 3D（R = PerlinWorley）----
vec4 hpRenderBase(vec3 coord)
{
    const vec3 tile = vec3(12.0, 12.0, 12.0);
    vec3 p = coord * tile;
    float perlin = 0.360 * hpPerlin3D(p, tile)
                 + 0.330 * hpPerlin3D((p * 2.0 + 11.7).zyx, tile * 2.0)
                 + 0.310 * hpPerlin3D((p * 4.0 + 23.1).yxz, tile * 4.0);
    perlin = perlin * 0.5 + 0.5;
    float wf = 0.55 * hpWorley3D(p, tile)
             + 0.45 * hpWorley3D((p * 2.0 + 5.7).zyx, tile * 2.0);
    float pw = wf + perlin * (1.0 - wf);
    pw = clamp((pw - 0.40) / 0.68, 0.0, 1.0);
    return vec4(pw, 0.0, 0.0, 1.0);
}

// ---- mode 2：erosion 3D（RGBA = WispyLo / WispyHi / BillowyLo / BillowyHi）----
// ⚠ 倍频必须是**整数**：hash 的平铺靠 mod(cell, tile)，tile 一旦是 4.5 / 12.6
// 这种非整数，"最后一个格胞"和"第一个格胞"就不是同一个 hash ⇒ 体积里出现
// 一整块**硬边接缝平面**。2026-10-25 实测：原来的 2.1 / 4.1 / 8.3 / 0.75 / 3.1 / 6.3
// 在云体内部切出 3~4 道水平硬边（远看就是"千层饼"横条），先被误判成步进量化，
// 二分到 --erosion 0 才定位到 detail 噪声。整数倍频既有同样频谱，又保持可平铺。
vec4 hpRenderDetail(vec3 coord)
{
    const vec3 tile = vec3(6.0, 6.0, 6.0);
    vec3 p = coord * tile;
    float wispyLo = 0.65 * hpPerlin3D(p, tile)
                  + 0.35 * hpPerlin3D((p * 2.0 + 7.3).zyx, tile * 2.0);
    float wispyHi = 0.60 * hpPerlin3D((p * 4.0 + 17.9).yxz, tile * 4.0)
                  + 0.40 * hpPerlin3D((p * 8.0 + 23.7).zyx, tile * 8.0);
    float billowyLo = 0.625 * hpWorley3D(p, tile)
                    + 0.375 * hpWorley3D((p * 2.0 + 9.1).zyx, tile * 2.0);
    float billowyHi = 0.625 * hpWorley3D((p * 3.0 + 13.9).yxz, tile * 3.0)
                    + 0.375 * hpWorley3D((p * 6.0 + 27.3).zyx, tile * 6.0);
    return vec4(clamp(wispyLo * 0.5 + 0.5, 0.0, 1.0),
        clamp(wispyHi * 0.5 + 0.5, 0.0, 1.0),
        clamp(billowyLo, 0.0, 1.0),
        clamp(billowyHi, 0.0, 1.0));
}
#endif
#endif
