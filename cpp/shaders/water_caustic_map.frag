#version 460
layout(location = 0) in vec2 vSrc;
layout(location = 0) out vec4 outColor;

layout(push_constant) uniform WaterCausticMapPush
{
    vec4 sunDirection;
    vec4 params;
} pc;

void main()
{
    const vec2 sourceDx = dFdx(vSrc);
    const vec2 sourceDy = dFdy(vSrc);
    const float area = abs(sourceDx.x * sourceDy.y - sourceDx.y * sourceDy.x);
    const float intensity = min(area * pc.params.w, 40.0);
    outColor = vec4(intensity);
}
