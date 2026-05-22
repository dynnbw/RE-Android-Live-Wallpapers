attribute vec2 aPosition;
attribute vec2 aTexCoord;
attribute vec3 aAdjust;
uniform mat4 uMVP;
varying vec2 vTex;
varying vec3 vAdjust;
void main() {
    vTex = aTexCoord;
    vAdjust = aAdjust;
    gl_Position = uMVP * vec4(aPosition.xy, 0.0, 1.0);
}
