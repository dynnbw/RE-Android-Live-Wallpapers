#version 300 es
// 天空就是一个铺满屏幕的四边形；每个顶点顺带带上它在设计稿里的纵坐标，
// 片元里按这条坐标去查色带，这样天空的高度和构图一致，不随屏幕比例拉伸。
in vec2 aPos;
in float aDesignY;

uniform mat4 uMVP;

out float vDesignY;

void main() {
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
    vDesignY = aDesignY;
}
