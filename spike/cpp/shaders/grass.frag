#version 450

layout(push_constant) uniform ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
} pc;

layout(location = 0) in vec3 inColor;
layout(location = 1) in float inHeight;
layout(location = 2) in float inSide;
layout(location = 3) in float inSeed;

layout(location = 0) out vec4 outColor;

const float PI = 3.14159265359;

vec4 seasonWeights(float phase) {
    float wrapped = mod(phase, 4.0);
    int index = int(floor(wrapped));
    float blend = smoothstep(0.0, 1.0, fract(wrapped));
    if (index == 0) {
        return vec4(1.0 - blend, blend, 0.0, 0.0);
    } else if (index == 1) {
        return vec4(0.0, 1.0 - blend, blend, 0.0);
    } else if (index == 2) {
        return vec4(0.0, 0.0, 1.0 - blend, blend);
    }
    return vec4(blend, 0.0, 0.0, 1.0 - blend);
}

vec3 lightingTint() {
    float sunElevation = sin((pc.hour - 6.0) / 12.0 * PI);
    float day = smoothstep(-0.18, 0.30, sunElevation);
    float dawn = exp(-pow((pc.hour - 6.4) / 1.6, 2.0));
    float dusk = exp(-pow((pc.hour - 18.3) / 1.7, 2.0));
    vec3 tint = mix(vec3(0.22, 0.28, 0.48), vec3(1.0), day);
    return mix(tint, vec3(1.05, 0.63, 0.37), max(dawn, dusk) * 0.58);
}

void main() {
    vec3 blade = mix(inColor * 0.20, inColor,
            smoothstep(0.0, 0.92, inHeight));
    blade += inColor * 0.10 * inHeight * (1.0 - abs(inSide));

    float winter = seasonWeights(pc.seasonPhase).w;
    float frostPattern = smoothstep(0.30, 1.0,
            sin(inSeed * 31.7 + inHeight * 11.0) * 0.5 + 0.5);
    float frost = winter * smoothstep(0.55, 1.0, inHeight)
            * frostPattern * 0.24;
    blade = mix(blade, vec3(0.70, 0.82, 0.85), frost);
    blade *= lightingTint();

    float edgeWidth = clamp(fwidth(inSide) * 1.25, 0.018, 0.50);
    float alpha = 1.0 - smoothstep(1.0 - edgeWidth, 1.0, abs(inSide));
    outColor = vec4(blade, alpha);
}
