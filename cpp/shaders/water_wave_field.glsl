// ===========================================================================
// Shared water wave field.
//
// 水面形态由**波方程场单独决定**：
//   · water_sim.comp 的常驻风源 + 交互脉冲 → 波高/速度场（RG16F 双缓冲）
//   · 本文件只负责把该场转成光学斜率（waterInteractionSlope）
// 这与参考 HPWater 的机制一致（HPWaterFluidDynamics.cs：没有解析波带、没有风场，
// 只有一套波方程场 + 外部波源；HPWaterFluidHeightToNormal 就是同一套中心差分）。
//
// **契约：不要再往这里加解析波带**（Gerstner 长波带 / 域扭曲 / 噪声倍频程）。
// GUI 的「波长倍率 / 主波向 / 涌浪陡度 / 波峰扭曲」与解析焦散目前没有作用对象，
// 需要这两层时按波方程场重新接靶，而不是把删除的东西加回来。
// 删除清单与副作用见 docs/decisions/water-wave-field.md
//
// Include this AFTER `cam` (CameraProperties) is declared: the field reads
// cam.waterSim*.
// ===========================================================================

#ifndef WATER_WAVE_FIELD_NO_DESCRIPTORS
layout(binding = 33, set = 0) uniform sampler2DArray waterSimulationState;

layout(binding = 59, set = 0) uniform sampler2D waterFftSlope;
#endif
#if !defined(WATER_WAVE_FIELD_NO_DESCRIPTORS)
#define WATER_WAVES_FFT_SWELL 1
#define WATER_FFT_DETAIL_TILE 32.0
#endif


#define WATER_INTERACTION_WAVES 1



#if 0

// ---------------------------------------------------------------------------
// 逐波常量哈希（ROUTE A / ROUTE B **共用**，故放在后端 #if 之外）。
// 把波序号位混成 4 个独立 [0,1) 随机数。整数位混（见 sun_shadow.glsl 的
// sunShadowJitter 同族）⇒ 零 SFU；这些量与采样点无关（每像素恒定），所以放在逐波
// 循环里也不会有额外负担。
// ---------------------------------------------------------------------------
vec4 waterFbmWaveRandom(uint index)
{
    uint hash = index * 747796405u + 2891336453u;
    hash ^= hash >> 16;
    hash *= 0x7feb352du;
    hash ^= hash >> 15;
    hash *= 0x846ca68bu;
    hash ^= hash >> 16;
    const vec2 first = vec2(
        float(hash & 0xffffu), float((hash >> 16) & 0xffffu)) / 65535.0;

    uint hash2 = hash * 2246822519u + 3266489917u;
    hash2 ^= hash2 >> 16;
    hash2 *= 0x7feb352du;
    hash2 ^= hash2 >> 15;
    hash2 *= 0x846ca68bu;
    hash2 ^= hash2 >> 16;
    const vec2 second = vec2(
        float(hash2 & 0xffffu), float((hash2 >> 16) & 0xffffu)) / 65535.0;

    return vec4(first, second);
}



