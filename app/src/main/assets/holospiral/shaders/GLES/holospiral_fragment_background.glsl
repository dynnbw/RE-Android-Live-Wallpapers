#version 300 es
precision mediump float;

out vec4 fragColor;
in lowp vec4 vColor;

void main() {
    fragColor = vColor;
}
