#version 300 es
// vis1（Visualizer）的波形带子。顶点已经是屏幕像素坐标，只过一个正交矩阵。
in vec2 aPosition;
uniform mat4 uMVP;
void main() {
  gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);
}
