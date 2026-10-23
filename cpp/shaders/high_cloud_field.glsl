// =============================================================================
// 高层平面云（2026-10-02，C 档重构）
//
// 蓝本：sixthsurge/photon 的 shaders/include/sky/clouds/cirrus.glsl 与
// altocumulus.glsl。photon 的许可为 NOASSERTION（Complementary/Chocapic 血统
// 的包通常禁止移植）⇒ **只借结构，代码全部重写，未复制任何一行**。借来的结构：
//   1. 球壳薄板 + 单次采样 + 解析光学厚（extinction·d·THICKNESS/|dir.y|）。
//      掠射视线由此自然加厚变淡，换掉了旧实现 abs(direction.y) < 0.005 的
//      "地平线整圈云突然消失"硬截断。
//   2. curl 域扭曲：从 cloudCurlNoise 取一个 z 切片当 2D 卷曲场，按八度逐级
//      放大 → 卷云的丝缕感（旧实现没有域扭曲，形状像涂上去的色块）。
//   3. Beer-Lambert 透射 + 单瓣/多瓣相函数 + powder 暗边。
//   4. 两个不同高度的层：A = 高层卷云（稀疏丝缕），B = 低层卷层云（成片）。
//
// 光照色相沿用本项目**已验收**的"日落加法火光"调色（旧 sampleHighCloud 的
// tint/fire 表达式原样保留），所以开起来与原观感同源，不引入新色偏。见项目
// 记忆 "云观感 vs 参考落日照" 与 "用户的中文颜色词"。
//
// 依赖（调用方必须先声明）：cam（读 highCloudParams0..4）、cloudShapeNoise、
// cloudCurlNoise；并且必须在 tyndall_cloud_field.glsl **之后** include（用它的
// tyndallTime / tyndallSphereRoots / tyndallHeight / TYNDALL_RADIUS）。
// 只有 rt_test.rmiss 与 volumetric_clouds.comp 会 include 本文件 —— 因为只有
// 它们把 CameraProperties 声明到了 highCloudParams（volumetric_light.comp 与
// rt_test.rgen 不读高层云参数，所以不 include，避免它们的块缺尾成员而编译失败）。
//
// lane 布局（CameraUBO.highCloudParams，20 个 float = 5 个 vec4）：
//   hc0 = 启用, 亮度, 薄板厚度(格), 光照步数(0=解析估计，最省)
//   hc1 = A: 覆盖率, 密度, 高度(距体积云顶,格), 水平尺寸
//   hc2 = A: 细节强度, 卷曲强度, 风速, 风向(度)
//   hc3 = B: 覆盖率, 密度, 高度, 水平尺寸
//   hc4 = B: 细节强度, 卷曲强度, 风速, 风向(度)
// =============================================================================
// =============================================================================
// ⚠ 2026-10-09 现状：本文件**当前生效的实现是文件末尾的「平面云」**（
// 蓝本重写，见那一节的完整注释）。下面 2026-10-02 的 photon 双层薄板实现仍是
// 退役状态（外层 #if 0，保留供逐字节回溯），不再是 live 代码。
// =============================================================================
// 2026-10-02 整层退役（性能）。
//
// 20 项旋钮全部可响应之后，本层仍是这个 pass 里最贵的一段：默认档 12 次纹理
// 采样/像素 = 2 层 × [1 次 curl 域扭曲 + 2 次基础八度 + 3 次细节八度]，开光
// march 再 +3 次/步/层。用户决定整层退役，只留体积云层。实现体整体用**外层
// #if 0** 屏蔽 —— 不是宏，-D 也打不开；文件末尾只留一个 no-op 的
// sampleHighCloud，两个消费者（rt_test.rmiss / volumetric_clouds.comp）一行
// 都不用改就能继续编译。
//
// 恢复步骤：把下面的 #if 0 改成 #if 1，并删掉文件末尾的 no-op stub。配套全部
// 原样留着，没有别的步骤：C++ 的 CameraUBO.highCloudParams[20] 与
// populateHighCloudParams、Java 的 RTXSettingsConfig.HighCloudSettings、
// GUI 的 20 项入口（RTXSettingsScreen 里被 if(false) 包住）、JSON 键。
//
// 注意：shader 侧的 highCloudParams0..4 **声明**必须保留 —— volumetric_clouds
// .comp 的 cloudMsParams 定在偏移 1024，靠它前面这些块走到底，删了会错位。
// =============================================================================
#ifndef HIGH_CLOUD_FIELD_GLSL
#define HIGH_CLOUD_FIELD_GLSL

