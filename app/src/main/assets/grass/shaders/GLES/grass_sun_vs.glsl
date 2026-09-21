#version 300 es
precision highp float;

uniform mat4 uMVPMatrix;
uniform float uTime;
uniform vec2 uResolution;
uniform vec2 uSunPos;
uniform float uSunPosOffsetY;
uniform float uLineAlpha;

in vec2 aPosition;
in vec2 aTexCoord;

out vec2 vUv;
out highp vec4 vLight;
out highp float vCircleNumber;
out highp float vRandSeed;
out highp vec2 vSunPos;
out highp mat4 vWorldSpaceMat;

// 线性光
#define LINE_SPEED 0.5
#define LINE_COLOR vec4(1.0, 0.8078, 0.3922, 1.0)
#define PI 3.1415926

float getRandom(float value, float seed) {
    return fract(sin(value * 999.9) * seed);
}

float perFromVal(float val, float from, float to) {
    return (val - from) / (to - from);
}

void main() {
    gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);

    // vUv 居中并预乘宽高比。grass 的 vUv.y 是**下正**（+0.5 = 屏幕底），
    // 见 GrassScene 的 orthoM(0, w, h, 0, ...)，参数序是 (l, r, bottom, top, n, f)。
    vUv = aTexCoord - 0.5;
    vUv.x *= uResolution.x / uResolution.y;

    // 太阳位置：与参考实现等价，只是"居中"这一步在 grass 这边做。
    vec2 sp = uSunPos / uResolution - 0.5;
    sp.x *= uResolution.x / uResolution.y;
    sp.x += uSunPosOffsetY * sp.x;
    sp.y += uSunPosOffsetY;
    vSunPos = sp;

    // 逐帧动画量。light.rgb 是三条相位错开的弱光线，
    // light.a 同时驱动光斑的尺寸和到太阳的距离。
    float wobble = sin(uTime * 0.3333 + 5.0) * 0.05;
    float t1 = (sin(uTime * LINE_SPEED * 4.0) + 1.0) * 0.5;
    float t2 = (sin((uTime * LINE_SPEED + PI * 0.3333) * 4.0) + 1.0) * 0.5;
    float t3 = (sin((uTime * LINE_SPEED + PI * 0.6667) * 4.0) + 1.0) * 0.5;
    vLight = vec4(t1, t2, t3, wobble);
    vLight.rgb *= LINE_COLOR.rgb * (uLineAlpha / 320.0);

    vCircleNumber = floor(mix(4.0, 8.0, getRandom(17.0, 7.8 / 100000.0 * 67.9)));
    vRandSeed = 7.8 / 100000.0 * 939.7;

    // 环晕用的透视矩阵。aspect 固定为 1（宽高比已经在 vUv 里预乘过了）。
    // translate.z = 3 是必须的：vec4(st, 0, 1) 先过 rotate 再过 translate，
    // z 不为 0 才能让透视除法的 w ≠ 0。
    float fov = 140.0;
    float zNear = 0.1;
    float zFar = 100.0;
    float zRange = zNear - zFar;
    float tanHalfFOV = tan(radians(fov / 2.0));
    mat4 perspective = mat4(
    1.0 / tanHalfFOV, 0.0, 0.0, 0.0,
    0.0, 1.0 / tanHalfFOV, 0.0, 0.0,
    0.0, 0.0, (-zNear - zFar) / zRange, 2.0 * zFar * zNear / zRange,
    0.0, 0.0, 1.0, 0.0
    );

    float fx = perFromVal(sp.x, -1.0, 1.0);
    float rx = radians(20.0);
    float ry = radians(mix(15.0, -15.0, fx));
    mat4 rotateX = mat4(
    1.0, 0.0, 0.0, 0.0,
    0.0, cos(rx), -sin(rx), 0.0,
    0.0, sin(rx), cos(rx), 0.0,
    0.0, 0.0, 0.0, 1.0
    );
    mat4 rotateY = mat4(
    cos(ry), 0.0, sin(ry), 0.0,
    0.0, 1.0, 0.0, 0.0,
    -sin(ry), 0.0, cos(ry), 0.0,
    0.0, 0.0, 0.0, 1.0
    );
    mat4 translate = mat4(
    1.0, 0.0, 0.0, 0.0,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 3.0, 1.0
    );
    vWorldSpaceMat = perspective * translate * (rotateY * rotateX);
}
