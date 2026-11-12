#version 450

layout(push_constant) uniform ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
} pc;

layout(location = 0) flat in float inPart;
layout(location = 1) in vec2 inLocal;
layout(location = 2) in float inSide;
layout(location = 3) flat in float inSeed;

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

vec3 seasonMix(vec3 spring, vec3 summer, vec3 autumn, vec3 winter,
        float phase) {
    vec4 weights = seasonWeights(phase);
    return spring * weights.x + summer * weights.y
            + autumn * weights.z + winter * weights.w;
}

float seasonMix(float spring, float summer, float autumn, float winter,
        float phase) {
    vec4 weights = seasonWeights(phase);
    return spring * weights.x + summer * weights.y
            + autumn * weights.z + winter * weights.w;
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
    float seasonalPresence = seasonMix(1.0, 1.08, 0.84, 0.0,
            pc.seasonPhase);
    if (seasonalPresence < 0.002) {
        discard;
    }

    vec3 tint = lightingTint();
    vec3 color;
    float alpha;

    if (inPart < 0.5) {
        float edgeWidth = clamp(fwidth(inSide) * 1.3, 0.03, 0.6);
        alpha = (1.0 - smoothstep(1.0 - edgeWidth, 1.0, abs(inSide)))
                * seasonalPresence;
        color = seasonMix(
                vec3(0.16, 0.34, 0.12),
                vec3(0.10, 0.30, 0.09),
                vec3(0.40, 0.25, 0.08),
                vec3(0.35, 0.40, 0.38),
                pc.seasonPhase) * tint;
    } else {
        float radius = length(inLocal);
        float angle = atan(inLocal.y, inLocal.x);

        float warpedAngle = angle + 0.035 * sin(angle * 11.0
                + inSeed * 17.0);
        float spokesA = pow(max(0.0,
                0.5 + 0.5 * cos(warpedAngle * 23.0 + inSeed * 23.0)),
                6.0);
        float spokesB = pow(max(0.0,
                0.5 + 0.5 * cos(warpedAngle * 37.0 - inSeed * 37.0
                        + 0.8)), 9.0);
        float spokesC = pow(max(0.0,
                0.5 + 0.5 * cos(warpedAngle * 53.0 + inSeed * 53.0
                        + 2.1)), 13.0);

        float reach = 0.90 + 0.055 * sin(angle * 17.0 + inSeed * 31.0)
                + 0.025 * sin(angle * 31.0 - inSeed * 9.0);
        float outerReach = clamp(reach + 0.04, 0.86, 1.03);
        float fiberBand = smoothstep(0.12, 0.34, radius)
                * (1.0 - smoothstep(0.76, outerReach, radius));
        float fibers = (spokesA * 0.15 + spokesB * 0.085
                + spokesC * 0.050) * fiberBand;
        float tuftBand = smoothstep(0.56, 0.73, radius)
                * (1.0 - smoothstep(0.91,
                        clamp(reach + 0.05, 0.93, 1.03), radius));
        float tufts = (spokesA * 0.26 + spokesB * 0.18
                + spokesC * 0.11) * tuftBand;
        float barbs = pow(max(0.0, 0.5 + 0.5 * cos(angle * 61.0
                + radius * 24.0 + inSeed * 43.0)), 8.0)
                * smoothstep(0.25, 0.45, radius)
                * (1.0 - smoothstep(0.72, 0.96, radius)) * 0.10;

        float cotton = 0.5 + 0.5 * sin(angle * 9.0
                + sin(angle * 13.0 + inSeed * 17.0) * 1.5
                + radius * 5.0);
        float softBody = smoothstep(0.02, 0.14, radius)
                * (1.0 - smoothstep(0.72, 0.98, radius))
                * (0.20 + cotton * 0.17);
        float edgeFuzz = (1.0 - smoothstep(0.82, 1.00, radius))
                * smoothstep(0.10, 0.50, radius) * 0.055
                * (0.5 + 0.5 * sin(angle * 47.0 + inSeed * 23.0));
        float core = 1.0 - smoothstep(0.025, 0.12, radius);
        float interior = 1.0 - smoothstep(0.10, 0.52, radius);

        alpha = clamp(core * 0.36 + interior * 0.09 + softBody
                + fibers + tufts + barbs + edgeFuzz, 0.0, 0.94)
                * seasonalPresence;
        vec3 cream = seasonMix(
                vec3(1.00, 0.92, 0.70),
                vec3(1.00, 0.97, 0.84),
                vec3(0.95, 0.82, 0.56),
                vec3(0.82, 0.88, 0.92),
                pc.seasonPhase);
        float downyLight = clamp(fibers * 1.3 + tufts * 0.70
                + softBody * 0.30 + barbs * 2.0, 0.0, 1.0);
        color = mix(cream, vec3(1.0), downyLight * 0.58)
                * (0.60 + tint * 0.50);
    }

    outColor = vec4(color, alpha);
}
