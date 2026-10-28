// =====================================================
// 玻璃电介质（真折射）公共模块 —— 2026-10-01
//
// 现状与动机
//   现在玻璃的"内部折射"是拿 POM 浮雕位移冒充的（rchit 写 parallaxRayTravel，
//   玻璃面与玻璃后接收面各自加上这个位移）。那份位移是**逐纹理 texel 量化**的：
//   视向一动，采到的 texel 一翻，透过玻璃看到的那一面整体跳一格 ⇒ 玻璃上出现与
//   玻璃纹理同形的颗粒，只跟着本地直射光走（太阳那份被 shadowTransmittance 压掉），
//   采样数/候选数都动不了它。
//
// 覆盖率口径（docs/GLASS_COVERAGE_20260914.md）：
//     "Glass blocks and panes use opaque **coverage**, OpenPBR transmission weight 1,
//      and an interior dielectric volume." / "Glass IOR is 1.5; ice retains 1.309
//      and water retains 1.333." / "Clear glass is classified by block semantics even
//      when Minecraft's raster layer is cutout."
//   它的玻璃路径**没有 POM 分支**，内部折射是真的介质折射 ⇒ 从根上没有这类量化噪声。
//
// 本文件做什么
//   只提供电介质几何/辐射数学：Snell 折射、非偏振 Fresnel、以及"按格计的染料
//   Beer-Lambert 衰减"。真正的光线推进（进/出界面、TIR 回退、段长与叠加染料）
//   由 rgen 的玻璃走查按 hitDistance 驱动，本文件不 trace、不依赖 rgen 里的任何
//   函数（GLSL 单遍编译：本文件被 include 在最前面，只能用自己声明的东西）。
//
// 物理约定
//   * IOR：玻璃 1.5（空气 1.0）。入射角由"指向界面"的方向与朝向入射侧的面法线给出。
//   * 染料：每个方块格的透射色 blockTint（RGB <= 1，就是现有的
//     computeGlassBlockTransmission 结果）。体内走过 lengthInBlocks 格 ⇒
//     段透射 = pow(blockTint, lengthInBlocks)（等价 Beer-Lambert sigma=-ln(tint)）。
//   * 薄片（玻璃板 pane，厚约 1/16 格）：不做体内折射，仍用薄片 Fresnel 模型，
//     只是**不再叠加 POM 位移**。用 glassIsThinSheet() 判断。
// =====================================================

#ifndef GLASS_DIELECTRIC_COMMON_GLSL
#define GLASS_DIELECTRIC_COMMON_GLSL

#ifndef GLASS_IOR
#define GLASS_IOR 1.5
#endif

//   "Glass IOR is 1.5; ice retains 1.309 and water retains 1.333."
// 粘液/蜂蜜参考没有给值 ⇒ 沿用 GLASS_IOR，不自行编造。
#ifndef GLASS_ICE_IOR
#define GLASS_ICE_IOR 1.309
#endif

// Minecraft 玻璃板厚 2/16 格（= 0.125）。这是**最小真实板厚**：
// 2026-10-17 之前这里错用 GLASS_THIN_SHEET_MAX_THICKNESS(0.25) 当 clamp 下限，
// 把玻璃板当成两倍厚 ⇒ 出射点位移与染料光程都翻倍。
// GLASS_THIN_SHEET_MAX_THICKNESS 仍然只表示"低于此厚度按薄片处理"的判据阈值。
#ifndef GLASS_MIN_SLAB_THICKNESS
#define GLASS_MIN_SLAB_THICKNESS 0.125
#endif

// 低于这个厚度的玻璃按薄片处理（Minecraft 玻璃板 1/16 格 = 0.0625）。
#ifndef GLASS_THIN_SHEET_MAX_THICKNESS
#define GLASS_THIN_SHEET_MAX_THICKNESS 0.25
#endif

// 单条路径允许穿过的玻璃界面数上限（进/出各算一次）。超过就按不透明处理，
// 防止两面玻璃无限互射。
#ifndef GLASS_MAX_INTERFACE_STEPS
#define GLASS_MAX_INTERFACE_STEPS 4
#endif

// 段透射的下限，避免 pow() 在长路径上把颜色压成 0 之后没法再抬起来。
#ifndef GLASS_SEGMENT_TRANSMITTANCE_FLOOR
#define GLASS_SEGMENT_TRANSMITTANCE_FLOOR 1.0e-4
#endif

// 解析平板出射点被夹回入射格时保留的边缘余量（格）。
// 必须大于调用方在出射点上的起点偏移之和（rgen 是 0.01 起点 + 0.01 tmin = 0.02），
// 否则夹到边界后仍会被推回格外的空气里。见 glassSlabExitPoint 的说明。
#ifndef GLASS_EXIT_CELL_MARGIN
#define GLASS_EXIT_CELL_MARGIN 0.05
#endif

// 折射率倒数，供调用方做 eta 计算时复用（etaI/etaT 用 GLASS_IOR 直接算即可）。
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
    // 下限是**真实最薄板厚**（玻璃板 0.125），不是"薄片判据"阈值 0.25 ——
    // 用后者会把玻璃板算成两倍厚（2026-10-17 修）。
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

    // insideDirection 指向体内 => dot(insideDirection, n) < 0。
    float cosInside = max(-dot(insideDirection, n), 1.0e-4);
    vec3 exitPoint =
        entryPoint + insideDirection * (max(thickness, 0.0) / cosInside);

    // 入射点正好落在格边界上，直接 floor 会有一半概率选到隔壁格，所以先沿入射
    // 方向迈一小步进到玻璃体内再取整。
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
