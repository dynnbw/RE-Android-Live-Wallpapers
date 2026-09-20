#version 300 es
// 天空球：复用同一个网格，放大后从内侧看。
// 关键是**不复用 uModelView** —— 天空的视图矩阵去掉了相机平移（星空在无穷远，
// 切机位时星野不平移），但保留相机旋转（相机转向哪，看见的就是哪片星）。
uniform mat4 uProjection;
uniform mat4 uModelView;

in vec3 aPosition;
in vec2 aTexCoord;

out highp vec2 vTexCoord;

void main() {
  vTexCoord = aTexCoord;
  gl_Position = uProjection * uModelView * vec4(aPosition, 1.0);
}
