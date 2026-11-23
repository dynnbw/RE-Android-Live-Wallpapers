#version 450

// 银河贴图 —— 对应 GLES 的 nightsky_sphere_fs.glsl。

layout(set = 0, binding = 0) uniform sampler2D uMilkyTex;

layout(push_constant) uniform PushConstants {
    mat4 uProjViewRot;
    float uLatitudeRad;
    float uLSTRad;
    float uMilkyBrightness;
} pc;

layout(location = 0) in vec2 vUv;

layout(location = 0) out vec4 fragColor;

void main() {
    // 让银河图经度与天空模型对齐（夏/冬银心方向修正），与 GLES 完全一致。
    vec2 uv = vec2(fract(vUv.x + 0.5), clamp(vUv.y, 0.0, 1.0));
    vec3 color = texture(uMilkyTex, uv).rgb * pc.uMilkyBrightness;
    fragColor = vec4(color, 1.0);
}
