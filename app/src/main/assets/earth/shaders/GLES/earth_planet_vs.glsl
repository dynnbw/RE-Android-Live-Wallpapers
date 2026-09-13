// 球体层（地球 / 云层 / 高光 / 月球）共用。
//
// 光照必须在**世界空间**算,不能在世界·相机空间算 —— 见 fs 里的说明。
// 所以这里分开传 uView 与 uModel:世界坐标既用于光照,也用于最终投影。
uniform mat4 uProjection;
uniform mat4 uView;
uniform mat4 uModel;

attribute vec3 aPosition;
attribute vec3 aNormal;
attribute vec2 aTexCoord;

varying vec3 vWorldPos;
varying vec3 vNormal;
varying highp vec2 vTexCoord;

void main() {
  vec4 world = uModel * vec4(aPosition, 1.0);
  vWorldPos = world.xyz;
  // 模型矩阵只有旋转与等比缩放,等比缩放不改变法线方向
  vNormal = mat3(uModel) * aNormal;
  vTexCoord = aTexCoord;
  gl_Position = uProjection * uView * world;
}
