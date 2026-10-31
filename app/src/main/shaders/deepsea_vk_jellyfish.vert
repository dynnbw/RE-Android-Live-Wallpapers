#version 450

// 顶点里已经是裁剪坐标（模型/投影在 CPU 侧合成并做过裁剪空间修正）
layout(location = 0) in vec4 aClip;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in float aAddAlpha;
layout(location = 3) in vec3 aAddColor;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out float vAddAlpha;
layout(location = 2) out vec3 vAddColor;

void main() {
    gl_Position = aClip;
    vTexCoord = aTexCoord;
    vAddAlpha = aAddAlpha;
    vAddColor = aAddColor;
}
