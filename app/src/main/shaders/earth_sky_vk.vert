#version 450

/*
 * 天空球，对应 GLES 的 earth_sky_vs.glsl。
 *
 * 与球体层共用同一份网格顶点缓冲（位置 / 法线 / UV），所以 location 1 的法线
 * 在这里是占位；天空只需要位置与 UV。
 */

layout(push_constant) uniform PushConstants {
    mat4 uViewProj;
    float uAngleY;   // 弧度
    float uScale;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoord;

layout(location = 0) out vec2 vTexCoord;

void main() {
    float c = cos(pc.uAngleY);
    float s = sin(pc.uAngleY);
    vec3 rotated = vec3(c * aPosition.x + s * aPosition.z,
                        aPosition.y,
                        -s * aPosition.x + c * aPosition.z);
    vTexCoord = aTexCoord;
    gl_Position = pc.uViewProj * vec4(rotated * pc.uScale, 1.0);
}
