#version 450

/*
 * Ocean weather sprite fragment shader.
 *
 * Matches the GLES fragment stage exactly: GL_MODULATE-style multiply of the
 * texture by the per-sprite colour. Textures are uploaded premultiplied, and
 * the pass blends with ONE / ONE_MINUS_SRC_ALPHA, as GLES does.
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 tex = texture(uTexture, vTexCoord);
    fragColor = vec4(tex.rgb * vColor.rgb, tex.a * vColor.a);
}
