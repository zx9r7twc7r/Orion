#version 460
// ===========================================================================
// 2026-10-19 折射焦散重做（Clearwater 参考实现，逐条照搬其结构）
//
// 参考（cpp/.tmp/caustic-research/clearwater-index.html 的 renderCaustics）：
//   · G×G 网格顶点铺满水面，**9 个实例**（3×3）解决折射位移跨平铺边界的接缝；
//   · 每个顶点从波浪贴图取斜率 → 法线 → refract(sun) → 投影到 1 格深的
//     参考接收面；**片元阶段**用 dFdx/dFdy 求"源面积/接收面积"的倒数（面积比）；
//   · 加法混合（ONE/ONE）+ 每通道不同 IOR 画三遍 ⇒ 自然色散。
//
// 与参考的对应关系：
//   · 波浪贴图 = 宿主 30 Hz 预烘的 FFT 斜率图（binding 0，.rg 基础层 / .ba 细节层），
//     与可见水面、旧解析焦散**同一真源**；
//   · 参考的 P.y（波面高度）我们没有：FFT 只输出斜率（见 water_fft.comp 的
//     finalize），故网格落在 y = 0 的水面源平面上，光程从源平面起算 —— 与旧
//     解析焦散的 "one-block reference receiver plane" 完全同一定义；
//   · 平铺周期 L = CameraUBO.waterFftParams.x（格），地图覆盖 L×L，采样端用
//     worldXZ / L 直接取（REPEAT），世界锚定、不跟相机走。
//
// 输出图 UV 约定：地图 [0,1]² ↔ 世界 XZ 对 L 取模的 [0,L)²，但**键是源点**：
// 参考的 c = (F.xz - uShift)/L + off，uShift 是平坦水面（绿档 IOR）把源点搬到
// 接收面的常量位移。于是采样端用平坦水面源点 / L 就能取到正确纹素（见
// rt_test.rgen 的 evaluatePrimaryWaterReceiverCaustics）。实例偏移在投影之后
// 加到地图 UV 上；L = 128 时 ±1 的两圈基本都在 [0,1] 外，与参考同构、只补
// 边界上被折射推出去的那几条亚纹素缝隙。
//
// 2026-10-19 反射焦散（同一张图的第 1 个数组层）：
//   sunDirection.w = 0 ⇒ 折射到下方 y = -referenceDepth（层 0）
//   sunDirection.w = 1 ⇒ 镜面反射到上方 y = +referenceDepth（层 1）
// 反射是单通道（镜面反射无 IOR 色散），同一套网格/实例/面积比与键约定。
// ===========================================================================
layout(push_constant) uniform WaterCausticMapPush
{
    // xyz = 指向太阳的单位向量（与 sky_model_common.glsl 的 sunDirection() 同一公式）
    // w = 0 折射 / 1 反射（选目标层与投影方向）
    vec4 sunDirection;
    // x = FFT 平铺周期 L（格） / y = 参考接收面深度（格）
    // z = 该遍的 IOR（1.3315 / 1.3335 / 1.3365，反射层忽略）
    // w = (C/L)²，片元阶段用
    vec4 params;
} pc;

layout(set = 0, binding = 0) uniform sampler2D waterFftSlope;

layout(location = 0) out vec2 vSrc;

const uint GRID = 256u;
const float GRID_F = 256.0;
// 与宿主 kWATER_FFT_DETAIL_TILE / 着色器 water_wave_field.glsl 的
// WATER_FFT_DETAIL_TILE 一致：细节层平铺 32 格。
const float DETAIL_TILE = 32.0;

