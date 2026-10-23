// ============================================================================
// 2026-10-11 SHaRC（World-Space Hash Radiance Cache）接入开关 —— 所有消费者共用
//
// 任何要用 SHaRC 的着色器都只 #include 这一个文件来拿齐"扩展 + 宏 + NVIDIA 头"。
// 不要在消费者里自己写 #define / #include：下面两件事很容易写错，而且错了之后是
// **静默**的（编译通过、画面看不出、只是结果不对或某天突然炸）。
//
// 一、头文件顺序（写错 ⇒ 未定义类型，这个还算容易发现）
//     SharcGlslHelpers.h **没有 include guard** —— 它是"环境注入"头，只定义 HLSL
//     风味别名宏和三个 buffer_reference 声明（RWStructuredBuffer_uint64_t /
//     _uint / _uint4）。而 HashGridCommon.h 里的 hashEntriesBuffer 用的正是
//     RWStructuredBuffer_uint64_t，所以 Helpers 必须在 SharcCommon.h 之前。
//
// 二、宏泄漏（写错 ⇒ 静默改语义，这个才是真正的坑）
//     Helpers 定义了 GLSL 里**不存在**的 HLSL 别名：float2/float3/float4、
//     uint2..4、int2..4、lerp、saturate、rcp、countbits、asfloat、asuint、
//     f32tof16、f16tof32、InterlockedAdd/Or/Exchange/CompareExchange。宏不认作用域，
//     会横扫**本文件之后**的整个翻译单元。
//     本工程的 rt_test.rgen 自己也在用 saturate()/lerp() 这些名字（2026-10-11 查过：
//     目前只出现在注释里，而预处理器不展开注释，所以还没炸 —— 但下一个人写一行真代码
//     就会静默变成 clamp(x,0,1)/mix）。因此这里在 include 结束后把那些**通用数学名**
//     全部 #undef 掉：SHARC 的函数体在 include 那一刻就已经展开完了，之后不再需要它们，
//     而本工程自己的 saturate/lerp 恢复本来的含义。
//     只 undef 通用名，SHARC 私有的 RW_STRUCTURED_BUFFER / BUFFER_AT_OFFSET /
//     HASH_GRID_CONST / HASH_GRID_LOOP_ATTR 保留（不会跟本工程撞名，且将来若在同一
//     翻译单元里再 include 一次 SHARC 头也就不用重设）。
//
// 为什么集中在这一个文件：Update / Resolve / Query 三个 pass 需要同一套东西，各自抄一遍
// 就是三份会漂移的副本 —— 本工程刚在 camera_ubo.glsl（"块体唯一真源"）上吃过这个亏。
//
// 启用策略：ORION_SHARC 未打开时 C++ 侧不创建缓存，CameraUBO 里四个地址为 0，
// 着色器的模式闸门（见 rt_test.rgen 的 sharcMode()）因此恒为 0 ⇒ 完全不碰缓存。
// 本文件只保证"类型与宏齐备、能编译"，本身不含任何行为。
// ============================================================================

// 必需的扩展。清单与顺序取自 NVIDIA 的 SharcGlslHelpers.h 头部注释（那边是注释掉的，
// 要消费者自己声明）。int64 在 rt_test.rgen 里已经 require 过，重复声明同一扩展是合法的。
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
#extension GL_EXT_shader_atomic_int64 : require
#extension GL_KHR_shader_subgroup_ballot : require
#extension GL_EXT_shader_16bit_storage : enable
#extension GL_EXT_control_flow_attributes : require

// SharcGlslHelpers.h 与 SharcCommon.h 的入口判据（那边写的是 #if 而不是 #ifdef）。
#define SHARC_ENABLE_GLSL 1

// Update pass 口径：默认 0 = Query/Resolve（SharcState 退化成占位 uint，update 系列函数
// 不参与编译）。将来那个做稀疏更新的 rgen 必须在 #include 本文件**之前**定义成 1。
#ifndef SHARC_UPDATE
#define SHARC_UPDATE 0
#endif

#include "sharc/SharcGlslHelpers.h"
#include "sharc/SharcCommon.h"

// ---------------------------------------------------------------------------
// 撤掉 Helpers 的 HLSL 别名宏（理由见文件头"宏泄漏"段）
// ---------------------------------------------------------------------------
#undef float2
#undef float3
#undef float4
#undef uint2
#undef uint3
#undef uint4
#undef int2
#undef int3
#undef int4
#undef float16_t2
#undef float16_t3
#undef float16_t4
#undef lerp
#undef rcp
#undef saturate
#undef countbits
#undef asfloat
#undef asuint
#undef f32tof16
#undef f16tof32
#undef InterlockedAdd
#undef InterlockedOr
#undef InterlockedExchange
#undef InterlockedCompareExchange
