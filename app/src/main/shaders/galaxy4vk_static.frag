#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture1;
layout(set = 0, binding = 1) uniform sampler2D uTexture2;
layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uParticleSize;
    float uParticleOpacity;
} pc;

layout(location = 0) in float pointSize;
layout(location = 0) out vec4 outColor;

void main() {
    if (pointSize > 4.0) {
        outColor = texture(uTexture1, gl_PointCoord);
    } else {
        outColor = texture(uTexture2, gl_PointCoord);
    }
}
