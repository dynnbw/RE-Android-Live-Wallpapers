attribute vec3 aPosition;
attribute vec4 aColor;

uniform mat4 uModelViewProj;
uniform float uMaxPointSize;
uniform float uFarPlane;

varying lowp vec4 vColor;
varying lowp float vFactor1;
varying lowp float vFactor2;

void main() {
    gl_Position = uModelViewProj * vec4(aPosition, 1.0);
    vFactor2 = (uFarPlane - abs(gl_Position.z)) / uFarPlane;
    gl_PointSize = vFactor2 * uMaxPointSize;
    vColor = aColor;
    vColor.a = vColor.a * vFactor2;
    vFactor2 = abs((vFactor2 * 2.0) - 1.0);
    vFactor1 = (1.0 - vFactor2) * 0.2;
}
