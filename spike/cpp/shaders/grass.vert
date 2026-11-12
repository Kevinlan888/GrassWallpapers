#version 450

layout(push_constant) uniform ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
} pc;

layout(location = 0) out vec3 outColor;
layout(location = 1) out float outHeight;
layout(location = 2) out float outSide;
layout(location = 3) out float outSeed;

const int kSegments = 6;

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

float hash11(float value) {
    value = fract(value * 0.1031);
    value *= value + 33.33;
    value *= value + value;
    return fract(value);
}

void main() {
    float blade = float(gl_InstanceIndex);
    float seedA = hash11(blade + 1.0);
    float seedB = hash11(blade + 17.0);
    float seedC = hash11(blade + 53.0);
    float seedD = hash11(blade + 101.0);

    int quad = gl_VertexIndex / 6;
    int corner = gl_VertexIndex % 6;
    float t0 = float(quad) / float(kSegments);
    float t1 = float(quad + 1) / float(kSegments);
    float t = (corner == 2 || corner == 4 || corner == 5) ? t1 : t0;
    float side = (corner == 0 || corner == 3 || corner == 5) ? -1.0 : 1.0;

    float baseX = mix(-1.08, 1.08, seedA);
    float seasonHeight = seasonMix(0.92, 1.07, 0.96, 0.72, pc.seasonPhase);
    float bladeHeight = mix(0.23, 0.62, seedB)
            * pc.bladeHeightScale * seasonHeight;
    float baseHalfWidth = mix(0.010, 0.027, seedC) / max(pc.aspect, 0.2);

    float seasonWind = seasonMix(0.92, 0.82, 1.18, 0.74, pc.seasonPhase);
    float phase = pc.time * mix(0.62, 1.10, seedD) + baseX * 2.4;
    float gust = 0.72 + 0.28 * sin(pc.time * 0.31 + seedA * 5.0);
    float bend = (sin(phase) * 0.044 + sin(phase * 0.37 + 2.1) * 0.014)
            * pc.windStrength * seasonWind * gust;
    float lean = mix(-0.055, 0.055, seedD);

    float centerX = baseX + bend * (0.18 * t + 1.05 * t * t)
            + lean * t * t;
    float centerY = -1.02 + bladeHeight * 2.0 * t;
    float derivativeX = bend * (0.18 + 2.10 * t) + 2.0 * lean * t;
    vec2 tangent = normalize(vec2(derivativeX,
            max(bladeHeight * 2.0, 0.12)));
    vec2 normal = vec2(tangent.y, -tangent.x);
    float halfWidth = baseHalfWidth
            * max(0.15, 1.0 - 0.85 * pow(t, 1.6));
    vec2 position = vec2(centerX, centerY) + normal * halfWidth * side;

    vec3 deep = seasonMix(
            vec3(0.045, 0.200, 0.045),
            vec3(0.025, 0.140, 0.035),
            vec3(0.220, 0.100, 0.028),
            vec3(0.100, 0.150, 0.160),
            pc.seasonPhase);
    vec3 bright = seasonMix(
            vec3(0.460, 0.940, 0.230),
            vec3(0.180, 0.780, 0.160),
            vec3(0.920, 0.580, 0.120),
            vec3(0.580, 0.720, 0.680),
            pc.seasonPhase);

    outColor = mix(deep, bright, seedD);
    outHeight = t;
    outSide = side;
    outSeed = seedD;
    gl_Position = vec4(position.x, -position.y, 0.0, 1.0);
}
