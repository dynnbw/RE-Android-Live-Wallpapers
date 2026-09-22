#version 300 es
// 全屏四边形。三条片元共用。
//
// **不接 MVP 矩阵。** 顶点已经是 NDC（-1..1），乘矩阵纯属多余；
// 而多一个 uniform 就多一个"忘了设"的机会 —— 未设的 mat4 是全零，
// gl_Position 算出来是 0，症状是**整个辉光不出现且不报错**。
//
// **也不做 v 翻转。** v=0 对应 y=-1（屏幕底），而 FBO 纹理里 v=0 也是底 ——
// 两边约定一致，翻了反而会上下颠倒。
in vec2 aPosition;
in vec2 aTexCoord;
out vec2 vUv;
void main() {
  gl_Position = vec4(aPosition, 0.0, 1.0);
  vUv = aTexCoord;
}
