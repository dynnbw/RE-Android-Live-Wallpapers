#version 300 es
in vec2 aPos;
in vec2 aUv;
out highp vec2 vUv;

void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
