#version 300 es
in vec2 aPosition;
in vec2 aTexCoord;
in vec3 aAdjust;
uniform mat4 uMVP;
out vec2 vTex;
out vec3 vAdjust;
void main() {
    vTex = aTexCoord;
    vAdjust = aAdjust;
    gl_Position = uMVP * vec4(aPosition.xy, 0.0, 1.0);
}
