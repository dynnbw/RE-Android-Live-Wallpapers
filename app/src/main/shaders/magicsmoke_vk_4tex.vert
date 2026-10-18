#version 450

/*
 * Magic Smoke, 4-layer variant (mask 0x0f).
 *
 * Mirrors the GLES vertex shader exactly (note the 0.35 depth base, which
 * differs from the 5-layer variant). Push constant layout (96 bytes):
 *   0   uLayer0..3   (vec4: sin, cos, scale*0.35, xshift)
 *   64  uPanOffset   (vec2)
 *   72  uAspectScale (vec2)
 *   80  uClearColor  (vec4; used by the fragment shader)
 */

layout(push_constant) uniform PushConstants {
    vec4 uLayer0;
    vec4 uLayer1;
    vec4 uLayer2;
    vec4 uLayer3;
    vec2 uPanOffset;
    vec2 uAspectScale;
    vec4 uClearColor;
} pc;

layout(location = 0) out vec2 vTexCoord0;
layout(location = 1) out vec2 vTexCoord1;
layout(location = 2) out vec2 vTexCoord2;
layout(location = 3) out vec2 vTexCoord3;

vec2 computeTexCoord(vec4 layer, vec2 position, float depth, vec2 panOffset) {
    float invZ = 0.35 + depth * 0.05;
    vec2 p = vec2((position.x + panOffset.x) * pc.uAspectScale.x,
                  (position.y + panOffset.y) * pc.uAspectScale.y);
    float x = 0.5 + 0.5 * invZ * (layer.z * (layer.y * p.x + layer.x * p.y)) + layer.w;
    float y = 0.5 + 0.5 * invZ * (layer.z * (-layer.x * p.x + layer.y * p.y));
    return vec2(x, y);
}

void main() {
    // Same quad order as the GLES TRIANGLE_STRIP.
    vec2 quad[4] = vec2[](
            vec2(-1.0, -1.0),
            vec2( 1.0, -1.0),
            vec2(-1.0,  1.0),
            vec2( 1.0,  1.0));
    vec2 aPosition = quad[gl_VertexIndex];

    vTexCoord0 = computeTexCoord(pc.uLayer0, aPosition, 1.0, pc.uPanOffset);
    vTexCoord1 = computeTexCoord(pc.uLayer1, aPosition, 2.0, pc.uPanOffset);
    vTexCoord2 = computeTexCoord(pc.uLayer2, aPosition, 3.0, pc.uPanOffset);
    vTexCoord3 = computeTexCoord(pc.uLayer3, aPosition, 4.0, pc.uPanOffset);

    // GL NDC is y-up with z in [-1, 1]; Vulkan clip space is y-down with z in [0, 1].
    // Same correction as the reference CLIP_CORRECTION matrix.
    gl_Position = vec4(aPosition.x, -aPosition.y, 0.5, 1.0);
}
