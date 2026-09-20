#version 300 es
// 机器人部件精灵:屏幕坐标正交投影(y 向下),MVP 由 CPU 侧合成
in vec2 aPosition;
in vec2 aTexCoord;

uniform mat4 uMvpMatrix;

out vec2 vTexCoord;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
}
