#version 450

/* Cosmic Flow flow pass — Vulkan port of shaders/GLES/flow_fs.glsl. */

layout(set = 0, binding = 0) uniform sampler2D sTexture;

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    vec4 uTimeNoiseScaleColor;
    vec4 uNoisePos01;
    vec4 uPrimaryColor;
    vec4 uSecondaryColor;
} pc;

layout(location = 0) in vec4 vPosition;
layout(location = 1) in vec4 vDx;
layout(location = 2) in vec4 vDy;

layout(location = 0) out vec4 fragColor;

void main() {
    // The texture alpha weighs in a variance of the flow colour (secondary).
    float col1 = texture(sTexture, vPosition.xy).a;

    // More transparent where the transformed grid is flatter.
    float normalAlpha = 1.1 - length(cross(vDx.xyz, vDy.xyz));
    normalAlpha = clamp(normalAlpha, 0.0, 1.0);

    float alpha = normalAlpha * (vPosition.y + 0.2);
    alpha = pow(alpha, 2.3);

    fragColor = vec4(pc.uPrimaryColor.rgb + col1 * pc.uSecondaryColor.rgb, alpha);
}
