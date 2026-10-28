#version 450

/* Holo Spiral 背景 pass —— GLES 的 holospiral_fragment_background.glsl。 */

layout(location = 0) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vColor;
}
