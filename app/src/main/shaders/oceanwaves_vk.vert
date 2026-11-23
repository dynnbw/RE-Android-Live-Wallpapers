#version 450

/*
 * 海浪：顶点上直接算波浪高度，用它的一阶/二阶差分去扰动海床贴图、焦散和反射的坐标。
 * 波浪函数、差分取样点、各项系数与 GLES 版逐字相同。
 *
 * Push constant 布局（96 字节）：
 *   0   uMvp            mat4（已含 GL → Vulkan 的裁剪空间修正）
 *   64  uParams         vec4: x=scale, y=time, z=panX, w=imageWidth
 *   80  uDesignSize     vec2
 *   88  uPad            vec2（占位，保持块大小与 Java 侧 24 个 float 对齐）
 */

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    vec4 uParams;
    vec2 uDesignSize;
    vec2 uPad;
} pc;

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;

layout(location = 0) out vec2 vSeabedUV;
layout(location = 1) out vec2 vCausticsUV;
layout(location = 2) out vec2 vReflectionUV;
layout(location = 3) out vec2 vRippleUV;
layout(location = 4) out float vCausticsIntensity;

float waves(vec2 pos) {
    vec2 dir1 = normalize(vec2(-0.007, 0.018)) * 0.04;
    vec2 dir2 = normalize(vec2(0.005, 0.011)) * 0.041;
    float t1 = 5.0 * pc.uParams.y;
    float t2 = t1 * 0.99;

    float z = 3.0;
    z += 0.7 * sin(dot(pos, dir1) - t1 - 1.1);
    z += 0.7 * sin(dot(pos, dir2) - t2 - 1.1);
    // 以 (240, -70) 为心的同心波：落点在心里那座"钟岛"上方
    float d = length(pos - vec2(240.0, -70.0));
    z += sin(d * 0.044 - t1);
    return z;
}

void main() {
    float scale = pc.uParams.x;
    float panX = pc.uParams.z;
    float imageWidth = pc.uParams.w;

    vec2 p = aPos / scale;
    gl_Position = pc.uMvp * vec4(aPos, 0.0, 1.0);

    float z = waves(p);
    float z_a = waves(p + vec2(0.5, -0.866));
    float z_b = waves(p + vec2(0.5, 0.866));
    float zxn = waves(p + vec2(-1.0, 0.0));
    float zxp = -z + z_a + z_b;
    float zyn = -0.732 * z + 0.577 * zxn + 1.155 * z_a;
    float zyp = -0.732 * z + 0.577 * zxn + 1.155 * z_b;

    vec2 dzd = vec2(zxp - z, zyp - z);
    vec2 dszd2 = vec2(zxp + zxn - z * 2.0, zyp + zyn - z * 2.0);

    // 海床按设计单位 1:1 铺，横向跟着桌面滚动；imageWidth 由缩放设置决定
    vSeabedUV = vec2(p.x + panX, p.y) / imageWidth - dzd * 0.1;
    vCausticsUV = vec2(0.5) + dzd;
    vReflectionUV = p * (0.5 / pc.uDesignSize) + vec2(0.4, 0.4) + dzd;
    // uv 与 GLES 一样是网格上的 0..1：上方顶点取 0
    vRippleUV = vec2(aUV.x * 0.33 + 0.333, aUV.y * 0.5 + 0.5)
            + vec2(pc.uParams.y * 0.013, pc.uParams.y * 0.007);
    vCausticsIntensity = clamp(0.5 + dszd2.x * 400.0, 0.0, 1.0);
}
