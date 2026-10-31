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
    baseCloud *= mix(1.0, densityHeightGradient, cloudTypeInfluence);

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
    float ambientStrength;
};

struct V3MarchInputs
{
    vec3 rayOrigin;
    vec3 rayDirection;
    vec3 lightDirection;
    vec3 shellOrigin;
    vec3 earthCenter;
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
    float extinction;
    vec3 ambientColor;
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
    float atmosphereThickness = max(inputs.layerThickness, 1.0);
    int lightSampleCount = clamp(inputs.lightSamples, 1, V3_MAX_LIGHT_SAMPLES);

    const float cosAngle = dot(normalize(inputs.rayDirection), normalize(inputs.lightDirection));
    const float hgLight = v3HGModified(cosAngle, settings.eccentricity,
        settings.silverIntensity, settings.silverSpread);

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

            float sigma = highFreqDensity * inputs.extinction * stepSize;
            float sampleOpacity = 1.0 - exp(-sigma);

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
