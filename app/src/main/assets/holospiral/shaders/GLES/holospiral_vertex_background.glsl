#version 300 es
in vec3 aPosition;
in vec4 aColor;

out lowp vec4 vColor;

void main() {
    vColor = aColor;
    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
}
