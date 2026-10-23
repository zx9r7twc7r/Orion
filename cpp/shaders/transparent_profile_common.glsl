// =============================================================================
// 透明材质剖面（染色玻璃 tint / 冰 ice / 粘液 slime / 蜂蜜 honey）—— 单一定义
//
// 2026-10-23：这段解码原先在 5 个 TU 里各有一份（material_common.glsl、
// rt_test.rchit、rt_test_translucent.rchit、rt_test.rahit、
// rt_test_translucent.rahit），且两份 .rahit 的返回类型已经漂成 int、其余是
// uint。收成一份之后，"改一份忘另一份"这类漂移不再可能发生。
//
// ---------------------------------------------------------------------------
// 宿主 → shader 的契约（写方：cpp/VulkanCore.cpp:54738-54793）
//
//   marker 1  → materialId 1.0                     水分界面（正/背面）
//   marker 2  → materialId 2.0                     清玻璃 / 玻璃板
//   marker 3..18 → materialId 2.0 + 顶点色注入染料  染色玻璃 / 染色玻璃板
//   marker 19..22 → materialId 2.0 + 0.1*(m-18)     tint / ice / slime / honey
//   marker 24 → materialId 6.0                     地狱传送门
//   marker 25 → materialId 0.25                    树叶/花瓣薄片背光
//   marker 23 → materialId 0.0（另有 pad1=0.25 表达雨天地面积水）
//
//   ⇒ 小数位承载 profile：2.1/2.2/2.3/2.4 → 1/2/3/4；小数位为 0 的值（1.0、
//     2.0、6.0、0.0）一律是 profile 0。解码后 profile 只占 3 bit，由
//     makeHitState() 打包进 payload.state 的 RT_STATE_PROFILE_SHIFT(=9) 起。
//     profile 一旦进了 state 就是位域，本函数只在"宿主 → 命中着色器"这一段
//     充当搬运工（32B 地形顶点是 x,y,z|u,v|color|materialId|pad2，没有空字段）。
//
// ---------------------------------------------------------------------------
// ⚠ 为什么用 floor(x + 0.5) 而不是 round(x)
//
// GLSL 规范对 round() 的半整数行为是**实现定义**的，原文："The fraction 0.5
// will round in a direction chosen by the implementation." 而宿主侧存在
// materialId = 0.25f（树叶背光，VulkanCore.cpp:54776），fract(0.25)*10 恰好
// 等于 2.5 —— 正落在那个不确定点上。
//
// 它今天不会出问题：0.25 落在 `materialId <= 0.5` 的不透明带
// （material_resolve.glsl:1347），走不到任何调用本函数的分支。但这条不变量
// 没有任何断言保护 —— 一次路由改动、一个新 marker，它就会变成"换驱动才翻"
// 的隐性问题。floor(x + 0.5) 在所有实现上同值，把不确定性变成确定值，
// 且对现有全部取值（0/1/2/3/4）结果与 round() 完全相同（见下方余量说明）。
//
// ⚠ 步长 0.1 不是 2 的幂，但余量足够
//
// 三顶点同值时重心插值后的残差约 2 ULP：materialId ≈ 2.4 处的 ULP 是
// 2.4 * 2^-23 ≈ 2.9e-7，乘 10 之后是 3e-6，而判定边界在 ±0.5。差 5 个数量级。
// 所以"先插值再解码"本身是安全的，不安全的只有取整方向。
// =============================================================================
#ifndef TRANSPARENT_PROFILE_COMMON_GLSL
#define TRANSPARENT_PROFILE_COMMON_GLSL

uint getTransparentProfileFromRawMaterialId(float materialId)
{
    return uint(
        floor(
            clamp(fract(materialId) * 10.0, 0.0, 4.0) + 0.5
        )
    );
}

#endif // TRANSPARENT_PROFILE_COMMON_GLSL
