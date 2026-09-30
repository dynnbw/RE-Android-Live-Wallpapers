#version 300 es
// vis1：原版是一条纯白的线（paint 只有颜色、没有贴图），所以这里只需要一个颜色。
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
  fragColor = uColor;
}
