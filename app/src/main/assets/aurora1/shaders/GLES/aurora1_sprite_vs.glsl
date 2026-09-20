#version 300 es
in vec2 aPosition;
in vec2 aTexCoord;

uniform mat4 uMatrix;

out vec2 vTexCoord;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = uMatrix * vec4(aPosition, 0.0, 1.0);
}