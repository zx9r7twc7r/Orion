// ===========================================================================
// 体积云 v3 核心：云模型（MIT，逐段移植）
//
// 这个文件**不依赖引擎**（不读 CameraUBO、不做球壳求交），只吃参数与采样器，
// 因此可以被两处同时 include：
//   1) cpp/shaders/volumetric_clouds_v3.comp   游戏内路径
//   2) cpp/shaders/cloud_preview.comp          离线预览工具（tools/cloudpreview）
// 两边跑的是同一份代码 ⇒ 预览出来的形状/明暗就是游戏里会看到的东西。
//
//   :475-487 云型垂直梯度   :489-497 风剪切   :499-540 低频形状
//   :542-563 高频侵蚀       :295-306 双 HG    :331-388 光能量
//   :565-688 rayMarch       :762      地平线淡出
// ===========================================================================
#ifndef CLOUD_V3_CORE
#define CLOUD_V3_CORE

const float V3_PI = 3.14159265;
const float V3_ONE_OVER_FOUR_PI = 0.07957747154594767;
const int V3_MAX_STEPS = 96;
const int V3_MIN_STEPS = 12;
const int V3_MAX_LIGHT_SAMPLES = 6;

float v3Remap(float value, float originalMin, float originalMax, float newMin, float newMax)
{
    return newMin + ((value - originalMin) / (originalMax - originalMin)) * (newMax - newMin);
}

float v3RemapClamped(float value, float originalMin, float originalMax, float newMin, float newMax)
{
    return clamp(v3Remap(value, originalMin, originalMax, newMin, newMax), newMin, newMax);
}

float v3RemapClampedBeforeAndAfter(float value, float originalMin, float originalMax,
    float newMin, float newMax)
{
    value = clamp(value, originalMin, originalMax);
    return clamp(v3Remap(value, originalMin, originalMax, newMin, newMax), newMin, newMax);
}

// 114-132 行（Halton 序列按参考的 16 项表）
vec2 v3JitterOffset(int index, vec2 dimensions)
{
    const vec4 haltonSeq1 = vec4(0.500000, 0.250000, 0.750000, 0.125000);
    const vec4 haltonSeq2 = vec4(0.625000, 0.375000, 0.875000, 0.062500);
    const vec4 haltonSeq3 = vec4(0.562500, 0.312500, 0.812500, 0.187500);
    const vec4 haltonSeq4 = vec4(0.687500, 0.437500, 0.937500, 0.031250);
    vec2 jitter = vec2(0.0);
    index = index / 2;
    if(index < 4)
    {
        jitter.x = haltonSeq1[index];
        jitter.y = haltonSeq2[index];
    }
    else
    {
        index -= 4;
        jitter.x = haltonSeq3[index];
        jitter.y = haltonSeq4[index];
    }
    return jitter / dimensions;
}

float v3RelativeHeight(vec3 point, vec3 earthCenter, vec3 startPosOnInnerShell,
    vec3 rayDir, vec3 eye, float atmosphereThickness)
{
    float lengthOfRayFromCamera = length(point - eye);
    float lengthOfRayToInnerShell = length(startPosOnInnerShell - eye);
    vec3 pointToEarthDir = normalize(point - earthCenter);
    float cosTheta = dot(rayDir, pointToEarthDir);
    float numerator = abs(cosTheta * (lengthOfRayFromCamera - lengthOfRayToInnerShell));
    return numerator / atmosphereThickness;
}

vec3 v3RelativePosition(vec3 pos, vec3 shellOrigin, float layerThickness)
{
    return (pos - shellOrigin) / layerThickness;
}

// ---------------------------------------------------------------- 光照
float v3HenyeyGreenstein(float cosAngle, float eccentricity)
{
    float numerator = 1.0 - eccentricity * eccentricity;
    float denominator = pow(1.0 + eccentricity * eccentricity - 2.0 * eccentricity * cosAngle, 1.5);
    return (numerator / denominator) * V3_ONE_OVER_FOUR_PI;
}