#if 0  // ---- 退役屏蔽开始：高层平面云实现（全部）----
const float HC_PI = 3.14159265359;
// 层内**单位密度**的消光系数（1/格）。
// 2026-10-02 修（响应性）：旧值 0.9 是"每单位厚度"的量级，而厚度滑杆的单位是
// **格**（默认 1200）⇒ 解析光学厚 extinction·d·thickness 恒在 10^3 量级 ⇒
// 透射率恒 0、直射与天空项被各自的 exp(-大数) 打到 0；再叠加旧的
// scatterIntegral = opacity/extinction（饱和后 = 1/extinction，与 thickness
// 无关），「薄板厚度」「云体密度」被从颜色里整个约掉，拖到底看不出差别。
// 现在按真实的每格消光取值：默认档（厚度 1200、密度 1.0、d≈0.5）天顶光学厚
// ≈ 0.6，掠射 ∝1/|dir.y| 自然变厚，整段厚度 16..8000 落在"几乎透明 → 不透明"
// 的可辨区间内。
const float HC_EXTINCTION = 0.0010;
// 基准噪声频率（1/格）。特征尺度 ≈ 1/HC_BASE_FREQ ≈ 180 km ⇒ 卷云的丝缕量级。
const float HC_BASE_FREQ = 0.0000055;
const float HC_OCT2 = 7.5;        // 第二八度频率倍率
const float HC_OCT_DETAIL = 60.0; // 细节八度频率倍率
const float HC_ANISO = 0.42;      // 沿 x 压扁 ⇒ 丝缕状拉长
const float HC_MAX_DISTANCE = 200000.0;
// 视线天顶角下限：解析光程 thickness/|dir.y| 的分母保护（掠射时云自然变厚）。
const float HC_MIN_VIEW_COS = 0.05;
// 光线天顶角下限：直射自阴影光程 thickness/|lightDir.y| 的分母保护。
const float HC_MIN_LIGHT_COS = 0.15;
// 直射/天空项的基线（radiance 量纲，与 skyColor 同尺）。旧值 0.40/0.60 是配
// opacity/extinction 用的（那个量纲约 1/extinction ≈ 1.1，大致抵消）；颜色改成
// ×opacity 之后必须重标，否则整层会暗一个数量级。
// 主亮度旋钮仍是 highCloudParams0.y，这里只是基线。
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

    // 2 个八度的 curl 域扭曲（curl 取自 cloudCurlNoise 的 z=0.5 切片）
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
        // 0..2 全段可用：旧系数 0.50 时 detail=1 就蚀掉一半、到 2 直接蚀空 ——
        // 滑杆右半段变成"越界"而不是"更强"。0.35 让 0..2 都落在可见侵蚀区间内。
        density -= (d1 * 0.70 + d2 * 0.30) * detailStrength * 0.35;
    }

    density = max(density, 0.0);
    // 高度整形：底部稀薄、顶部密实（photon altitude_shaping 的同形简化）
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

    // altitude = 体积云顶 + lp1.z（lp1.z 就是"距体积云顶"），单位格。
    // hcSphereCenter() 的球心与 radiusInner 是按"锚在云底球面"这一约定摆的，
    // 两者必须成对理解：球面高度 = cam.cloudParams0.x + altitude。
    float altitude = cam.cloudParams0.y + lp1.z;
    float radiusInner = TYNDALL_RADIUS + altitude;

    // 2026-10-02 修（响应性根因）：旧实现对**外**球面求交后取 max(root, 0)。
    // 相机总在薄板之下 ⇒ 相机落在外球面**内部**，近根为负、max 恒为 0 ⇒ 采样点
    // 退回相机自身；而 coord 又由相机的径向投影给出，于是**整片天空拿到同一个
    // 与视线无关**的密度值：既没有云形，拖任何形状滑杆也只是整体变一个数。
    // 现在与**薄板中面**求交：t 随视线变化（视差正确）、采样点落在层内
    // （altitudeFraction = 0.5）、噪声场随视线展开。
    float radiusMid = radiusInner + 0.5 * thickness;
    vec2 midRoots;
    if(!tyndallSphereRoots(origin, direction, radiusMid, midRoots))
        return vec4(0.0, 0.0, 0.0, 1.0);
    // 相机在中面下方（常态）取远根 = 穿出中面；在中面上方取近根 = 进入中面。
    float t = midRoots.x > 0.0 ? midRoots.x : midRoots.y;
    if(t <= 0.0 || t > HC_MAX_DISTANCE) return vec4(0.0, 0.0, 0.0, 1.0);
