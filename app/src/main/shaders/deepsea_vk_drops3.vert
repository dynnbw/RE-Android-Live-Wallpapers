#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    float uTime;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aMove;
layout(location = 2) in float aLife;
layout(location = 3) in float aAge;
layout(location = 4) in float aSize;

layout(location = 0) out float vAlpha;

void main() {
    // 与 GLES 版相同：只在第一轮内可见（div > 1 时 alpha 置 0）
    float temp = aLife - (pc.uTime * 10.0 * aAge);
    float time = pc.uTime;
    float alpha = 0.0;
    if (temp < 0.0) {
        float td = aLife / aAge;
        td /= 10.0;
        float df = pc.uTime / td;
        int div = int(df);
        df = float(div);
        td *= df;
        time = pc.uTime - td;
        alpha = aLife - (time * 10.0 * aAge);
        if (div > 1) {
            alpha = 0.0;
        }
    }
    gl_PointSize = aSize;
    if (gl_PointSize < 0.0) {
        gl_PointSize = 0.0;
    }
    vec3 pos = aPosition + time * aMove * 1.2;
    float angle = time * 6.0;
    float r = 0.5 * aLife;
    float moveX = pos.x + r * cos(angle);
    pos.x += moveX;
    vec4 clip = pc.uMvp * vec4(pos, 1.0);
    gl_Position = vec4(clip.x, -clip.y, 0.5 * clip.z + 0.5 * clip.w, clip.w);
    vAlpha = alpha;
}
