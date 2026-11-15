#version 300 es
// 水面：顶点上直接算波浪高度，用它的一阶/二阶差分去扰动海床贴图、焦散和反射的坐标。
// 波浪函数、差分取样点、各项系数都照参考实现的 interactivewatershader/image.vertex。
precision highp float;

uniform mat4 uMVP;
/** 屏幕像素 / 设计单位（设计稿按 480 宽）。 */
uniform float uScale;
/** 已按 40π 回绕的水面时间。 */
uniform float uTime;
/** 桌面滚动量，设计单位。 */
uniform float uPanX;
/** 设计单位下的画面尺寸。 */
uniform vec2 uDesignSize;
/** 海床贴图铺满一次要多少设计单位。 */
uniform float uImageWidth;

in vec2 aPos;
in vec2 aUV;

out vec2 vSeabedUV;
out vec2 vCausticsUV;
out vec2 vReflectionUV;
out vec2 vRippleUV;
out float vCausticsIntensity;

float waves(vec2 pos) {
    vec2 dir1 = normalize(vec2(-0.007, 0.018)) * 0.04;
    vec2 dir2 = normalize(vec2(0.005, 0.011)) * 0.041;
    float t1 = 5.0 * uTime;
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
    vec2 p = aPos / uScale;
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);

    float z = waves(p);
    float z_a = waves(p + vec2(0.5, -0.866));
    float z_b = waves(p + vec2(0.5, 0.866));
    float zxn = waves(p + vec2(-1.0, 0.0));
    float zxp = -z + z_a + z_b;
    float zyn = -0.732 * z + 0.577 * zxn + 1.155 * z_a;
    float zyp = -0.732 * z + 0.577 * zxn + 1.155 * z_b;

    vec2 dzd = vec2(zxp - z, zyp - z);
    vec2 dszd2 = vec2(zxp + zxn - z * 2.0, zyp + zyn - z * 2.0);

    // 海床按设计单位 1:1 铺，横向跟着桌面滚动；uImageWidth 由缩放设置决定
    vSeabedUV = vec2(p.x + uPanX, p.y) / uImageWidth - dzd * 0.1;
    vCausticsUV = vec2(0.5) + dzd;
    vReflectionUV = p * (0.5 / uDesignSize) + vec2(0.4, 0.4) + dzd;
    vRippleUV = vec2(aUV.x * 0.33 + 0.333, aUV.y * 0.5 + 0.5)
            + vec2(uTime * 0.013, uTime * 0.007);
    vCausticsIntensity = clamp(0.5 + dszd2.x * 400.0, 0.0, 1.0);
}
