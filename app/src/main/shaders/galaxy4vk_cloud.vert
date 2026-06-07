#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uParticleSize;
    float uParticleOpacity;
} pc;

layout(location = 0) in vec3 aPosition;   // (angle, dist, z)
layout(location = 1) in float aPointSize;

void main() {
    float dist = aPosition.y / 4.0;
    float angle = aPosition.x;
    float z = aPosition.z;

    float x = dist * sin(angle) * 0.8;
    float y = dist * cos(angle) * 0.8;

    float p = dist * 7.5;
    float s = cos(p);
    float t = sin(p);

    vec4 pos;
    pos.x = t * x + s * y;
    pos.y = s * x - t * y;
    pos.z = z;
    pos.w = 1.0;

    pos.y = pos.y * 0.5;
    gl_Position = pc.uMvpMatrix * pos;

    float pSize = 170.0 - (pos.y * pos.y * 1.44 + pos.x * pos.x) * 400.0;
    if (pSize <= 30.0) pSize = 30.0;
    gl_PointSize = pSize;
}