#if 0
    // 旧式求交（只碰外球面，采样点恒落回相机）。屏蔽保留，
    // 取代原因见上面这段注释。
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
    // 沿径向投影到该层基准球面 ⇒ 去掉厚度方向上的视差，"一层一个 2D 噪声场"。
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
    // 解析薄板光学厚：掠射（|dir.y|→0）时光程 ∝ 1/|dir.y|，云自然变厚变淡。
    float viewOD = extinction * d * thickness / max(abs(direction.y), HC_MIN_VIEW_COS);
    float trans = exp(-max(viewOD, 0.0));
    float opacity = 1.0 - trans;
    if(opacity < 0.002) return vec4(0.0, 0.0, 0.0, 1.0);
#if 0
    // 旧式散射积分 = opacity/extinction：饱和后它退化成 1/extinction，与
    // thickness、d 都脱钩 ⇒ 厚度/密度滑杆在颜色上失效（响应性根因之一）。
    float scatterIntegral = opacity / max(extinction, 1.0e-4);
#endif
    // 单次散射解 L = 源函数 × (1 - exp(-τ))：源函数是真 radiance 量纲，
    // 所以这里乘 opacity（透射率损耗），而不是乘 opacity/extinction。
    float scatterIntegral = opacity;

    // 直射光衰减：几何 march（lightSteps>0）或解析估计（0，零额外采样）。
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

    // ---- 已验收的日落火光调色（旧 sampleHighCloud 的 expr 原样保留）----
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

    // 两层各自求交，再**按命中距离**定前后。不能写死"A 先 B 后"：相机在两层
    // 之下时，向上的视线先撞到较低那层（近），高的那层在它后面；把顺序写死会
    // 让近层被当成背景乘以远层的透射，颜色顺序反了。
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

    int front = -1; // 近
    int back = -1;  // 远
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
#endif  // ---- 退役屏蔽结束：高层平面云实现（全部）----

