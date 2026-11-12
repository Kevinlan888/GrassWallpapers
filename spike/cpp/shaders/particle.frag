#version 450

layout(push_constant) uniform ScenePushConstants {
    float time;
    float aspect;
    float seasonPhase;
    float hour;
    float windStrength;
    float bladeHeightScale;
} pc;

layout(location = 0) in vec2 inLocal;
layout(location = 1) in float inFade;
layout(location = 2) flat in float inSeason;
layout(location = 3) flat in float inSeed;

layout(location = 0) out vec4 outColor;

const float PI = 3.14159265359;

vec3 lightingTint() {
    float sunElevation = sin((pc.hour - 6.0) / 12.0 * PI);
    float day = smoothstep(-0.18, 0.30, sunElevation);
    float dawn = exp(-pow((pc.hour - 6.4) / 1.6, 2.0));
    float dusk = exp(-pow((pc.hour - 18.3) / 1.7, 2.0));
    vec3 tint = mix(vec3(0.26, 0.32, 0.52), vec3(1.0), day);
    return mix(tint, vec3(1.05, 0.67, 0.41), max(dawn, dusk) * 0.55);
}

float segmentMask(vec2 point, vec2 start, vec2 end, float halfWidth) {
    vec2 segment = end - start;
    vec2 offset = point - start;
    float along = clamp(dot(offset, segment)
            / max(dot(segment, segment), 0.0001), 0.0, 1.0);
    float distanceToSegment = length(offset - segment * along);
    return 1.0 - smoothstep(halfWidth, halfWidth * 2.4,
            distanceToSegment);
}

float mapleRadius(float angle) {
    float centerLobe = max(0.0, 1.0 - angle / 0.52);
    float upperLobe = max(0.0, 1.0 - abs(angle - 0.90) / 0.46);
    float lowerLobe = max(0.0, 1.0 - abs(angle - 1.76) / 0.48);
    return 0.27 + centerLobe * 0.70 + upperLobe * 0.59
            + lowerLobe * 0.38;
}

void main() {
    int season = int(inSeason);
    vec3 tint = lightingTint();
    vec3 color;
    float alpha;
    float radius = length(inLocal);

    if (season == 3) {
        float snowCore = 1.0 - smoothstep(0.18, 1.0, radius);
        float angle = atan(inLocal.y, inLocal.x);
        float arms = pow(max(0.0, abs(cos(angle * 3.0))), 18.0)
                * (1.0 - smoothstep(0.55, 1.02, radius));
        alpha = (snowCore * 0.72 + arms * 0.28) * inFade;
        color = vec3(0.90, 0.95, 1.0) * (0.58 + tint * 0.50);
    } else if (season == 2) {
        vec2 leaf = inLocal;
        float leafAngle = atan(abs(leaf.x), leaf.y);
        float edgeRadius = mapleRadius(leafAngle)
                + 0.012 * cos(leafAngle * 24.0 + inSeed * 19.0);
        float edgeDistance = length(leaf) - edgeRadius;
        float edgeSoftness = max(fwidth(edgeDistance), 0.016);
        float leafBody = 1.0 - smoothstep(-edgeSoftness, edgeSoftness,
                edgeDistance);

        float stem = (1.0 - smoothstep(0.026, 0.042, abs(leaf.x)))
                * smoothstep(-0.74, -0.68, leaf.y)
                * (1.0 - smoothstep(-0.30, -0.18, leaf.y));
        float leafMask = max(leafBody, stem);

        float vein = 0.0;
        vein = max(vein, segmentMask(leaf, vec2(0.0, -0.18),
                vec2(0.0, 0.92), 0.014));
        vein = max(vein, segmentMask(leaf, vec2(0.0, -0.08),
                vec2(0.60, 0.53), 0.011));
        vein = max(vein, segmentMask(leaf, vec2(0.0, -0.08),
                vec2(-0.60, 0.53), 0.011));
        vein = max(vein, segmentMask(leaf, vec2(0.0, -0.12),
                vec2(0.62, -0.12), 0.009));
        vein = max(vein, segmentMask(leaf, vec2(0.0, -0.12),
                vec2(-0.62, -0.12), 0.009));

        float warmth = clamp(0.12 + inSeed * 0.82 + leaf.y * 0.18,
                0.0, 1.0);
        vec3 leafColor = mix(vec3(0.58, 0.10, 0.018),
                vec3(0.94, 0.31, 0.025),
                smoothstep(0.06, 0.76, warmth));
        leafColor = mix(leafColor, vec3(1.00, 0.66, 0.11),
                smoothstep(0.76, 0.98, warmth) * 0.50);
        leafColor = mix(leafColor, vec3(1.00, 0.74, 0.20), vein * 0.34);
        leafColor *= 0.78 + 0.22 * (1.0 - clamp(length(leaf), 0.0, 1.0));
        alpha = leafMask * (0.92 + vein * 0.08) * inFade;
        color = leafColor * (0.56 + tint * 0.58);
    } else if (season == 1) {
        float angle = atan(inLocal.x, inLocal.y + 0.08);
        vec2 pappusCenter = vec2(0.0, -0.02);
        float pappusRadius = length(inLocal - pappusCenter);
        float fan = 1.0 - smoothstep(1.15, 1.48, abs(angle));
        float pappusBand = smoothstep(0.08, 0.24, pappusRadius)
                * (1.0 - smoothstep(0.80, 1.02, pappusRadius));

        float downA = pow(max(0.0,
                0.5 + 0.5 * cos(angle * 17.0 + inSeed * 21.0)), 7.0);
        float downB = pow(max(0.0,
                0.5 + 0.5 * cos(angle * 29.0 - inSeed * 31.0 + 0.9)),
                11.0);
        float downC = pow(max(0.0,
                0.5 + 0.5 * cos(angle * 43.0 + inSeed * 47.0 + 2.2)),
                15.0);
        float downyFill = fan * pappusBand
                * (0.07 + 0.09 * (0.5 + 0.5 * sin(angle * 11.0
                        + inSeed * 37.0)));
        float pappus = fan * pappusBand
                * (downA * 0.66 + downB * 0.38 + downC * 0.22);
        float core = 1.0 - smoothstep(0.09, 0.20,
                length(vec2(inLocal.x * 1.7, inLocal.y + 0.18)));
        float tail = (1.0 - smoothstep(0.022, 0.070, abs(inLocal.x)))
                * smoothstep(-0.94, -0.82, inLocal.y)
                * (1.0 - smoothstep(-0.12, -0.04, inLocal.y));

        alpha = clamp(pappus * 0.92 + downyFill + core * 0.66
                + tail * 0.48, 0.0, 1.0) * inFade;
        color = mix(vec3(1.0, 0.93, 0.76), vec3(1.0),
                clamp(pappus * 1.2 + downyFill * 2.0, 0.0, 1.0))
                * (0.68 + tint * 0.42);
    } else {
        float angle = atan(inLocal.y, inLocal.x);
        float petals = pow(max(0.0, abs(cos(angle * 2.0 + inSeed * 9.0))), 7.0);
        float petalMask = (1.0 - smoothstep(0.26, 1.0, radius))
                * (0.55 + petals * 0.45);
        alpha = petalMask * inFade;
        color = mix(vec3(1.0, 0.72, 0.78), vec3(1.0, 0.93, 0.86),
                inSeed) * (0.62 + tint * 0.48);
    }

    outColor = vec4(color, alpha);
}
