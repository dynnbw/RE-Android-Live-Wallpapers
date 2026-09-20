#version 300 es
precision mediump float;

out vec4 fragColor;
in vec4 vColor;

void main() {
    fragColor = vec4(vColor.rgb * vColor.a, vColor.a);
}