float v3HGModified(float cosAngle, float eccentricity, float silverIntensity, float silverSpread)
{
    return max(v3HenyeyGreenstein(cosAngle, eccentricity),
        silverIntensity * v3HenyeyGreenstein(cosAngle, 0.99 - silverSpread));
}

float v3BeerLambertModified(float densityAlongLightRay, float attenuationReduction,
    float influenceReduction)
{
    return max(exp(densityAlongLightRay),
        exp(densityAlongLightRay * attenuationReduction) * influenceReduction);
}

// 331-388 行（保留三处 MANIPULATE ME 的取值）
float v3LightEnergy(float heightFraction, float dl, float dsLoded, float phaseProbability,
    float cosAngle, float stepSize, float brightness, float heightSkew)
{
    float primaryAttenuation = exp(-dl);
    float secondaryAttenuation = exp(-dl);
    float attenuationProbability = max(
        v3Remap(cosAngle, 0.7, 1.0, secondaryAttenuation, secondaryAttenuation * 0.25),
        primaryAttenuation);

    float depthProbability = 0.05 + pow(dsLoded,
        clamp(v3Remap(heightFraction * 0.125, 0.3, 0.85, 0.5, 2.0), 0.5, 2.0));
    float verticalProbability = pow(clamp(
        v3Remap(heightFraction * heightSkew, 0.07, 0.34, 0.1, 1.0), 0.1, 1.0), 0.8);

    float inScatterProbability = depthProbability * verticalProbability;
    return attenuationProbability * primaryAttenuation * inScatterProbability *
        phaseProbability * brightness;
}

// ---------------------------------------------------------------- 密度
float v3DensityHeightGradient(float relativeHeight, float cloudType)
{
    relativeHeight = clamp(relativeHeight, 0.0, 1.0);

    float cumulus = max(0.0, v3Remap(relativeHeight, 0.01, 0.3, 0.0, 1.0) *
        v3Remap(relativeHeight, 0.6, 0.95, 1.0, 0.0));
    float stratocumulus = max(0.0, v3Remap(relativeHeight, 0.0, 0.25, 0.0, 1.0) *
        v3Remap(relativeHeight, 0.3, 0.65, 1.0, 0.0));
    float stratus = max(0.0, v3Remap(relativeHeight, 0.0, 0.1, 0.0, 1.0) *
        v3Remap(relativeHeight, 0.2, 0.3, 1.0, 0.0));

    float a = mix(stratus, stratocumulus, clamp(cloudType * 2.0, 0.0, 1.0));
    float b = mix(stratocumulus, stratus, clamp((cloudType - 0.5) * 2.0, 0.0, 1.0));
    return mix(a, b, cloudType);
}

vec3 v3SkewSamplePoint(vec3 point, inout float heightFraction, vec3 windDir,
    float windSpeed, float cloudTopOffset, float timeSeconds)
{
    point += heightFraction * windDir * cloudTopOffset * 0.009;
    point += (windDir + vec3(0.0, 0.1, 0.0)) * windSpeed * timeSeconds;
    return point;
}

float v3SampleLowFrequency(
    sampler3D shapeTex,
    sampler2D weatherTex,
    vec3 point,
    vec3 unskewedSamplePoint,
    float relativeHeight,
    float coverageMaster,
    float cloudTypeInfluence,
    float weatherScale)
{
    vec4 lowFrequencyNoises = texture(shapeTex, point);

    float lowFrequencyFBM = (lowFrequencyNoises.g * 0.625) +
                            (lowFrequencyNoises.b * 0.25) +
                            (lowFrequencyNoises.a * 0.125);
    lowFrequencyFBM = clamp(lowFrequencyFBM, 0.0, 1.0);

    float baseCloud = v3RemapClamped(lowFrequencyNoises.r, lowFrequencyFBM - 0.9, 1.0, 0.0, 1.0);

    vec3 weatherData = texture(weatherTex, unskewedSamplePoint.xz / weatherScale).rgb;
    float densityHeightGradient = v3DensityHeightGradient(relativeHeight, weatherData.g);
    // 参考 :524 的注释值是 `densityHeightGradient * 0.5`，但它会把 baseCloud 整体
    // 对半砍（实测直接没云）——作者注释掉的正是这个原因。这里改成**归一化**：
    // 梯度峰值 1.0 时不改变密度，只按高度重新分配（积云中段厚、层云贴底）。
    baseCloud *= mix(1.0, densityHeightGradient, cloudTypeInfluence);

    // 覆盖度：参考常量 0.6（:529）；weather.r 是覆盖度偏置。coverageMaster 是
    // 引擎滑杆的主控，映射成"相对参考 0.6 的倍率"，0.4 时正好回到 0.6。
    float weatherCoverage = mix(0.6, weatherData.r, cloudTypeInfluence);
    float cloudCoverage = clamp(weatherCoverage * (0.4 + 1.2 * coverageMaster), 0.0, 1.0);

    float baseCloudWithCoverage =
        v3RemapClampedBeforeAndAfter(baseCloud, cloudCoverage, 1.0, 0.0, 1.0);
    baseCloudWithCoverage *= cloudCoverage;
    return baseCloudWithCoverage;
}

