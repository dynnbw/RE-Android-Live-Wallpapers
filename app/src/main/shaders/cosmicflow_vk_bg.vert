#version 450

/*
 * Cosmic Flow background pass — Vulkan port of shaders/GLES/bg_vs.glsl.
 *
 * The GLES shader writes GL clip space directly (the square is already in NDC),
 * so the Vulkan clip correction is applied to the finished position:
 * y is flipped and z is remapped to [0, w].
 */

const mat4 CLIP_CORRECTION = mat4(
        1.0, 0.0, 0.0, 0.0,
        0.0, -1.0, 0.0, 0.0,
        0.0, 0.0, 0.5, 0.0,
        0.0, 0.0, 0.5, 1.0);

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(push_constant) uniform PushConstants {
    // x = time, y = height scale, z = color index (always 0), w = x offset
    vec4 uTimeHeightScaleColorXOffset;
    vec4 uPrimaryColor;
} pc;

layout(location = 0) out vec3 vColor;
layout(location = 1) out vec2 vTexCoord;

void main() {
    float uTime = pc.uTimeHeightScaleColorXOffset.x;
    float uHeightScale = pc.uTimeHeightScaleColorXOffset.y;
    float uXOffset = pc.uTimeHeightScaleColorXOffset.w;

    vec4 position = vec4(aPosition, 1.0);
    vec4 glPosition = vec4(1.0, uHeightScale, 1.0, 1.0) *
            (position + vec4(
                    0.0,
                    -0.2 * sin(position.y + uXOffset * 2.0 + uTime + position.x),
                    0.0,
                    0.0));

    gl_Position = CLIP_CORRECTION * glPosition;
    vColor = pc.uPrimaryColor.rgb;
    vTexCoord = aTexCoord;
}
