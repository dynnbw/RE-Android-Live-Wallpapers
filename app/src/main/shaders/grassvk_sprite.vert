#version 450

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in float aAlpha;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out float vAlpha;

layout(push_constant) uniform SpritePushConstants {
    mat4 uMVP;
} uPush;

void main() {
    gl_Position = uPush.uMVP * vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
    vAlpha = aAlpha;
}
