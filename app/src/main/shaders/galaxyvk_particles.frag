#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D uFlares;

void main() {
    // 与 GLES 的 galaxy_particle_fs 完全一致：粒子颜色乘同一张 flare 贴图
    outColor = vColor * texture(uFlares, gl_PointCoord);
}
