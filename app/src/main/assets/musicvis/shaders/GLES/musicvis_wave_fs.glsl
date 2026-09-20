#version 300 es
precision mediump float;
out vec4 fragColor;
in vec2 vTex;
uniform sampler2D uTex;
void main() {
    fragColor = texture(uTex, vTex);
}
