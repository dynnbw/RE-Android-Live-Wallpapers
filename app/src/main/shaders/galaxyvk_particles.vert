#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlphaMultiplier;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;

layout(location = 0) out vec4 vColor;

void main() {
    float dist = aPosition.y;
    float angle = aPosition.x;
    float x = dist * sin(angle);
    float y = dist * cos(angle) * 0.892;
    float p = dist * 5.5;
    float s = cos(p);
    float t = sin(p);

    vec4 pos;
    pos.x = t * x + s * y;
    pos.y = s * x - t * y;
    pos.z = aPosition.z;
    pos.w = 1.0;

    gl_Position = pc.uMvpMatrix * pos;
    gl_PointSize = aColor.a;
    vColor = vec4(aColor.rgb / 255.0, pc.uAlphaMultiplier);
}