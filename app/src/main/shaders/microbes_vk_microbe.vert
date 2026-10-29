#version 450

layout(push_constant) uniform PushConstants {
    vec4 uTrans;
    float time;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 miscInfo;
layout(location = 2) in vec3 aColor;

layout(location = 0) out vec3 vColor;
layout(location = 1) out vec2 vTransform;
layout(location = 2) out float vWidthScale;

void main() {
    float scale = miscInfo.x;
    float energy = miscInfo.y;
    float pulseProgress = pc.time - miscInfo.z;
    float pulse = clamp(min(pulseProgress * 2.0, -(pulseProgress - 1.0) * 0.5), 0.0, 1.0);
    vec4 pos = vec4(aPosition.xy * pc.uTrans.xy + pc.uTrans.zw, 0.0, 1.0);
    // GL 裁剪空间 -> Vulkan：y 取反，z 从 [-w,w] 映射到 [0,w]
    pos.y = -pos.y;
    pos.z = 0.5 * pos.z + 0.5 * pos.w;
    gl_Position = pos;
    gl_PointSize = 30.0 * scale;
    vTransform = vec2(cos(aPosition.z), sin(aPosition.z));
    vWidthScale = 1.0 / mix(0.5, 0.9, energy);
    vColor = aColor * 1.1 + pulse;
}
