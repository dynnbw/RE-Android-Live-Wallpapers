// 大气光晕：屏幕空间四边形，坐标已是像素，走正交投影。
// 原版用 GLES1 扩展 glDrawTexfOES（窗口坐标直接贴图），GLES2 里没有，这是等价替代。
uniform mat4 uOrtho;

attribute vec2 aPosition;
attribute vec2 aTexCoord;

varying vec2 vTexCoord;

void main() {
  vTexCoord = aTexCoord;
  gl_Position = uOrtho * vec4(aPosition, 0.0, 1.0);
}
