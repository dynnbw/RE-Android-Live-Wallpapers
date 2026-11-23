#version 450

/*
 * 星点的顶点着色器 —— 对应 GLES 的 nightsky_star_vs.glsl。
 *
 * 9000 颗星的赤经/赤纬/亮度是静态顶点属性，坐标变换与点大小全在 GPU 上算。
 * 位置用 GL_POINTS + gl_PointSize 画成点精灵；Vulkan 需要 largePoints 特性，
 * 通用渲染器在设备支持时已经开启。裁剪空间的转换与其它 VK 着色器一致。
 */

layout(push_constant) uniform PushConstants {
    mat4 uProjViewRot;
    float uLatitudeRad;
    float uLSTRad;
    float uTimeSec;
    float uTrailMode;
} pc;

layout(location = 0) in vec4 aStar;   // raDeg, decDeg, brightness, normBrightness
layout(location = 1) in vec3 aColor;

layout(location = 0) out vec4 vColor;

const float PI = 3.14159265358979323846;

vec3 equatorialToHorizon(float ra, float dec, float latitude, float lst) {
    float h = lst - ra;
    float sinDec = sin(dec);
    float cosDec = cos(dec);
    float sinLat = sin(latitude);
    float cosLat = cos(latitude);
    float sinH = sin(h);
    float cosH = cos(h);

    float east = -cosDec * sinH;
    float north = sinDec * cosLat - cosDec * sinLat * cosH;
    float up = sinDec * sinLat + cosDec * cosLat * cosH;
    return vec3(east, north, up);
}

void main() {
    float ra = radians(aStar.x);
    float dec = radians(aStar.y);
    float brightness = max(aStar.z, 0.0);
    float normBrightness = clamp(aStar.w, 0.0, 1.0);

    vec3 horizon = equatorialToHorizon(ra, dec, pc.uLatitudeRad, pc.uLSTRad);
    vec4 clip = pc.uProjViewRot * vec4(horizon * 5.0, 1.0);
    gl_Position = vec4(clip.x, -clip.y, 0.5 * clip.z + 0.5 * clip.w, clip.w);

    float pointSize = 10.0 + 80.0 * pow(normBrightness, 0.45);

    float twinkleSeed = fract(abs(sin(ra * 12.9898 + dec * 78.233)) * 43758.5453);
    float twinkle =
            0.94 + 0.06 * sin(pc.uTimeSec * (1.3 + twinkleSeed * 1.9) + ra * 11.0 + dec * 5.0);

    float alpha = clamp(0.15 + 0.85 * pow(brightness, 0.33), 0.0, 1.0) * twinkle;

    if (pc.uTrailMode > 0.5) {
        pointSize *= 1.0;
        alpha *= 0.40;
    }

    gl_PointSize = pointSize;
    vColor = vec4(aColor, alpha);
}
