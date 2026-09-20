#version 300 es
in vec2 aPosition;
in vec2 aUV;
in vec3 aColor;

out vec2 vUV;
out vec3 vColor;

void main() {
    vUV = aUV;
    vColor = aColor;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
