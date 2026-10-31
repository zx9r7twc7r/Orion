#ifndef HIT_PAYLOAD_GLSL
#define HIT_PAYLOAD_GLSL

// =============================================================================
// 2026-10-31 payload 瘦身第二阶段（68 B -> 52 B）：
//   vec3 normal  -> uint normalPacked   （八面体 2 x 16 bit，省 8 B）
//   vec3 albedo  -> uint albedoPacked   （unorm8 x 4，省 8 B）
// 精度：法线用 16 bit/轴（亚度级），albedo 用 8 bit/通道（源贴图本来就是 8 bit，
// 且 AnyHit 的玻璃染色累积只会乘 <=1 的系数，不会越界）⇒ 无可见损失。
// emission 保持 vec3：它是 HDR，8 bit 编码会损失动态范围，收益不抵风险。
// =============================================================================
uint packNormalOctahedral(vec3 n)
{
    n /= max(abs(n.x) + abs(n.y) + abs(n.z), 1.0e-8);
    vec2 e = n.xy;
    if (n.z < 0.0)
    {
        e = (1.0 - abs(n.yx)) *
            vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    uvec2 q = uvec2(clamp(e * 0.5 + 0.5, vec2(0.0), vec2(1.0)) * 65535.0 + 0.5);
    return q.x | (q.y << 16u);
}

vec3 unpackNormalOctahedral(uint p)
{
    vec2 e = vec2(uvec2(p & 0xffffu, p >> 16u)) / 65535.0 * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0)
    {
        n.xy = (1.0 - abs(n.yx)) *
               vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

uint packAlbedoUnorm(vec3 a)
{
    return packUnorm4x8(vec4(clamp(a, vec3(0.0), vec3(1.0)), 1.0));
}

vec3 unpackAlbedoUnorm(uint p)
{
    return unpackUnorm4x8(p).rgb;
}

struct HitPayload {
    float hitDistance;
    // 2026-10-31 第二阶段（normal/albedo 压缩）实测**全屏变黑**，已回退成 vec3。
    // 原因未定位（八面体编码与 unorm8 编码都可能是元凶），两处同时改无法区分。
    // 复现路径：hit_payload.glsl 的 packNormalOctahedral/packAlbedoUnorm 保留在下方，
    // 但字段改回 vec3 ⇒ 打包版本从 *.bak_pre_pack_20261031 与 git 历史恢复后逐个字段重试。
    vec3  normal;
    // 2026-10-31 单字段试验（第一阶段）：albedo -> unorm8x4。
    // 与 normal 的八面体压缩分两次做，避免上次"两个一起改导致全黑却无法区分"。
    uint  albedoPacked;
    uint state;
    float roughness;
    float f0Metal;
    vec3 emission;
    float parallaxRayTravel;
    float pomSelfShadow;
    float pomMicroOcclusion;
    float glassAlpha;

};

#endif