const float WATER_DIRROT_GOLDEN = 2.39996323;
const float WATER_DIRROT_COS = -0.73736888;
const float WATER_DIRROT_SIN =  0.67549029;
vec2 waterDirRotWaveSlopeCapped(vec2 worldXZ, float amplitude, int octaveCap)
{
    const float lambdaMax = clamp(cam.waterWaveShapeParams.x, 1.0, 8.0);
    const float drag = clamp(cam.waterWaveShapeParams.y, 0.0, 1.5);
    const int count =
        clamp(int(cam.waterWaveShapeParams.z + 0.5), 1, min(max(octaveCap, 1), 16));
    const float band = clamp(cam.waterWaveShapeParams.w, 2.0, 32.0);

    const float speedMul = clamp(cam.waterFftParams.w, 0.0, 4.0);
    const int steepInt = clamp(int(clamp(cam.waterFftParams.z, 0.0, 3.0) + 1.5), 1, 4);
    const float waveTime = uintBitsToFloat(cam.padding) * speedMul;

    const float dirRad = radians(cam.waterFftParams2.x);
    const float dirCos = cos(dirRad);
    const float dirSin = sin(dirRad);
    const vec2 p = mat2(dirCos, -dirSin, dirSin, dirCos) * worldXZ;

    const vec2 lodOrigin = cam.waterSimSurfaceAndOrigin.yz;
    const float lodDistance = length(worldXZ - lodOrigin);
    const float minWavelength =
        mix(0.30, 2.0, smoothstep(24.0, 96.0, lodDistance));
    const float kCap = 6.28318531 / minWavelength;

    const float kMin = 6.28318531 / lambdaMax;
    const float log2Band = log2(band);
    const float spectralBeta = 0.8;

    vec2 grad = vec2(0.0);
    vec2 warp = vec2(0.0);
    vec2 dirVec = vec2(1.0, 0.0);

    for(int w = 0; w < count; ++w)
    {
        const vec4 r = waterFbmWaveRandom(uint(w));

        const float q = log2Band * r.z;
        const float k = kMin * exp2(q);
        if(k > kCap) continue;

        dirVec = vec2(
            WATER_DIRROT_COS * dirVec.x - WATER_DIRROT_SIN * dirVec.y,
            WATER_DIRROT_SIN * dirVec.x + WATER_DIRROT_COS * dirVec.y
        );
        const vec2 dir = dirVec;

        const float amp = exp2(-spectralBeta * q);
        const float omega = sqrt(9.8 * k);

        const float x =
            k * dot(dir, p + warp) + waveTime * omega + r.w * 6.28318531;
        const float u = 0.5 * (sin(x) + 1.0);

        float sharp = 1.0;
        if(steepInt >= 2) sharp *= u;
        if(steepInt >= 3) sharp *= u;
        if(steepInt >= 4) sharp *= u;

        const float dH = amp * float(steepInt) * k * sharp * cos(x);
        grad += dir * dH;
        warp += dir * (dH * amp * drag);
    }

    return grad * (amplitude * 0.0621 / sqrt(float(count)));
}
#endif


// pcg2d 格点哈希：uvec2 → uint，已知好的廉价混洗（乘加+移位，零 SFU）。
float waterSundialHash(ivec2 lattice)
{
    uvec2 v = uvec2(lattice);
    v = v * 1664525u + 1013904223u;
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v ^= v >> 16u;
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v ^= v >> 16u;
    return float(v.x ^ v.y) / 4294967296.0;
}

// 平滑 value noise（noisetex 的替身）：返回 (值, ∂/∂纹素x, ∂/∂纹素y)。
// 插值权重 f²(3−2f) 与参考 textureGather 手写插值同式；解析梯度省掉 4 次额外取样。
vec3 waterSundialValueNoise(ivec2 cell, vec2 f)
{
    const float v00 = waterSundialHash(cell);
    const float v10 = waterSundialHash(cell + ivec2(1, 0));
    const float v01 = waterSundialHash(cell + ivec2(0, 1));
    const float v11 = waterSundialHash(cell + ivec2(1, 1));
    const vec2 wgt = f * f * (3.0 - 2.0 * f);
    const float value = mix(mix(v00, v10, wgt.x), mix(v01, v11, wgt.x), wgt.y);
    const vec2 dw = 6.0 * f * (1.0 - f);
    return vec3(
        value,
        ((v10 - v00) * (1.0 - wgt.y) + (v11 - v01) * wgt.y) * dw.x,
        ((v01 - v00) * (1.0 - wgt.x) + (v11 - v10) * wgt.x) * dw.y
    );
}