float v3ErodeWithHighFrequency(
    sampler3D detailTex,
    sampler2D curlTex,
    float baseCloud,
    vec3 point,
    float heightFraction,
    float curlStrength,
    float erosionScale)
{
    vec4 curlNoise = texture(curlTex, point.xz);
    point.xz += curlNoise.xy * (1.0 - heightFraction) * curlStrength;

    vec4 highFrequencyNoise = texture(detailTex, point);

    float highFreqFBM = (highFrequencyNoise.r * 0.625) +
                        (highFrequencyNoise.g * 0.25) +
                        (highFrequencyNoise.b * 0.125);

    float highFreqModifier = clamp(
        mix(highFreqFBM, 1.0 - highFreqFBM, clamp(heightFraction * 2.0, 0.0, 1.0)), 0.0, 1.0);

    return v3Remap(baseCloud, highFreqModifier * erosionScale, 1.0, 0.0, 1.0);
}

struct V3MarchSettings
{
    float eccentricity;
    float silverIntensity;
    float silverSpread;
    float brightness;
    // 2026-10-18：环境项。参考只有太阳项（GetLightEnergy），云底在玩家仰视时
 // 会接近纯黑。有 ambient/SH；这里用本引擎的 skyColor 补一个
    // 按"云内遮挡"衰减的环境项，强度由滑杆给（0 = 逐字移植的原始行为）。
    float ambientStrength;
};

struct V3MarchInputs
{
    vec3 rayOrigin;
    vec3 rayDirection;
    vec3 lightDirection;
    vec3 shellOrigin;      // 内壳顶（= 云底）在 anchor 处的位置
    vec3 earthCenter;      // 球心（anchor, base - R, anchor）
    float startT;
    float endT;
    float layerThickness;
    float coverageMaster;
    float baseDensityFactor;
    float noiseScale;
    float cloudTypeInfluence;
    float windSpeed;
    float timeSeconds;
    vec3 windDirection;
    float heightGradientSkew;
    float erosionScale;
    float curlStrength;
    float weatherScale;
    float erosionAmount;
    float extinction;      // 每格消光（按密度滑杆换算）
    vec3 ambientColor;     // 环境（天光）颜色，用于云底补光
    int requestedSteps;
    int lightSamples;
    int frameIndex;
};

