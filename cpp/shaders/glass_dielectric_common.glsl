
#ifndef GLASS_DIELECTRIC_COMMON_GLSL
#define GLASS_DIELECTRIC_COMMON_GLSL

#ifndef GLASS_IOR
#define GLASS_IOR 1.5
#endif

#ifndef GLASS_ICE_IOR
#define GLASS_ICE_IOR 1.309
#endif

#ifndef GLASS_MIN_SLAB_THICKNESS
#define GLASS_MIN_SLAB_THICKNESS 0.125
#endif

#ifndef GLASS_THIN_SHEET_MAX_THICKNESS
#define GLASS_THIN_SHEET_MAX_THICKNESS 0.25
#endif

#ifndef GLASS_MAX_INTERFACE_STEPS
#define GLASS_MAX_INTERFACE_STEPS 4
#endif

#ifndef GLASS_SEGMENT_TRANSMITTANCE_FLOOR
#define GLASS_SEGMENT_TRANSMITTANCE_FLOOR 1.0e-4
#endif

#ifndef GLASS_EXIT_CELL_MARGIN
#define GLASS_EXIT_CELL_MARGIN 0.05
#endif

const float GLASS_IOR_INVERSE = 1.0 / GLASS_IOR;

// -----------------------------------------------------
// 逐材质折射率（零成本：只改常量选择，不动任何射线）
//   transparentProfile：rgen 的 getTransparentProfile()
//     0 = 染色玻璃 / 清玻璃，1 = 遮光玻璃，2 = 冰族，
//     3 = 粘液，4 = 蜂蜜（见 2026-10-13 的嵌套优先级表）。
//   只有冰有参考值（1.309）；粘液/蜂蜜参考未给 ⇒ 沿用 1.5。
// -----------------------------------------------------
float glassIorForProfile(int transparentProfile)
{
    return transparentProfile == 2 ? GLASS_ICE_IOR : GLASS_IOR;
}

// -----------------------------------------------------
// 解析式电介质平板：由命中面推出该玻璃片的实际厚度（**不需要额外射线**）
//
//   玻璃块（满方块）：面在格心 +/-0.5     => 厚度 1.0
//   玻璃板（pane，2/16 厚）：面在格心 +/-0.0625 => 厚度 0.125
//
// 判据只用"命中点到所在方块格心的距离"（hitPos 的小数部分 . 面法线），纯 ALU，
// 没有第二次 trace。切向面（斜切）会被 clamp 到 [最小厚度, 1.0]。
// -----------------------------------------------------
float glassSlabThicknessFromFace(vec3 hitPosition, vec3 faceNormal)
{
    vec3 n = normalize(faceNormal);
    vec3 cellLocal = fract(hitPosition) - vec3(0.5);
    float halfThickness = abs(dot(cellLocal, n));
    return clamp(
        2.0 * halfThickness,
        GLASS_MIN_SLAB_THICKNESS,
        1.0
    );
}

// -----------------------------------------------------
// 解析式平板折射的出射点。
//   entryPoint    : 入射面命中点
//   outwardNormal : 该面的朝外法线（函数内按入射侧翻正）
//   incidentDir   : 入射方向（指向表面，即相机方向）
//   thickness     : 板厚（格）
//   ior           : 玻璃折射率
//
// 物理：平板两面相距 thickness，内部方向 T = refract(I, N, 1/ior)，出射点
//   E = P + T * (thickness / |dot(T, N)|)。
// 平行板的出射方向与入射方向相同（只整体横移），所以调用方仍沿原方向继续透射，
// 只是把起点从入射面挪到出射面 ---- 这正是旧 POM 位移想冒充的东西，但这里是
// **世界空间连续**的解析量，不含任何纹理 texel 量化。
//
// 平行板不会发生全反射（sin(theta_t) = sin(theta_i)/n < 1/n 只在掠射极限取等）；
// 若几何不平行导致 refract 退化（返回零向量），这里直接退回沿原方向平移。
//
// ---- 2026-10-01：出射点必须夹回**入射格**内（修"透过玻璃穿墙"）----
// 平板模型有个隐含前提：内部光线一定走到**对面**才离开玻璃。但折射的横移量是
//   thickness * tan(theta_t)
// IOR 1.5 下一格厚玻璃最大 0.89 格 —— 入射点靠近格边时，内部光线其实先撞上**侧面**，
// 而按"走到对面"算出来的出射点已经横移到隔壁那一列了。隔壁若正好是嵌玻璃的那堵
// 1 格厚的墙（墙后就是空气），透射射线就从墙**背后**起步，整堵墙被跳过 ⇒
// 斜视玻璃时紧邻的墙被透出，能看到墙后面的东西。
//
// 夹回来的范围正是"内部光线会从侧面出去"的那些像素：真实情况下它们本来就该被
// 隔壁方块挡住，所以不需要保留那份横移，视觉上只是玻璃边缘附近的错位变小。
// 余量 GLASS_EXIT_CELL_MARGIN 兼顾调用方附加的起点偏移，保证夹完之后射线起点
// 仍然落在玻璃格内。
// -----------------------------------------------------
vec3 glassSlabExitPoint(
    vec3 entryPoint,
    vec3 outwardNormal,
    vec3 incidentDir,
    float thickness,
    float ior
)
{
    vec3 n = normalize(outwardNormal);
    if(dot(incidentDir, n) > 0.0)
    {
        n = -n;
    }

    float eta = 1.0 / max(ior, 1.0);
    vec3 insideDirection = refract(incidentDir, n, eta);
    if(dot(insideDirection, insideDirection) < 1.0e-8)
    {
        insideDirection = incidentDir;
    }

    float cosInside = max(-dot(insideDirection, n), 1.0e-4);
    vec3 exitPoint =
        entryPoint + insideDirection * (max(thickness, 0.0) / cosInside);

    vec3 glassCellMin = floor(entryPoint + normalize(incidentDir) * 1.0e-3);
    vec3 glassCellMax = glassCellMin + vec3(1.0);

    return clamp(
        exitPoint,
        glassCellMin + vec3(GLASS_EXIT_CELL_MARGIN),
        glassCellMax - vec3(GLASS_EXIT_CELL_MARGIN)
    );
}

// 沿**视线方向**到出射面的距离（用于 RR 深度等标量用途）。
float glassSlabTravelAlongView(
    vec3 outwardNormal,
    vec3 viewDir,
    float thickness
)
{
    vec3 n = normalize(outwardNormal);
    float cosView = max(abs(dot(normalize(viewDir), n)), 1.0e-4);
    return max(thickness, 0.0) / cosView;
}

#endif