// gerstnerApproxWaveDerivative 的逐字转写（常数与运算顺序同参考）：
// 返回该层剖面在 coord 空间的 2D 导数。
vec2 waterSundialProfileDeriv(vec2 coord, float steepness)
{
    const float PI = 3.14159265;
    const float waveSmoothness = 1.0 - 0.7 * steepness;
    const float curveOffset1 = (1.0 - waveSmoothness) / PI;
    const float curveOffset2 = pow(curveOffset1, 1.0 / waveSmoothness);
    const float coordScale = PI / (pow(1.0 + curveOffset2, waveSmoothness) - curveOffset1);

    const vec2 noiseCoord = coord * (0.003 * 64.0) + 0.5;
    const ivec2 noiseCell = ivec2(floor(noiseCoord));
    const vec3 n = waterSundialValueNoise(noiseCell, noiseCoord - vec2(noiseCell));
    vec2 noiseDerv = n.yz * (0.003 * 64.0 * 6.0);
    noiseDerv.x += 1.0;

    const float x =
        fract(coord.x * (0.5 / PI) + n.x * (6.0 * 0.5 / PI)) * 2.0 - 1.0;
    const float power = pow(abs(x) + curveOffset2, waveSmoothness - 1.0);
    return sin(coordScale * power * (abs(x) + curveOffset2) - coordScale * curveOffset1)
        * (x < 0.0 ? -coordScale : coordScale)
        / PI * noiseDerv * waveSmoothness * power;
}

vec2 waterSundialWaveSlopeCapped(vec2 worldXZ, float amplitude, int octaveCap)
{
    const float lambdaMax = clamp(cam.waterWaveShapeParams.x, 1.0, 8.0);
    const int layers =
        clamp(int(cam.waterWaveShapeParams.z + 0.5), 1, min(max(octaveCap, 1), 16));
    const float steepness = clamp(cam.waterFftParams.z * (1.0 / 3.0), 0.0, 1.0);
    const float speedMul = clamp(cam.waterFftParams.w, 0.0, 4.0);
    const float waveTime = uintBitsToFloat(cam.padding) * speedMul;
    const float waveScale = lambdaMax * (1.0 / 3.6);
    const float waveHeight = 0.9 * amplitude;

    const float dirRad = radians(cam.waterFftParams2.x);
    const float dirCos = cos(dirRad);
    const float dirSin = sin(dirRad);

    const mat2 goldenRotate =
        mat2(-0.73736888, 0.67549029, -0.67549029, -0.73736888);
    const mat2 layerRotation = mat2(1.4) * mat2(
        goldenRotate[0].x, goldenRotate[1].x,
        goldenRotate[0].y, goldenRotate[1].y);
    mat2 normalRotation = transpose(goldenRotate) * mat2(32.0);

    const float speed = 2.0 * waveTime;
    vec2 coord = mat2(dirCos, -dirSin, dirSin, dirCos) * (worldXZ / waveScale);

    vec2 totalNormal = vec2(0.0);
    float currentWeight = 1.0;
    for(int i = 0; i < layers; ++i)
    {
        coord = goldenRotate * coord;
        totalNormal += normalRotation * (currentWeight *
            waterSundialProfileDeriv(coord - vec2(speed, 0.0), steepness));
        normalRotation *= layerRotation;
        currentWeight *= 0.6;
        coord *= 1.4;
    }
    const float totalWeight = (1.0 - 0.6) / (1.0 - pow(0.6, float(layers)));
    return totalNormal * (totalWeight * 0.5) *
        (0.2 * waveHeight / (32.0 * waveScale));
}

#if WATER_WAVES_FFT_SWELL
// 2026-10-18 远海走样修复（方案 A）：斜率图带 mip 链，按屏幕足迹取级。
// 斜率在**光线追踪阶段**采样，texture() 的隐式 LOD 在 RT 里未定义，故必须显式
// 传 LOD。本函数把"该水面点的每像素世界足迹"换算成纹素倍数并取 log2：
//   projScale   = gl_LaunchSizeEXT.y / (2·tan(fov/2))，由 RT 调用方传（仿 voxyLodMip）；
//                 compute 无 gl_LaunchSizeEXT ⇒ 传 <= 0，退化为 level 0（改动前行为）；
//   rayDistance = 光线命中距离（RT 的 gl_HitTEXT）或相机距离近似；
//   noV         = 视线与竖直方向的夹角余弦（掠射时足迹拉长，取更大的 mip）。
float waterFftSlopeLodFromProj(vec3 surfacePos, float rayDistance, float projScale)
{
    if(projScale <= 0.0)
    {
        return 0.0;
    }
    const float tilePeriod = max(cam.waterFftParams.x, 1.0e-3);
    const float texelWorld = tilePeriod / 256.0;
    vec3 toCam = surfacePos - cam.viewInverse[3].xyz;
    float dist = max(length(toCam), 1.0e-3);
    float noV = max(abs(toCam.y) / dist, 0.04);
    float worldPerPixel = max(rayDistance, 1.0e-3) / (projScale * noV);
    return clamp(log2(max(worldPerPixel / texelWorld, 1.0e-4)), -8.0, 8.0);
}

