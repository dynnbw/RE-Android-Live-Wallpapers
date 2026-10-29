#version 450

layout(push_constant) uniform PushConstants {
    vec4 uTrans;
    float time;
} pc;

layout(location = 0) in vec3 aPosition;

void main() {
    vec4 pos = vec4(aPosition.xy * pc.uTrans.xy + pc.uTrans.zw, 0.0, 1.0);
    // GL 裁剪空间 -> Vulkan：y 取反，z 从 [-w,w] 映射到 [0,w]
    pos.y = -pos.y;
    pos.z = 0.5 * pos.z + 0.5 * pos.w;
    gl_Position = pos;
    float scale = cos(pc.time * 2.0 + aPosition.z * 10.0) * 0.2 + 0.7;
    gl_PointSize = 20.0 * scale;
}
