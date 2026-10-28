#version 450

/* Holo Spiral 点阵 pass —— GLES 的 holospiral_fragment_geometry.glsl。 */

layout(set = 0, binding = 0) uniform sampler2D uTexture0;

layout(location = 0) in vec4 vColor;
layout(location = 1) in float vFactor1;
layout(location = 2) in float vFactor2;

layout(location = 0) out vec4 fragColor;

void main() {
    // 点精灵坐标与 GLES 的 gl_PointCoord 一样以左上角为原点，贴图不用翻转
    vec4 texColor = texture(uTexture0, gl_PointCoord);
    fragColor.a = vColor.a * (texColor.r * vFactor1 + texColor.g * vFactor2);
    fragColor.rgb = vColor.rgb;
}