// =============================================================================
// 平面云（2026-10-09）—— 蓝本的
// planeClouds()。**只借结构与算法，代码全部按本项目架构重写**：直接
// 依赖它自己的 Bruneton 大气库（sampleInScatteringDoubleSide /
// atmosphereAbsorptionGround）和它自己的 64×64 noisetex，本渲染器两者都没有，
// 逐行搬运编译不过，也没有意义。
//
// 借来的结构：
//   1. 固定高度**球壳**求交，不按 cameraY 求交 —— 俯仰角变化时云层不会整体滑动，
//      视差正确；球壳沿用体积云同一球心约定（tyndallSphereRoots）。
//   2. 多八度 2D FBM：每级 `cloudPos = cloudPos * OCTAVE_SCALE + wind`，权重按
// OCTAVE_FADE 衰减，末尾除以权重和归一。原始循环结构。
//   3. 云量阈值 `clamp(density + AMOUNT - 1, 0, 1)`，AMOUNT 即"覆盖率"旋钮。
//   4. 距离淡出 `exp2(-t / FADE_DISTANCE)`。
// 5. 不透明度 `1 - exp2(-DENSITY * d)`，再**平方**进 alpha。
//   6. 视-光夹角的前向散射瓣（银边）+ powder 薄处提亮。
//
// 换掉的两处（本渲染器没有对应设施）：
// · 噪声：用 64×64 可平铺 noisetex 的 smooth2DNoise；本项目改取
//     cloudShapeNoise（128³ 可平铺体）的一个 z 切片当 2D 场（退役版与 tyndall
// 细节层都是这么做的），每八度 1 次纹理读。频率沿用 
//     `coord = cloudPos * 0.0001 / SCALE`，所以两者的特征尺度一致。
// · 光照：沿视线积分 Bruneton 内散射；本渲染器改用**已验收**的日落
//     调色（与体积云同源，不引入新色偏）+ 相位 + 空气路径红化近似
//     （atmosphereAbsorptionGround 的简化类比：云点越贴地平线、太阳越低 ⇒
//     光程越长 ⇒ 越红越暗）。
//
// 额外补的一条：大尺度 region 调制覆盖率，打散噪声体 1/频率
// = 10000 格的平铺格纹（复用 tyndall 的 tyndallRegion）。
//
// lane 布局（CameraUBO.highCloudParams，20 float = 5 vec4，偏移 944）：
//   hc0 = 启用, 高度偏移（距体积云顶，格）, 尺度, 速度
//   hc1 = 密度, 覆盖率, 八度数, 淡出距离（格）
//   hc2 = 亮度, 保留, 保留, 保留
//   hc3 = 保留（宿主写 0）
//   hc4 = 保留（宿主写 0）
//
// 契约与退役前逐字一致：返回 vec4(预乘 rgb, 透射率)，distance 给出命中距离。
// 两个消费点（rt_test.rmiss 传 pixelAngle=0、volumetric_clouds.comp 传真值）
// 一行都不用改。
// =============================================================================