void main()
{
    // 每个四边形 6 个顶点（两个三角形），无顶点缓冲/索引缓冲。
    uint quad = uint(gl_VertexIndex) / 6u;
    uint corner = uint(gl_VertexIndex) % 6u;
    uint ix = quad % GRID;
    uint iz = quad / GRID;

    vec2 cornerOffset;
    if(corner == 0u)      cornerOffset = vec2(0.0, 0.0);
    else if(corner == 1u) cornerOffset = vec2(1.0, 0.0);
    else if(corner == 2u) cornerOffset = vec2(0.0, 1.0);
    else if(corner == 3u) cornerOffset = vec2(1.0, 0.0);
    else if(corner == 4u) cornerOffset = vec2(1.0, 1.0);
    else                  cornerOffset = vec2(0.0, 1.0);

    // 本实例负责的源平铺内坐标（0..1）。
    vec2 uv = (vec2(float(ix), float(iz)) + cornerOffset) / GRID_F;

    // 3×3 实例：off ∈ {-1,0,1}²。与参考的 gl_InstanceID 展开逐字一致。
    vec2 instanceOffset = vec2(
        float(gl_InstanceIndex % 3u) - 1.0,
        float(gl_InstanceIndex / 3u) - 1.0
    );

    // 与参考逐字一致：9 个实例全部采**同一块**源平铺（aUV 不随实例偏移），
    // 实例只把投影后的地面位置整体平移 ±L。波场本身是周期的 ⇒ 同块内容平移
    // 一整格就是邻铺内容；于是 ±1 两圈只负责补"被折射推过平铺边界"的那几条
    // 边纹素（否则地图四周会留一圈没写过的 0 = 暗缝）。
    // 与可见水面同源的两层斜率：基础层 tile L、细节层 tile 32。
    // 顶点阶段没有隐式导数 ⇒ 必须 textureLod 显式给 0（与参考 textureLod(...,0) 一致）。
    const vec2 baseSlope = textureLod(waterFftSlope, uv, 0.0).rg;
    const vec2 detailSlope = textureLod(
        waterFftSlope,
        uv * (pc.params.x / DETAIL_TILE),
        0.0
    ).ba;
    const vec2 slope = baseSlope + detailSlope;

    // 与 water_wave_field.glsl 的法线约定相同：gradient = (dh/dx, dh/dz)。
    const vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));

    // 入射光方向 = -sunDirection（指向太阳的向量取反 = 光线传播方向）。
    const vec3 incoming = -normalize(pc.sunDirection.xyz);

    // 本实例源平面上的世界坐标（局部于该平铺；vSrc 的导数只需要相对量）。
    const float tilePeriod = pc.params.x;
    const vec3 sourceWorld = vec3(uv.x * tilePeriod, 0.0, uv.y * tilePeriod);

    vec3 receiverWorld;
    vec2 flatShiftXZ;
    if(pc.sunDirection.w > 0.5)
    {
        // ---- 反射层：水和镜子一样把太阳送回空中，接收面在 y = +H ----
        // 与 traceWaterReflectedSun 的 reflect(sunDirection, +Y) 同一约定。
        const vec3 outgoing = normalize(reflect(incoming, normal));
        // 太阳在地平线下时宿主不录制本 pass；仍钳住向上分量作防御。
        const float up = max(outgoing.y, 1.0e-4);
        receiverWorld = sourceWorld + outgoing * (pc.params.y / up);

        // 平坦水面的反射方向 = (-sx, sy, -sz)；减去它的位移，键仍是源点。
        const vec3 flatOutgoing = normalize(
            reflect(incoming, vec3(0.0, 1.0, 0.0)));
        flatShiftXZ =
            flatOutgoing.xz * (pc.params.y / max(flatOutgoing.y, 1.0e-4));
    }
    else
    {
        // ---- 折射层：投影到 y = -H 的水下接收面 ----
        const vec3 refracted = refract(incoming, normal, 1.0 / pc.params.z);
        // 太阳在地平线下时宿主不录制本 pass；这里仍钳住向下分量，避免
        // r.y≈0 时除出天文数字（防御性，不改变正常路径）。
        const float down = min(refracted.y, -1.0e-4);
        receiverWorld = sourceWorld + refracted * (pc.params.y / -down);

        // 参考的 uShift：**平坦水面**（固定用绿档 IOR）把源点搬到接收面的位移。
        // 不减去它，整张图会被这个常量位移搬走 ⇒ 图案与水面源点错位，3×3
        // 折叠的边界也接不上（参考里各通道共用绿档位移，逐通道残差才是色散）。
        const vec3 flatRefracted = refract(
            incoming, vec3(0.0, 1.0, 0.0), 1.0 / 1.3335);
        const float flatDown = min(flatRefracted.y, -1.0e-4);
        flatShiftXZ =
            flatRefracted.xz * (pc.params.y / -flatDown);
    }

    // 参考的 vSrc = aUV*uL：**源**坐标，片元用它的屏幕导数测面积压缩。
    vSrc = sourceWorld.xz;

    // 投影点 → 地图 UV（局部平铺 + 实例偏移），直接当裁剪坐标（正交、w=1）。
    const vec2 mapUv = (receiverWorld.xz - flatShiftXZ) / tilePeriod
        + instanceOffset;
    gl_Position = vec4(mapUv * 2.0 - 1.0, 0.0, 1.0);
}
