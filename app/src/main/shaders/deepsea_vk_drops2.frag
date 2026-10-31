#version 450

layout(set = 0, binding = 0) uniform sampler2D uTexture;
layout(set = 0, binding = 1) uniform sampler2D uAlphaTexture;

layout(location = 0) in float vAlpha;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 tex = texture(uTexture, gl_PointCoord);
    vec4 alphaTexture = texture(uAlphaTexture, gl_PointCoord);
    fragColor = tex;
    fragColor.w = alphaTexture.r * vAlpha * 0.5;
}
