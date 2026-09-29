#version 300 es
uniform mat4 uMVPMatrix;
in vec2 aPosition;
in vec4 aColor;
in vec2 aTexCoord;
// 逐叶的萤火虫遮挡（近处的草挡住远处草的光）。算好了才传上来 —— 详见 GrassFireflyShadow，
// 这里是顶点格式的第 9 个分量；Vulkan 那条管线不读它。
in float aFireflyShadow;
out vec4 vColor;
out highp vec2 vTexCoord;
out float vFireflyShadow;
// 屏幕像素位置，原样传下去给萤火虫照明用。
//
// aPosition 已经是屏幕坐标（orthoM(0, w, h, 0)，y 向下），与萤火虫的坐标同一个空间，
// 所以这里既不用乘矩阵也不用翻 y。**必须是 highp**：屏幕高到 2400 个像素，
// mediump 在这个量级上只剩 2 像素的分辨率，光斑边缘会抖。
out highp vec2 vPos;
void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  vColor = aColor;
  vTexCoord = aTexCoord;
  vPos = aPosition;
  vFireflyShadow = aFireflyShadow;
}
