#version 450

layout(push_constant) uniform PushConstants {
    vec4 uTrans;
    float time;
    float uSizeScale;
} pc;

layout(location = 0) in vec3 pos;

layout(location = 0) out vec4 vColor;

void main() {
    vec2 offset = vec2(sin((pc.time + pos.x) * 0.1), cos((pc.time + pos.y) * 0.1))
            * mix(50.0, 200.0, pos.z);
    vec4 clip = vec4((pos.xy + offset) * pc.uTrans.xy + pc.uTrans.zw, 0.0, 1.0);
    clip.xy *= mix(0.1, 0.9, pos.z);
    // GL 裁剪空间 -> Vulkan：y 取反，z 从 [-w,w] 映射到 [0,w]
    clip.y = -clip.y;
    clip.z = 0.5 * clip.z + 0.5 * clip.w;
    gl_Position = clip;
    gl_PointSize = 200.0 * mix(0.5, 1.0, pos.z) * pc.uSizeScale;
    vColor = vec4(0.6, 0.6, 1.0, mix(0.03, 0.08, pos.z));
}
