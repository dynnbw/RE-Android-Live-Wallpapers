#version 300 es
// 贴图都是预乘 alpha 的白色素材，按 GL_ONE / GL_ONE_MINUS_SRC_ALPHA 叠加。
precision mediump float;

in vec2 vUV;
in float vAlpha;

uniform sampler2D uTex;

out vec4 fragColor;

void main() {
    fragColor = texture(uTex, vUV) * vAlpha;
}
