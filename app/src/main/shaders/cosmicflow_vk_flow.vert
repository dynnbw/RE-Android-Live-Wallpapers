#version 450

/*
 * Cosmic Flow flow pass — Vulkan port of shaders/GLES/flow_vs.glsl.
 *
 * The GLES shader does all its math in GL clip space and the fragment shader
 * derives its alpha from the clip-space deltas (vDx / vDy). Those deltas must
 * therefore keep using the un-corrected MVP, so the clip correction is applied
 * only to gl_Position, after vDx / vDy have been computed.
 */

const float POWER_LO = 0.03;
const float POWER_HI = 0.05;
const float TURB = 3.5;
const float SPEED = 0.2;

const float ZERO = 0.0;
const float PI_TWICE = 3.1415926535 * 2.0;
const vec4 NOISE_SCALE = vec4(0.7, 1.3, 0.7, 1.0);

// GL clip space (y up, z in [-w, w]) -> Vulkan clip space (y down, z in [0, w])
const mat4 CLIP_CORRECTION = mat4(
        1.0, 0.0, 0.0, 0.0,
        0.0, -1.0, 0.0, 0.0,
        0.0, 0.0, 0.5, 0.0,
        0.0, 0.0, 0.5, 1.0);

layout(location = 0) in vec3 aPosition;

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    // x = time, y = noise scale, z/w unused
    vec4 uTimeNoiseScaleColor;
    vec4 uNoisePos01;
    vec4 uPrimaryColor;
    vec4 uSecondaryColor;
} pc;

layout(location = 0) out vec4 vPosition;
layout(location = 1) out vec4 vDx;
layout(location = 2) out vec4 vDy;

float wave(float x, vec3 params) {
    float waveParam = x * params.x + params.y + params.z;
    return sin(mod(waveParam, PI_TWICE));
}

float addNoiseForAxis(float wave1, float wave2, float wave3, float wave4) {
    float ret = ZERO;

    ret += POWER_HI * wave1 * wave2;
    ret += POWER_HI * wave3 * wave4;
    ret += POWER_LO * wave3 * wave2;

    return ret;
}

vec4 addNoise0(vec4 pos) {
    // Moving texture noise coordinate (the noise lookup version of Cosmic Flow).
    vec2 texPos0 = pos.xy + pc.uNoisePos01.xy * 3.0;

    float x = texPos0.x;
    float y = texPos0.y;
    float timeSpeedHi = pc.uTimeNoiseScaleColor.x * SPEED;
    float ret = ZERO;

    float wave1 = wave(x, vec3((TURB * 5.34), 9.13, timeSpeedHi));
    float wave2 = wave(y, vec3((TURB * 7.54), 3.33, -timeSpeedHi));
    float wave3 = wave(x, vec3(TURB * 3.54, 6.13, -timeSpeedHi));
    float wave4 = wave(y, vec3(TURB * 5.24, 4.33, timeSpeedHi));

    float wave5 = wave(x, vec3(TURB * 7.12, 5.14, timeSpeedHi));
    float wave6 = wave(y, vec3(TURB * 1.375, 7.43, -timeSpeedHi));
    float wave7 = wave(x, vec3(TURB * 3.54, 3.43, -timeSpeedHi));

    float wave8 = wave(x, vec3(TURB * 2.23, 4.44, timeSpeedHi));
    float wave9 = wave(y, vec3(TURB * 4.199, 1.84, -timeSpeedHi));
    float wave10 = wave(x, vec3(TURB * 3.54, 5.13, -timeSpeedHi));

    float nx = addNoiseForAxis(wave1, wave2, wave3, wave4);
    // ny is intentionally equal to nx: the original did this because the
    // Mali 400 could not compile the second full call (see flow_vs.glsl).
    float nz = addNoiseForAxis(wave8, wave9, wave10, wave1);
    float ny = nx;

    return vec4(pos.x + nx, pos.y + ny, ZERO + nz, 1.0);
}

/*
 * Distance approximation across the crinkled grid between two neighbouring
 * positions. Kept in GL clip space, exactly as in the GLES shader, because the
 * fragment shader derives the flow transparency from the cross product.
 */
vec4 delta(vec4 pos, float dx, float dy, vec4 newPos) {
    return pc.uMvpMatrix * addNoise0(pos + vec4(dx, dy, ZERO, ZERO)) * NOISE_SCALE
            - newPos;
}

void main() {
    vec4 glPosition = pc.uMvpMatrix * addNoise0(vec4(aPosition, 1.0)) * NOISE_SCALE;

    vDx = delta(vec4(aPosition, 1.0), 0.045, ZERO, glPosition);
    vDy = delta(vec4(aPosition, 1.0), ZERO, 0.045, glPosition);

    vPosition = vec4(aPosition, 1.0);
    gl_Position = CLIP_CORRECTION * glPosition;
}
