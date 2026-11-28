#version 450

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec4 aColor;
layout(location = 2) in vec2 aTexCoord;
// 逐叶的萤火虫遮挡（近处的草挡住远处草的光），与 GLES 同一份顶点数据
layout(location = 3) in float aFireflyShadow;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vTexCoord;
layout(location = 2) out vec2 vPos;
layout(location = 3) out float vFireflyShadow;

layout(push_constant) uniform GrassPushConstants {
    mat4 uMVP;
} uPush;

void main() {
    gl_Position = uPush.uMVP * vec4(aPosition, 0.0, 1.0);
    gl_Position.y = -gl_Position.y;
    vColor    = aColor;
    vTexCoord = aTexCoord;
    // 屏幕像素位置（与顶点同一空间、y 向下），萤火虫照明按它算距离
    vPos = aPosition;
    vFireflyShadow = aFireflyShadow;
}
