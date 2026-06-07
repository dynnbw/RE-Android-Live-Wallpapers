#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 texColor = texture(uTexture, gl_PointCoord);
    outColor.rgb = texColor.rgb;
    outColor.a = 0.1;
}