// horizonFade = HORIZON_WAVE_FADE = 1 − exp(−4·|NoV|)，由调用方按
// 相机→水面的视线与竖直方向的夹角算好传入（NoV = 该夹角的余弦 = |viewDir.y|）。
// waveLod = 上面算好的 mip 级（0 = 最清，8 = 最糊）。
vec2 waterFftSwellSlope(vec2 worldXZ, float horizonFade, float waveLod)
{
    const float tilePeriod = max(cam.waterFftParams.x, 1.0e-3);
    const vec2 uv = fract(worldXZ / tilePeriod);
    const vec2 slope = textureLod(waterFftSlope, uv, clamp(waveLod, 0.0, 8.0)).rg;

    const float detailLod = clamp(
        waveLod + log2(max(tilePeriod / WATER_FFT_DETAIL_TILE, 1.0e-4)),
        0.0,
        8.0
    );
    const vec2 detailUv = fract(worldXZ / WATER_FFT_DETAIL_TILE);
    const vec2 detailSlope =
        textureLod(waterFftSlope, detailUv, detailLod).ba;

    return (slope + detailSlope) * horizonFade;
}
#endif
// ---------------------------------------------------------------------------
// 自然波面入口：调用点（rchit / rgen）契约不变。实现见下 —— 有描述符（可见水面）
// 走 FFT 涌浪；无描述符（体积光波闪）走解析细波层。
// 旧注释"解析波面已删、返回零斜率"描述的是更早的占位实现，已不适用。
// out 参数 cameraDistance 恒写 0：调用方按 0 的语义工作。
// ---------------------------------------------------------------------------
// 2026-10-06 近水掉帧降本：带八度封顶的变体。无封顶的同名入口是所有既有调用点的
// 行为契约，逐位不变。
// 2026-10-15 退役：本函数原是一条 `#if/#elif` 后端天梯（DIRROT/GUNNELL/FBM/AFL/
//   NOISE/SEASCAPE/FFT）。用户定调"全删，只留 ROUTE C" ⇒ 天梯收成 ROUTE C
//   + FFT 涌浪层；其余分支的源码已从本文件移除（回退靠 git 历史）。
// 2026-10-17：可见水面定档 FFT 涌浪，ROUTE C 整体 `#if 0` 屏蔽；
// 无描述符消费者改走解析细波层（见下面的 #else 分支）。
vec2 waterNaturalWaveGradientCapped(
    vec2 worldXZ,
    out float cameraDistance,
    int octaveCap,
    float horizonFade,
    float waveLod
)
{
    cameraDistance = 0.0;

    const float amplitude = cam.waterFftParams.y;
    if(amplitude <= 0.0)
    {
        return vec2(0.0);
    }

#if WATER_WAVES_FFT_SWELL
    return waterFftSwellSlope(worldXZ, horizonFade, waveLod);
#else
    return waterSundialWaveSlopeCapped(worldXZ, amplitude, octaveCap);
#endif
}

const int WATER_WAVE_FULL_OCTAVE_CAP = 16;

vec2 waterNaturalWaveGradient(vec2 worldXZ, out float cameraDistance, float horizonFade, float waveLod)
{
    return waterNaturalWaveGradientCapped(
        worldXZ,
        cameraDistance,
        WATER_WAVE_FULL_OCTAVE_CAP,
        horizonFade,
        waveLod
    );
}

