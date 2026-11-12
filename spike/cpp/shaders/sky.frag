#version 450

layout(push_constant) uniform ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
} pc;

layout(location = 0) in vec2 inUV;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265359;

vec4 seasonWeights(float phase) {
    float wrapped = mod(phase, 4.0);
    int index = int(floor(wrapped));
    float blend = smoothstep(0.0, 1.0, fract(wrapped));
    vec4 weights = vec4(0.0);
    if (index == 0) {
        weights = vec4(1.0 - blend, blend, 0.0, 0.0);
    } else if (index == 1) {
        weights = vec4(0.0, 1.0 - blend, blend, 0.0);
    } else if (index == 2) {
        weights = vec4(0.0, 0.0, 1.0 - blend, blend);
    } else {
        weights = vec4(blend, 0.0, 0.0, 1.0 - blend);
    }
    return weights;
}

vec3 seasonMix(vec3 spring, vec3 summer, vec3 autumn, vec3 winter,
        float phase) {
    vec4 weights = seasonWeights(phase);
    return spring * weights.x + summer * weights.y
            + autumn * weights.z + winter * weights.w;
}

float hash21(vec2 value) {
    vec3 seed = fract(vec3(value.xyx) * vec3(0.1031, 0.1030, 0.0973));
    seed += dot(seed, seed.yzx + 33.33);
    return fract((seed.x + seed.y) * seed.z);
}

void main() {
    vec3 nightTop = vec3(0.008, 0.020, 0.070);
    vec3 nightHorizon = vec3(0.075, 0.115, 0.245);
    vec3 dawnTop = vec3(0.200, 0.330, 0.540);
    vec3 dawnHorizon = vec3(1.000, 0.560, 0.330);
    vec3 dayTop = vec3(0.200, 0.510, 0.800);
    vec3 dayHorizon = vec3(0.700, 0.890, 0.980);
    vec3 duskTop = vec3(0.155, 0.145, 0.345);
    vec3 duskHorizon = vec3(0.980, 0.390, 0.180);

    vec3 top = mix(nightTop, dawnTop, smoothstep(4.4, 6.2, pc.hour));
    top = mix(top, dayTop, smoothstep(7.2, 10.0, pc.hour));
    top = mix(top, duskTop, smoothstep(16.6, 18.4, pc.hour));
    top = mix(top, nightTop, smoothstep(19.8, 21.8, pc.hour));

    vec3 horizon = mix(nightHorizon, dawnHorizon,
            smoothstep(4.4, 6.2, pc.hour));
    horizon = mix(horizon, dayHorizon, smoothstep(7.2, 10.0, pc.hour));
    horizon = mix(horizon, duskHorizon, smoothstep(16.6, 18.4, pc.hour));
    horizon = mix(horizon, nightHorizon, smoothstep(19.8, 21.8, pc.hour));

    float gradient = pow(clamp(inUV.y, 0.0, 1.0), 1.35);
    vec3 color = mix(top, horizon, gradient);

    float sunElevation = sin((pc.hour - 6.0) / 12.0 * PI);
    float daylight = smoothstep(-0.12, 0.30, sunElevation);
    float night = 1.0 - smoothstep(-0.25, 0.08, sunElevation);

    vec2 starGrid = inUV * vec2(pc.aspect * 170.0, 170.0);
    vec2 starCell = floor(starGrid);
    vec2 starLocal = fract(starGrid) - 0.5;
    float starHash = hash21(starCell);
    float starShape = 1.0 - smoothstep(0.025, 0.105, length(starLocal));
    float starMask = starShape * step(0.986, starHash) * night
            * (1.0 - smoothstep(0.52, 0.79, inUV.y));
    float twinkle = 0.72 + 0.28 * sin(pc.time * 1.65 + starHash * 48.0);
    color += vec3(0.86, 0.92, 1.0) * starMask * twinkle * 0.85;

    vec3 farHill = seasonMix(
            vec3(0.085, 0.235, 0.115),
            vec3(0.050, 0.190, 0.075),
            vec3(0.265, 0.175, 0.065),
            vec3(0.270, 0.335, 0.365),
            pc.seasonPhase);
    farHill *= 0.30 + 0.55 * daylight;
    float farHillLine = 0.735 + 0.030 * sin(inUV.x * 6.2 + 0.7)
            + 0.011 * sin(inUV.x * 17.0 + 1.9);
    float farHillMask = smoothstep(
            farHillLine - 0.004, farHillLine + 0.004, inUV.y);
    color = mix(color, farHill, farHillMask * 0.90);

    vec3 nearHill = seasonMix(
            vec3(0.035, 0.135, 0.055),
            vec3(0.022, 0.105, 0.035),
            vec3(0.145, 0.090, 0.030),
            vec3(0.155, 0.205, 0.230),
            pc.seasonPhase);
    nearHill *= 0.25 + 0.50 * daylight;
    float nearHillLine = 0.835 + 0.024 * sin(inUV.x * 4.7 + 2.8)
            + 0.008 * sin(inUV.x * 13.0 + 0.4);
    float nearHillMask = smoothstep(
            nearHillLine - 0.004, nearHillLine + 0.004, inUV.y);
    color = mix(color, nearHill, nearHillMask);

    outColor = vec4(color, 1.0);
}
