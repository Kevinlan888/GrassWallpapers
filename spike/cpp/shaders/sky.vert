#version 450

layout(push_constant) uniform ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
} pc;

layout(location = 0) out vec2 outUV;

void main() {
    vec2 position = vec2(
            float((gl_VertexIndex << 1) & 2),
            float(gl_VertexIndex & 2));
    // The full-screen triangle starts at the top-left in Vulkan's Y-down space.
    outUV = position;
    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
}
