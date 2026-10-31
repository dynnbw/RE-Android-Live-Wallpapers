#version 450

layout(push_constant) uniform PushConstants {
    mat4 uViewProj;
    float uMoveScale;
    float uEmitterPull;
    float uAlphaScale;
} pc;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aMove;
layout(location = 2) in float aLife;
layout(location = 3) in float aAge;
layout(location = 4) in float aSize;
layout(location = 5) in float aAngle;
layout(location = 6) in vec3 aTrans;
layout(location = 7) in float aScale;
layout(location = 8) in float aAddAlpha;
layout(location = 9) in float aTime;

layout(location = 0) out float vAlpha;
layout(location = 1) out float vAddAlpha;

void main() {
    // 与 GLES 版相同：生命耗尽后按周期重算当前一轮的时间
    float td = aLife / aAge;
    td /= 10.0;
    float df = aTime / td;
    int div = int(df);
    df = float(div);
    td *= df;
    float time = aTime - td;
    float tempAlpha = time * 20.0 * aAge;
    float alpha;
    if (tempAlpha >= aLife) {
        alpha = aLife * 2.0 - tempAlpha;
    } else {
        alpha = tempAlpha;
    }

    // 每只水母的模型矩阵是平移 + 绕 z 旋转；每帧的缩放作用在 move 与点尺寸上
    vec3 local = aPosition + time * vec3(aMove.x * aScale, aMove.y * aScale, aMove.z) * pc.uMoveScale;
    float s = sin(aAngle);
    float c = cos(aAngle);
    vec3 world = vec3(c * local.x - s * local.y, s * local.x + c * local.y, local.z) + aTrans;
    vec4 clip = pc.uViewProj * vec4(world, 1.0);
    gl_PointSize = (aSize - clip.z) * aScale;
    if (gl_PointSize < 0.0) {
        gl_PointSize = 0.0;
    }
    gl_Position = vec4(clip.x, -clip.y, 0.5 * clip.z + 0.5 * clip.w, clip.w);

    // 第二种水滴会把粒子拉向水母在裁剪空间里的位置；第一种不拉
    if (pc.uEmitterPull > 0.0) {
        vec4 ePosition = pc.uViewProj * vec4(aTrans, 1.0);
        float speed = time * 0.5 * 0.4;
        gl_Position.x += (ePosition.x - gl_Position.x) * speed;
        gl_Position.y += (ePosition.y - gl_Position.y) * speed;
    }
    vAlpha = alpha;
    vAddAlpha = aAddAlpha;
}
