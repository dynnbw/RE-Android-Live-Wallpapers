#version 300 es
// 全屏四边形。三条片元共用。
//
// **不做 v 翻转。** v=0 对应 y=-1（屏幕底），而 FBO 纹理里 v=0 也是底 ——
// 两边约定一致，翻了反而会上下颠倒。
uniform mat4 uMVPMatrix;
in vec2 aPosition;
in vec2 aTexCoord;
out vec2 vUv;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  vUv = aTexCoord;
}
