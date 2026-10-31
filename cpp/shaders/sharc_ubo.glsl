// ============================================================================
// 2026-10-11 sharc_ubo.glsl —— SHaRC 的「宿主下发的量」到「SHARC 结构体」的唯一真源
//
// 谁用：rt_test.rgen（update + query 两档都在这一个模块里，见 sharcMode 的说明）
//       rt_sharc_resolve.comp（Resolve pass）
// 前置条件（顺序不能反）：
//   1) `#include "sharc_enable.glsl"`  —— 扩展 + NVIDIA 头 + 撤掉 HLSL 别名宏
//   2) 声明 CameraProperties 块体（`#include "camera_ubo.glsl"`），且实例名必须是 `cam`
//   3) `#include "sharc_ubo.glsl"`      —— 本文件
//
// 为什么集中在这一个文件：这三个函数（地址重建 / 模式闸门 / 参数打包）此前只存在于
// rt_test.rgen 里，而 Resolve pass 需要一模一样的版本 —— 各自抄一份就是两份会漂移的
// 副本（本工程刚在 camera_ubo.glsl 上吃过这个教训，那里是"块体唯一真源"）。
//
// 数据流：C++ 侧 cpp/VulkanCore.cpp
//   · 四块缓存缓冲的 64 位设备地址 → CameraUBO.sharcBuffersLo/Hi（两个 uvec4）
//   · sceneScale / 容量 / radianceScale / 模式 → CameraUBO.sharcCacheParams（一个 uvec4）
// 着色器侧只做还原，不做任何算术决定（容量必须与 C++ 真正分配的元素数逐位一致）。
// ============================================================================

#define SHARC_ROUGHNESS_THRESHOLD 0.4

// 缓存四块缓冲的 64 位设备地址。分量顺序与 C++ 侧逐位一致：
//   x = hashEntries / y = accumulation / z = resolved / w = lock
// 低 32 位在 sharcBuffersLo[分量]、高 32 位在 sharcBuffersHi[分量]；按
// GL_EXT_buffer_reference 的约定**低 32 位对应 .x**，所以 packUint2x32(uvec2(lo,hi))
// 就是那个地址本身。
uint64_t sharcCacheAddress(uint sharcSlot)
{
    return packUint2x32(
        uvec2(cam.sharcBuffersLo[sharcSlot], cam.sharcBuffersHi[sharcSlot]));
}

// 模式闸门。档位（宿主经 CameraUBO.sharcCacheParams.w 下发）：
//   0 = 完全不碰缓存（未启用 / 还没清零）
//   1 = 5a 寻址探针（一次性，只读 + 回写供宿主校验）
//   2 = 只 query（不 update；用于量"只读缓存"的收益与画面）
//   3 = update + query（默认）
//   4 = 只 update（不 query）—— 用来把"query 本身带来的变化"与"缓存被写入"分开：
//       3 → 4 的差就是 query 的贡献，4 → 0 的差就是缓存维护的代价。
uint sharcMode()
{
    if(cam.sharcCacheParams.x == 0u)
    {
        return 0u;
    }
    for(uint sharcSlot = 0u; sharcSlot < 4u; ++sharcSlot)
    {
        if(cam.sharcBuffersLo[sharcSlot] == 0u &&
           cam.sharcBuffersHi[sharcSlot] == 0u)
        {
            return 0u;
        }
    }

    return min(cam.sharcCacheParams.w, 4u);
}

// update 是否生效（模式 3 / 4）。
bool sharcUpdateActive()
{
    const uint sharcCurrentMode = sharcMode();
    return sharcCurrentMode == 3u || sharcCurrentMode == 4u;
}

// query 是否生效（模式 2 / 3）。
bool sharcQueryActive()
{
    const uint sharcCurrentMode = sharcMode();
    return sharcCurrentMode == 2u || sharcCurrentMode == 3u;
}

// 打包 SharcParameters。
SharcParameters sharcBuildParameters()
{
    SharcParameters sharcParameters;

    sharcParameters.hashGridParameters.cameraPosition = cam.viewInverse[3].xyz;
    sharcParameters.hashGridParameters.logarithmBase = SHARC_GRID_LOGARITHM_BASE;
    sharcParameters.hashGridParameters.sceneScale =
        uintBitsToFloat(cam.sharcCacheParams.x);
    sharcParameters.hashGridParameters.levelBias = float(SHARC_GRID_LEVEL_BIAS);

    sharcParameters.hashGridData.capacity = cam.sharcCacheParams.y;
    sharcParameters.hashGridData.hashEntriesBuffer =
        RWStructuredBuffer_uint64_t(sharcCacheAddress(0u));

    sharcParameters.radianceScale = uintBitsToFloat(cam.sharcCacheParams.z);
    sharcParameters.accumulationBuffer =
        RWStructuredBuffer_SharcAccumulationData(sharcCacheAddress(1u));
    sharcParameters.resolvedBuffer =
        RWStructuredBuffer_SharcPackedData(sharcCacheAddress(2u));

    return sharcParameters;
}

// 打包 SharcResolveParameters（只有 Resolve pass 用）。
//   cameraPositionPrev —— 上一帧相机位置，用于"相机快速移动时给相邻层级混合"；
//                         UBO 里现成有 prevViewInverse。
//   accumulationFrameNum —— 时域累积窗口上限（SDK 上限 1024，越小越"跟手"、
//                         越大越稳；这里取 512 作为起步值，后面按观感调）。
//   responsiveFrameNum  —— 只有 Responsive Lighting 档才用；本工程没开（默认 0），
//                         但字段必须填（resolve 里会 clamp）。
//   staleFrameNumMax    —— 多少帧没新样本就驱逐该元素。SDK 建议用 1024 起步，
//                         占用率过高时调小它（驱逐更激进）。
//   frameIndex          —— 帧号，用于时域抖动。
SharcResolveParameters sharcBuildResolveParameters()
{
    SharcResolveParameters resolveParameters;
    resolveParameters.cameraPositionPrev = cam.prevViewInverse[3].xyz;
    resolveParameters.accumulationFrameNum = 10u;
    resolveParameters.responsiveFrameNum = 10u;
    resolveParameters.staleFrameNumMax = 64u;
    resolveParameters.frameIndex = cam.frameCount;
    return resolveParameters;
}
