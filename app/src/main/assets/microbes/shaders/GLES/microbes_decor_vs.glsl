// 原版 Decoration_vert(逐字 + uSizeScale):装饰 = 紫色半透明漂浮点,顶点 shader 内做 sin/cos 漂移
precision mediump float;
uniform vec4 uTrans;
uniform float time;
uniform float uSizeScale;
attribute vec3 pos;
varying vec4 vColor;
void main() {
  vec2 offset = vec2(sin((time+pos.x)*.1), cos((time+pos.y)*.1))*mix(50., 200., pos.z);
  gl_Position.xy = ((pos.xy+offset) * uTrans.xy + uTrans.zw) * mix(.1, .9, pos.z);
  gl_Position.zw = vec2(0,1);
  gl_PointSize = 200.*mix(.5, 1., pos.z)*uSizeScale;
  vColor = vec4(.6,.6,1, mix(.03, .08, pos.z));
}