// ===========================================================================
// 波面混合 —— 参考的 HPWaterFluidWorldNormalBlend 是"XZ 相加、Y 相乘"：
//     float2 xz = n1.xz + n2.xz;  float y = n1.y * n2.y;  normalize(xz.x, y, xz.y)
// 这里给出它在**斜率空间**的精确等价（免去来回换法线，只要 2 次 sqrt）：
//   a = sqrt(1+|g1|²)、b = sqrt(1+|g2|²) ⇒ N1=(g1/a, 1/a)、N2=(g2/b, 1/b)
//   blended ∝ (g1/a + g2/b, 1/(ab)) ⇒ slope = g1·b + g2·a  （Y 分量恒为 1）
// 语义：两波叠加处比"直接相加"更陡、更集中（参考的观感）。
// mode=0 ⇒ 逐位旧档（直接相加）。旋钮：cam.waterWaveParams.z。
// 注：自然波面删除后，这里退化为"交互场单独成形"（slope0 恒为 0）。
// ===========================================================================
vec2 waterBlendWaveSlopes(vec2 slope0, vec2 slope1, float mode)
{
    if(mode < 0.001)
    {
        return slope0 + slope1;
    }
    float a = sqrt(1.0 + dot(slope0, slope0));
    float b = sqrt(1.0 + dot(slope1, slope1));
    vec2 blended = slope0 * b + slope1 * a;
    return mix(slope0 + slope1, blended, clamp(mode, 0.0, 1.0));
}

// 波面混合模式的集中读取（消费点不必各自 clamp；0 = 旧档）。
float waterWaveBlendMode()
{
    return clamp(cam.waterWaveParams.z, 0.0, 1.0);
}

// Persistent Eclipse-style interaction field.  The physical water plane is
// unchanged; only its optical normal receives the stored height slope.
//
// `stateAndBorderMask` receives the simulation-active flag times the window
// border fade.  Elevation gating is left to the caller because the closest-hit
// path keys it off the surface it is shading, while the caustic path keys it
// off the water surface the camera ray entered.
vec2 waterInteractionSlope(vec2 worldXZ, out float stateAndBorderMask)
{
    stateAndBorderMask = 0.0;

#if !WATER_INTERACTION_WAVES || defined(WATER_WAVE_FIELD_NO_DESCRIPTORS)
    return vec2(0.0);
#else
    float worldTexel = cam.waterSimSurfaceAndOrigin.w;
    if(cam.waterSimState.y == 0u || worldTexel <= 0.0)
    {
        return vec2(0.0);
    }

    vec2 simulationUV =
        (worldXZ - cam.waterSimSurfaceAndOrigin.yz) /
        (worldTexel * 512.0);
    vec2 simulationBorder = smoothstep(
        vec2(0.0), vec2(0.12), simulationUV
    ) * (1.0 - smoothstep(
        vec2(0.88), vec2(1.0), simulationUV
    ));
    stateAndBorderMask = simulationBorder.x * simulationBorder.y;
    if(stateAndBorderMask <= 0.0)
    {
        return vec2(0.0);
    }

    float stateLayer = float(cam.waterSimState.x);
    vec2 uvStep = vec2(1.0 / 512.0);
    float heightLeft = texture(
        waterSimulationState,
        vec3(simulationUV - vec2(uvStep.x, 0.0), stateLayer)
    ).r;
    float heightRight = texture(
        waterSimulationState,
        vec3(simulationUV + vec2(uvStep.x, 0.0), stateLayer)
    ).r;
    float heightDown = texture(
        waterSimulationState,
        vec3(simulationUV - vec2(0.0, uvStep.y), stateLayer)
    ).r;
    float heightUp = texture(
        waterSimulationState,
        vec3(simulationUV + vec2(0.0, uvStep.y), stateLayer)
    ).r;

    float calibration = clamp(cam.waterWaveParams.x, 0.0, 1.0);
    float strength = clamp(cam.waterWaveParams.y, 0.0, 2.0);
    float interactionScale = mix(0.19, 0.5, calibration) * strength;
    return vec2(
        heightRight - heightLeft,
        heightUp - heightDown
    ) * (interactionScale / max(worldTexel, 1.0e-4));
#endif
}
