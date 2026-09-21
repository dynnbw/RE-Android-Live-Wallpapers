#version 300 es
precision highp float;

uniform mat4 uMVPMatrix;

in vec2 aPosition;
in vec2 aTexCoord;

out highp vec2 vUv;

void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  // 全屏四边形。aTexCoord 的 v 在屏幕底为 1（与 grass 的 y 向下投影一致），
  // 所以 vUv.y 往下增 —— 与参考实现同向，不需要翻转。
  vUv = aTexCoord;
}
