// =============================================================================
// Shared ray payload (location = 0) for every shader of the RT world pipeline.
//
// 2026-10-20: single source of truth (was six hand-synced copies).
//
// RT_DEFERRED_MATERIAL undefined (default) -> 20 dwords / 80 B, material carried
//                                            through traversal, exactly as before.
// RT_DEFERRED_MATERIAL defined            -> 11 dwords / 48 B identity only; the
//                                            closest-hit shaders write the hit
//                                            identity, ray generation evaluates
//                                            the material into a *local*
//                                            HitPayload and never touches the ray
//                                            payload again.
// =============================================================================
#ifndef HIT_PAYLOAD_GLSL
#define HIT_PAYLOAD_GLSL

// Set by the deferred closest-hit shaders so the ray-generation dispatcher knows
// which material body owns this hit (opaque vs camera-side glass).
#define HIT_IDENTITY_GLASS_BIT (1u << 13u)

struct HitIdentity {
    // Deferred ray payload (location 0): only what has to survive traversal.
    // The material itself is evaluated after the ray, in ray generation.
    vec3 albedo;              // Any-Hit accumulated tint, or miss sky radiance
    float hitDistance;
    uint state;               // ray purpose | HIT_IDENTITY_GLASS_BIT
    uint instanceIndex;
    uint primitiveIndex;
    float baryX;
    float baryY;
    float glassAlpha;         // Any-Hit accumulator
    float parallaxRayTravel;  // Any-Hit accumulator
};

struct HitPayload {
    float hitDistance;
    vec3 normal;
    vec3 albedo;
    uint state;
    float roughness;
    float f0Metal;
    vec3 emission;
    float parallaxRayTravel;
    float pomSelfShadow;
    float pomMicroOcclusion;
    float glassAlpha;
    float prevOffsetX;
    float prevOffsetY;
    float prevOffsetZ;
};

#endif // HIT_PAYLOAD_GLSL
