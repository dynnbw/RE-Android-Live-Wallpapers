#version 300 es
// 每个光斑一个四边形：corner 在 [-1,1]，center/radius 在屏幕像素坐标里。
in vec2 aCorner;
in vec2 aCenter;
in float aRadius;
in vec4 aFill;
in vec3 aRing;

uniform mat4 uMVP;

out vec2 vCorner;
out vec4 vFill;
out vec3 vRing;
out float vRadius;

void main() {
    vec2 pos = aCenter + aCorner * aRadius;
    gl_Position = uMVP * vec4(pos, 0.0, 1.0);
    vCorner = aCorner;
    vFill = aFill;
    vRing = aRing;
    vRadius = aRadius;
}