// ---------------------------------------------------------------- march
// 565-688 行。返回 vec4(marchColor, accumDensity)，并写出首个命中距离。
vec4 v3MarchClouds(
    sampler3D shapeTex,
    sampler3D detailTex,
    sampler2D curlTex,
    sampler2D weatherTex,
    const V3MarchInputs inputs,
    const V3MarchSettings settings,
    out float cloudDistance)
{
    cloudDistance = 1.0e6;

    float dotUp = dot(inputs.rayDirection, vec3(0.0, 1.0, 0.0));
    float referenceSteps = floor(mix(35.0, 60.0, 1.0 - clamp(dotUp, 0.0, 1.0)));
    float qualityScale = clamp(float(inputs.requestedSteps) / 24.0, 0.5, 1.5);
    int steps = int(clamp(referenceSteps * qualityScale, float(V3_MIN_STEPS), float(V3_MAX_STEPS)));
    float stepSize = (inputs.endT - inputs.startT) / float(steps);
    // 2026-10-18 修正：参考的 ATMOSPHERE_THICKNESS 是**层厚常量**，不是当前射线的
    // 弦长（endT-startT）。用弦长会让相对坐标/相对高度被压缩，云型梯度直接归零
    // （预览实测：从上方俯视完全没有云）。
    float atmosphereThickness = max(inputs.layerThickness, 1.0);
    int lightSampleCount = clamp(inputs.lightSamples, 1, V3_MAX_LIGHT_SAMPLES);

    const float cosAngle = dot(normalize(inputs.rayDirection), normalize(inputs.lightDirection));
    const float hgLight = v3HGModified(cosAngle, settings.eccentricity,
        settings.silverIntensity, settings.silverSpread);

    // 太阳 cone 采样核（:598-624）
    vec3 lightDirection = normalize(inputs.lightDirection);
    vec3 maxCompUnitVector;
    if(abs(lightDirection.x) > abs(lightDirection.y) && abs(lightDirection.x) > abs(lightDirection.z))
    {
        maxCompUnitVector = vec3(abs(lightDirection.x), 0.0, 0.0);
    }
    else if(abs(lightDirection.y) > abs(lightDirection.x) &&
            abs(lightDirection.y) > abs(lightDirection.z))
    {
        maxCompUnitVector = vec3(0.0, abs(lightDirection.y), 0.0);
    }
    else
    {
        maxCompUnitVector = vec3(0.0, 0.0, abs(lightDirection.z));
    }

    vec3 zComponent = cross(lightDirection, maxCompUnitVector);
    vec3 xComponent = cross(zComponent, lightDirection);
    mat3 sunRotMatrix = mat3(xComponent, lightDirection, zComponent);

    vec3 noiseKernel[V3_MAX_LIGHT_SAMPLES];
    noiseKernel[0] = sunRotMatrix * vec3(0.1, 0.25, -0.15);
    noiseKernel[1] = sunRotMatrix * vec3(0.2, 0.5, 0.2);
    noiseKernel[2] = sunRotMatrix * vec3(-0.2, 0.1, -0.1);
    noiseKernel[3] = sunRotMatrix * vec3(-0.05, 0.75, 0.05);
    noiseKernel[4] = sunRotMatrix * vec3(-0.1, 1.0, 0.0);
    noiseKernel[5] = sunRotMatrix * vec3(0.0, 3.0, 0.0);

    int jitterIndex = inputs.frameIndex % 16;
    vec2 jitter = v3JitterOffset(jitterIndex, vec2(75.0));
    const float jitterFactor = 1.180;

    float accumDensity = 0.0;
    float transmittance = 1.0;
    vec3 returnColor = vec3(0.0);

    for(int i = 0; i < V3_MAX_STEPS; ++i)
    {
        if(i >= steps) break;

        float t = inputs.startT + float(i) * stepSize;
        vec3 rayOffset = vec3(jitter.x, (jitter.x + jitter.y) * jitterFactor, jitter.y);
        vec3 pos = inputs.rayOrigin + t * (inputs.rayDirection + rayOffset);

        vec3 samplePoint = v3RelativePosition(pos, inputs.shellOrigin, atmosphereThickness);
        samplePoint /= inputs.noiseScale;

        // 相对高度：参考用 cosTheta 近似，但那条式子在
        // **接近水平的视线**上会乘一个 dot(dir,up)≈0 的因子 ⇒ 高度恒为 0，
        // 云型梯度随即把密度清零（实测"层内平视 / 云顶俯视看不到云"）。
        // 这里直接用几何高度：h = (pos.y - 云底) / 层厚 —— 与参考语义一致且稳定。
        float relativeHeight = clamp((pos.y - inputs.shellOrigin.y) /
            max(inputs.layerThickness, 1.0), 0.0, 1.0);
        float heightFraction = relativeHeight;

        vec3 skewedSamplePoint = v3SkewSamplePoint(samplePoint, heightFraction,
            inputs.windDirection, inputs.windSpeed, 1.0, inputs.timeSeconds);

        float baseDensity = v3SampleLowFrequency(shapeTex, weatherTex, skewedSamplePoint, pos,
            heightFraction, inputs.coverageMaster, inputs.cloudTypeInfluence,
            inputs.weatherScale) * inputs.baseDensityFactor;

        if(baseDensity > 0.0)
        {
            float highFreqDensity = v3ErodeWithHighFrequency(detailTex, curlTex,
                baseDensity * inputs.erosionAmount, skewedSamplePoint, heightFraction,
                inputs.curlStrength, inputs.erosionScale);
            highFreqDensity = clamp(highFreqDensity, 0.0, 1.0);

            cloudDistance = min(cloudDistance, t);

            float densityAlongLight = 0.0;
            for(int k = 0; k < V3_MAX_LIGHT_SAMPLES; ++k)
            {
                if(k >= lightSampleCount) break;
                vec3 lightPos = pos + (stepSize * noiseKernel[k] * float(k));
                vec3 sampleLightPos = v3RelativePosition(lightPos, inputs.shellOrigin,
                    atmosphereThickness);
                sampleLightPos /= inputs.noiseScale;

                float currBaseLightDensity = v3SampleLowFrequency(shapeTex, weatherTex,
                    sampleLightPos, lightPos, heightFraction, inputs.coverageMaster,
                    inputs.cloudTypeInfluence, inputs.weatherScale);
                if(currBaseLightDensity > 0.0)
                {
                    float currLightDensity = v3ErodeWithHighFrequency(detailTex, curlTex,
                        inputs.erosionAmount * currBaseLightDensity, sampleLightPos,
                        heightFraction, inputs.curlStrength, inputs.erosionScale);
                    densityAlongLight += max(currLightDensity, 0.0);
                }
            }

            float totalLightEnergy = v3LightEnergy(heightFraction, densityAlongLight, baseDensity,
                hgLight, cosAngle, stepSize, settings.brightness, inputs.heightGradientSkew);

            // 2026-10-18 与参考版本的唯一差别（已注释说明）：
            // 参考用 `transmittance = mix(T, L, 1-accumDensity); returnColor += T;`，
            // 每步都累加一次近 1 的量，稀疏云会把颜色累到几十（预览实测一片死白）。
            // 这里改成能量守恒的积分：σ = density × extinction，
            //   opacity = 1 - exp(-σ·ds)，scattering += L·T·opacity，T *= 1-opacity。
            // 密度/光照/相位/cone 采样仍逐字来自参考。
            float sigma = highFreqDensity * inputs.extinction * stepSize;
            float sampleOpacity = 1.0 - exp(-sigma);

            // 环境项：用"沿光线的密度"近似遮挡，厚云底自然更暗
            float ambientOcclusion = exp(-densityAlongLight * 0.08);
            vec3 radiance = vec3(totalLightEnergy) +
                inputs.ambientColor * settings.ambientStrength * ambientOcclusion;
            returnColor += radiance * transmittance * sampleOpacity;
            transmittance *= (1.0 - sampleOpacity);
            accumDensity = 1.0 - transmittance;

            if(transmittance < 0.02)
            {
                break;
            }
        }
    }

    // 地平线淡出（参考 :762）。参考只在"向上看"时才走这条路径（他们 _dot < 0.06
    // 直接早退），所以原式对向下视线会算出负数 ⇒ smoothstep 归零。预览实测：
    // 从层顶俯视整幅图 0 密度，就是这个 clamp 缺失导致的。
    // 这里只在 dir.y > 0 时淡出，向下视线保持原样。
    float horizonFade = 1.0;
    if(inputs.rayDirection.y > 0.0)
    {
        horizonFade = smoothstep(0.0, 1.0,
            clamp(v3Remap(inputs.rayDirection.y, 0.06, 0.2, 0.0, 1.0), 0.0, 1.0));
    }
    accumDensity *= horizonFade;
    returnColor *= horizonFade;

    return vec4(returnColor, clamp(accumDensity, 0.0, 1.0));
}

#endif
