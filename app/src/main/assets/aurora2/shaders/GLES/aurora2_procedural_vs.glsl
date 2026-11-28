#version 300 es
in vec2 aPosition;
in vec2 aTexCoord;

out vec2 vUv;

void main() {
    vUv = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
