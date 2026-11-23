#version 450

/*
 * 球体层（地球 / 云层 / 高光 / 月球）共用，对应 GLES 的 earth_planet_vs.glsl。
 *
 * 通用 pass 的推送常量上限是 128 字节，塞不下 proj/view/model 三个矩阵。所有球体的
 * 模型矩阵都只是「绕 Y 旋转 × 等比缩放 × 平移」，所以这里只推送 proj·view 与四个
 * 变换参数，世界坐标在着色器里重建 —— 与 Matrix.translateM/rotateM/scaleM 的顺序一致。
 */

layout(push_constant) uniform PushConstants {
    mat4 uViewProj;
    float uAngleY;   // 弧度
    float uScale;
    float uTx;
    float uTz;
    float uLightX;
    float uLightY;
    float uLightZ;
    float uDeltaR;
    float uDeltaG;
    float uDeltaB;
    float uAmbient;
    float uNightGain;
    float uUseNight;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoord;

layout(location = 0) out vec3 vWorldPos;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec2 vTexCoord;

void main() {
    float c = cos(pc.uAngleY);
    float s = sin(pc.uAngleY);
    // Mat4.rotateM 的 Y 轴旋转（列主序：x' = c*x + s*z, z' = -s*x + c*z）
    vec3 rotated = vec3(c * aPosition.x + s * aPosition.z,
                        aPosition.y,
                        -s * aPosition.x + c * aPosition.z);
    vec3 world = rotated * pc.uScale + vec3(pc.uTx, 0.0, pc.uTz);

    vWorldPos = world;
    // 等比缩放不改变法线方向
    vNormal = vec3(c * aNormal.x + s * aNormal.z,
                   aNormal.y,
                   -s * aNormal.x + c * aNormal.z);
    vTexCoord = aTexCoord;
    gl_Position = pc.uViewProj * vec4(world, 1.0);
}
