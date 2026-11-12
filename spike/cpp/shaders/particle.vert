#version 450

layout(push_constant) uniform ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
} pc;

layout(location = 0) out vec2 outLocal;
layout(location = 1) out float outFade;
layout(location = 2) flat out float outSeason;
layout(location = 3) flat out float outSeed;

float hash11(float value) {
    value = fract(value * 0.1031);
    value *= value + 33.33;
    value *= value + value;
    return fract(value);
}

void main() {
    float instance = float(gl_InstanceIndex);
    float seedA = hash11(instance + 5.0);
    float seedB = hash11(instance + 41.0);
    float seedC = hash11(instance + 89.0);
    float seedD = hash11(instance + 137.0);
    int season = int(round(pc.seasonPhase)) % 4;
    float seasonFloat = float(season);

    float verticalDirection = (season == 0 || season == 1) ? 1.0 : -1.0;
    float fallSpeed = mix(0.012, 0.030, seedA);
    float horizontalSpeed = mix(0.020, 0.050, seedD)
            * (0.65 + pc.windStrength * 0.45);

    float x = mod(seedB * 2.60 + pc.time * horizontalSpeed
            + sin(pc.time * 0.36 + seedC * 6.28) * 0.075, 2.60) - 1.30;
    float y = mod(seedC * 2.60 + pc.time * fallSpeed * verticalDirection
            + sin(pc.time * 0.51 + seedA * 6.28) * 0.055, 2.60) - 1.30;

    float size = season == 3
            ? mix(0.006, 0.014, seedD)
            : (season == 2
                    ? mix(0.021, 0.039, seedD)
                    : mix(0.014, 0.029, seedD));
    vec2 radius = vec2(size / max(pc.aspect, 0.2), size);

    vec2 corners[6] = vec2[](
            vec2(-1.0, -1.0),
            vec2(1.0, -1.0),
            vec2(1.0, 1.0),
            vec2(-1.0, -1.0),
            vec2(1.0, 1.0),
            vec2(-1.0, 1.0));
    vec2 local = corners[gl_VertexIndex % 6];

    float rotation = seedA * 6.28318
            + pc.time * mix(-0.65, 0.65, seedB) * (season == 3 ? 0.35 : 1.0);
    float c = cos(rotation);
    float s = sin(rotation);
    vec2 rotated = vec2(
            local.x * c - local.y * s,
            local.x * s + local.y * c);
    vec2 position = vec2(x, y) + rotated * radius;

    outLocal = local;
    outFade = smoothstep(-1.30, -1.05, y)
            * (1.0 - smoothstep(1.05, 1.30, y))
            * smoothstep(-1.30, -1.10, x)
            * (1.0 - smoothstep(1.10, 1.30, x));
    outSeason = seasonFloat;
    outSeed = seedD;
    gl_Position = vec4(position.x, -position.y, 0.0, 1.0);
}
