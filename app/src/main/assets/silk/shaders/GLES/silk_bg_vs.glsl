#version 300 es
// Full-screen background quad (port of COriginal pass-through shader).
in vec3 aPosition;
in vec2 aTexCoor;
out vec2 vTextureCoord;
void main() {
    gl_Position = vec4(aPosition, 1.0);
    vTextureCoord = aTexCoor;
}
