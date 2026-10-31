#version 460
layout(push_constant) uniform WaterCausticMapPush
{
    vec4 sunDirection;
    vec4 params;
} pc;

layout(set = 0, binding = 0) uniform sampler2D waterFftSlope;

layout(location = 0) out vec2 vSrc;

const uint GRID = 256u;
const float GRID_F = 256.0;
const float DETAIL_TILE = 32.0;

void main()
{
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

    vec2 uv = (vec2(float(ix), float(iz)) + cornerOffset) / GRID_F;

    vec2 instanceOffset = vec2(
        float(gl_InstanceIndex % 3u) - 1.0,
        float(gl_InstanceIndex / 3u) - 1.0
    );

    const vec2 baseSlope = textureLod(waterFftSlope, uv, 0.0).rg;
    const vec2 detailSlope = textureLod(
        waterFftSlope,
        uv * (pc.params.x / DETAIL_TILE),
        0.0
    ).ba;
    const vec2 slope = baseSlope + detailSlope;

    const vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));

    const vec3 incoming = -normalize(pc.sunDirection.xyz);

    const float tilePeriod = pc.params.x;
    const vec3 sourceWorld = vec3(uv.x * tilePeriod, 0.0, uv.y * tilePeriod);

    vec3 receiverWorld;
    vec2 flatShiftXZ;
    if(pc.sunDirection.w > 0.5)
    {
        const vec3 outgoing = normalize(reflect(incoming, normal));
        const float up = max(outgoing.y, 1.0e-4);
        receiverWorld = sourceWorld + outgoing * (pc.params.y / up);

        const vec3 flatOutgoing = normalize(
            reflect(incoming, vec3(0.0, 1.0, 0.0)));
        flatShiftXZ =
            flatOutgoing.xz * (pc.params.y / max(flatOutgoing.y, 1.0e-4));
    }
    else
    {
        const vec3 refracted = refract(incoming, normal, 1.0 / pc.params.z);
        const float down = min(refracted.y, -1.0e-4);
        receiverWorld = sourceWorld + refracted * (pc.params.y / -down);

        const vec3 flatRefracted = refract(
            incoming, vec3(0.0, 1.0, 0.0), 1.0 / 1.3335);
        const float flatDown = min(flatRefracted.y, -1.0e-4);
        flatShiftXZ =
            flatRefracted.xz * (pc.params.y / -flatDown);
    }

    vSrc = sourceWorld.xz;

    const vec2 mapUv = (receiverWorld.xz - flatShiftXZ) / tilePeriod
        + instanceOffset;
    gl_Position = vec4(mapUv * 2.0 - 1.0, 0.0, 1.0);
}
