#version 300 es
// 茎、花头、浮尘、种子都用同一张四边形：顶点坐标已经在 CPU 上算成屏幕像素。
in vec2 aPos;
in vec2 aUV;
in float aAlpha;

uniform mat4 uMVP;

out vec2 vUV;
out float vAlpha;

void main() {
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
    vUV = aUV;
    vAlpha = aAlpha;
}
