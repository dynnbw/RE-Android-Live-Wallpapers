#version 450

layout(push_constant) uniform PushConstants {
    vec4 uTrans;
    float time;
} pc;

layout(location = 0) in vec4 aPosition;

layout(location = 0) out vec2 vTransform;
layout(location = 1) out float vWidthScale;

const float energy = 3.0;

void main() {
    float angle = aPosition.z;
    float size = aPosition.w;
    vec4 pos = vec4(aPosition.xy * pc.uTrans.xy + pc.uTrans.zw, 0.0, 1.0);
    // GL 裁剪空间 -> Vulkan：y 取反，z 从 [-w,w] 映射到 [0,w]
    pos.y = -pos.y;
    pos.z = 0.5 * pos.z + 0.5 * pos.w;
    gl_Position = pos;
    gl_PointSize = 30.0 * size;
    vTransform = vec2(cos(angle), sin(angle));
    vWidthScale = 7.0 / energy;
}
