// 天空球：复用同一个网格，放大后从内侧看。
// 关键是**不复用 uModelView** —— 矩阵里已经去掉了相机平移与旋转，
// 否则会变成"星空跟着拖拽转、地球看着不动"。
uniform mat4 uProjection;
uniform mat4 uModelView;

attribute vec3 aPosition;
attribute vec2 aTexCoord;

varying highp vec2 vTexCoord;

void main() {
  vTexCoord = aTexCoord;
  gl_Position = uProjection * uModelView * vec4(aPosition, 1.0);
}
