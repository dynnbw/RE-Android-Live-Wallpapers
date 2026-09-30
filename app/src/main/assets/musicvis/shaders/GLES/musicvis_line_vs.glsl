#version 300 es
// vis1（Visualizer）的波形点。顶点已经是屏幕像素坐标，只过一个正交矩阵。
//
// 顶点格式：x, y, 角 x, 角 y —— 角坐标是该顶点在四边形里的位置（±1），原样传给片元，
// 由它把方的四边形切成边缘柔化的圆（见 musicvis_line_fs）。
in vec2 aPosition;
in vec2 aCorner;
uniform mat4 uMVP;
out vec2 vCorner;
void main() {
  vCorner = aCorner;
  gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);
}