const float PLANE_CLOUD_PI = 3.14159265359;
// 八度几何：OCTAVE_SCALE 2.8 / OCTAVE_FADE 0.45（CloudSettings.glsl 出厂值）。
const float PLANE_CLOUD_OCTAVE_SCALE = 2.8;
const float PLANE_CLOUD_OCTAVE_FADE = 0.45;
#ifndef PLANE_CLOUD_NOISE_FREQ
// `cloudPos * 0.0001 / PLANE_CLOUD_SCALE`。1/0.0001 = 10000 格为
// 噪声体的一个完整 wrap。
#define PLANE_CLOUD_NOISE_FREQ 0.0001
#endif
// cloudShapeNoise 的 z 切片。选 0.37 与 tyndall 的 region 场常量同一层，
// 避免与体积云主形状读到同一层而产生相关性。
const float PLANE_CLOUD_NOISE_SLICE = 0.37;
// 去平铺 region 频率，与 tyndall 的 region 同源（周期 ≈ 8333 格）。
const float PLANE_CLOUD_REGION_FREQ = 0.00012;
// 命中距离上限。 PLANE_CLOUD_FADE_DISTANCE 默认 100000，这里留足
// 余量给地平线附近的掠射视线。
const float PLANE_CLOUD_MAX_DISTANCE = 300000.0;
const int PLANE_CLOUD_MAX_OCTAVES = 8;
// 前向散射瓣的偏心率。
const float PLANE_CLOUD_PHASE_G = 0.60;
const float PLANE_CLOUD_PHASE_GAIN = 3.2;
const float PLANE_CLOUD_POWDER = 2.0;
const float PLANE_CLOUD_AMBIENT_GAIN = 0.45;
// 空气路径消光（RGB 各通道不同 ⇒ 低太阳时自然红化）。
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
    // 与体积云同一口径：只画主世界。
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

    // ---- 1. 固定高度球壳求交 ----
    // 平面云高度 = 体积云顶 + 用户偏移。换算成 tyndallSphereRoots 的半径：
    // 该函数的球面在水平偏移 0 处的高度恰为 cam.cloudParams0.x + (radius - TYNDALL_RADIUS)。
    float planeAltitude = cam.cloudParams0.x + cam.cloudParams0.y + heightOffset;
    float shellRadius = TYNDALL_RADIUS + (planeAltitude - cam.cloudParams0.x);
    vec2 roots;
    if(!tyndallSphereRoots(origin, direction, shellRadius, roots))
        return vec4(0.0, 0.0, 0.0, 1.0);
    // 相机在壳下方朝上看 ⇒ 取近根（壳底面）；相机在壳上方朝下看 ⇒ 近根也是它。
    // 两者都为负（相机在壳下方朝下看）⇒ 直接无命中。
    float t = roots.x > 0.0 ? roots.x : roots.y;
    if(t <= 0.0 || t > PLANE_CLOUD_MAX_DISTANCE)
        return vec4(0.0, 0.0, 0.0, 1.0);

    vec3 hitPos = origin + direction * t;

 // ---- 2. 多八度 2D FBM----
    vec2 wind = vec2(1.0, 0.5) * (speed * tyndallTime() * 20.0);
    vec2 cloudPos = hitPos.xz + wind;
    vec2 octaveWind = wind * PLANE_CLOUD_OCTAVE_SCALE;
    float frequency = PLANE_CLOUD_NOISE_FREQ / scale;
    // 距离预过滤：远处样本读噪声 mip，避免点采样出方块（与体积云共用工具）。
    // pixelAngle=0（反射路径）⇒ lod 0，逐位旧档行为。
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

    // ---- 3. 去平铺：大尺度 region 调制覆盖率 ----
    // 噪声体在 1/频率 = 10000 格处 wrap，天顶大范围铺开时能看出格纹。region 场
 // 周期 ≈ 8333 格且互相错开，乘上去之后重复图案被打破（没有这一条，
    // 它的 noisetex 靠更长的有效周期掩盖）。
    float region = tyndallRegion(vec3(hitPos.x * PLANE_CLOUD_REGION_FREQ,
                                      0.37,
                                      hitPos.z * PLANE_CLOUD_REGION_FREQ)).x;
    accum *= mix(0.55, 1.35, region);

 // ---- 4. 云量阈值（: clamp(density + AMOUNT - 1, 0, 1)）----
    float cloudAmount = clamp(accum + coverage - 1.0, 0.0, 1.0);
    if(cloudAmount <= 0.0)
        return vec4(0.0, 0.0, 0.0, 1.0);

 // ---- 5. 距离淡出 + 不透明度----
    cloudAmount *= exp2(-t / fadeDistance);
    if(cloudAmount <= 0.0)
        return vec4(0.0, 0.0, 0.0, 1.0);
    float opacity = 1.0 - exp2(-densityKnob * cloudAmount);
    float alpha = opacity * opacity;
    if(alpha < 0.0015)
        return vec4(0.0, 0.0, 0.0, 1.0);
    distance = t;

    // ---- 6. 光照 ----
    // 已验收的日落调色（与体积云同源）。
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

    // 空气路径红化：光从太阳到云点穿过的空气量由云点本地"上"方向与太阳的夹角
 // 决定（贴地平线 ⇒ 1/cos 变长）。用 atmosphereAbsorptionGround 做
    // 完整积分，这里取其单调性做解析近似。
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

    // 契约：vec4(预乘 rgb, 透射率)。两个消费点都按 emission-absorption 合成。
    return vec4(color * alpha, 1.0 - alpha);
}

#endif  // HIGH_CLOUD_FIELD_GLSL
