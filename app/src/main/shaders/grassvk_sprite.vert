#version 450

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in float aAlpha;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out float vAlpha;
layout(location = 2) out vec4 vTint;

layout(push_constant) uniform SpritePushConstants {
    mat4 uMVP;
    vec4 uTint;   // rgb = 染色，a = 整批透明度（天气图层按昼夜染色）
} uPush;

void main() {
    gl_Position = uPush.uMVP * vec4(aPosition, 0.0, 1.0);
    gl_Position.y = -gl_Position.y;
    vTexCoord = aTexCoord;
    vAlpha = aAlpha;
    vTint = uPush.uTint;
}
