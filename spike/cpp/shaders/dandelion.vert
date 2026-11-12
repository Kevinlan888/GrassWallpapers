#version 450

layout(push_constant) uniform ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
} pc;

layout(location = 0) flat out float outPart;
layout(location = 1) out vec2 outLocal;
layout(location = 2) out float outSide;
layout(location = 3) flat out float outSeed;

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
    float instance = float(gl_InstanceIndex);
    float seedA = hash11(instance + 1.0);
    float seedB = hash11(instance + 23.0);
    float seedC = hash11(instance + 67.0);
    float seedD = hash11(instance + 113.0);

    float baseX = mix(-0.88, 0.88, seedA);
    float seasonSize = seasonMix(0.93, 1.08, 0.88, 0.64, pc.seasonPhase);
    float stemHeight = mix(0.58, 0.92, seedB) * seasonSize;
    float phase = pc.time * mix(0.52, 0.92, seedC) + baseX * 3.1;
    float bend = (sin(phase) * 0.052 + sin(phase * 0.43 + 1.4) * 0.018)
            * pc.windStrength * seasonMix(0.92, 0.82, 1.18, 0.72,
                    pc.seasonPhase);
    float lean = mix(-0.028, 0.028, seedD);

    int part = gl_VertexIndex / 6;
    int corner = gl_VertexIndex % 6;
    vec2 quadCorners[6] = vec2[](
            vec2(-1.0, -1.0),
            vec2(1.0, -1.0),
            vec2(1.0, 1.0),
            vec2(-1.0, -1.0),
            vec2(1.0, 1.0),
            vec2(-1.0, 1.0));

    vec2 position;
    float side = 0.0;
    vec2 local = vec2(0.0);

    if (part == 0) {
        float t = (corner == 2 || corner == 4 || corner == 5) ? 1.0 : 0.0;
        side = (corner == 0 || corner == 3 || corner == 5) ? -1.0 : 1.0;
        float stemX = baseX + (bend + lean) * t * t;
        float centerY = -1.02 + stemHeight * 2.0 * t;
        float derivativeX = 2.0 * (bend + lean) * t;
        vec2 tangent = normalize(vec2(derivativeX,
                max(stemHeight * 2.0, 0.12)));
        vec2 normal = vec2(tangent.y, -tangent.x);
        float halfWidth = mix(0.0034, 0.0012, t) / max(pc.aspect, 0.2);
        position = vec2(stemX, centerY) + normal * halfWidth * side;
    } else {
        local = quadCorners[corner];
        float topX = baseX + bend + lean;
        float topY = -1.02 + stemHeight * 2.0;
        float headRadius = mix(0.034, 0.054, seedC) * seasonSize;
        float wobble = 1.0 + 0.035 * sin(pc.time * 0.85 + instance * 2.1);
        vec2 radius = vec2(
                headRadius * wobble / max(pc.aspect, 0.2),
                headRadius * wobble);
        position = vec2(topX, topY) + local * radius;
    }

    outPart = float(part);
    outLocal = local;
    outSide = side;
    outSeed = seedD;
    gl_Position = vec4(position.x, -position.y, 0.0, 1.0);
}
