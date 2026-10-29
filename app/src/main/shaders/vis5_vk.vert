#version 450
// vis5（Many）的面板 —— 对应 GLES 版 musicvis_many_quad_vs.glsl。
//
// 顶点是三维场景坐标（x, y, z）与四边形的角纹理坐标。uMvp 已含 Vulkan 的裁剪空间
// 修正（y 取反、z 从 [-w, w] 映射到 [0, w]），所以面板的位置与朝向和 GLES 版一致。
layout(push_constant) uniform PushConstants {
    mat4 uMvp;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTex;

void main() {
    vTex = aTexCoord;
    gl_Position = pc.uMvp * vec4(aPosition, 1.0);
}
