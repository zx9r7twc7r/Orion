#version 460
// ===========================================================================
// 2026-10-19 折射焦散重做（Clearwater 参考实现）
//
// 参考的片元阶段只有三行物理：源面积（vSrc 的屏幕导数叉积）× (C/L)²。
// 平坦水面 = 1（未扰动的波前），聚焦 = >1 的加法能量；折叠/散焦 <1 自然变暗，
// 但消费端只取 max(I-1,0)（与旧解析焦散的能量口径一致）。
//
// 每个通道单独一遍（不同 IOR）⇒ 这里是标量 I，宿主按通道掩码写 R/G/B。
// 加法混合让 3×3 实例在折叠处自然叠加（同一接收点被多个源平铺覆盖 = 聚焦）。
// ===========================================================================
layout(location = 0) in vec2 vSrc;
layout(location = 0) out vec4 outColor;

layout(push_constant) uniform WaterCausticMapPush
{
    vec4 sunDirection;
    // w = (C/L)²：把纹素面积换算回无量纲面积比（平坦水面恰为 1）
    vec4 params;
} pc;

void main()
{
    const vec2 sourceDx = dFdx(vSrc);
    const vec2 sourceDy = dFdy(vSrc);
    // 参考：float area = abs(a.x*b.y - a.y*b.x);
    const float area = abs(sourceDx.x * sourceDy.y - sourceDx.y * sourceDy.x);
    // 参考的硬上限 40：折叠处面积比趋近 0，倒数会发散。
    const float intensity = min(area * pc.params.w, 40.0);
    outColor = vec4(intensity);
}
