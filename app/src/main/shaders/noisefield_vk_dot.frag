#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(location = 0) in float vAlpha;

layout(location = 0) out vec4 fragColor;

void main() {
    // gl_PointCoord 落在点精灵自己的方块里，与 GLES 的 gl_PointCoord 相同
    vec4 texColor = texture(uTexture, gl_PointCoord);
    texColor.a = texColor.a * vAlpha;
    fragColor = texColor;
}
