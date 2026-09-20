#version 300 es
// 部件着色:原版按 new = c*(255-color)/255 + color 把黑色剪影染成目标颜色
// (白色细节保持不变),即 mix(color, white, tex.rgb)
precision mediump float;

out vec4 fragColor;
uniform sampler2D uTexture;
uniform vec3 uTint;
uniform float uAlpha;

in vec2 vTexCoord;

void main() {
    vec4 tex = texture(uTexture, vTexCoord);
    vec3 rgb = mix(uTint, vec3(1.0), tex.rgb);
    fragColor = vec4(rgb, tex.a * uAlpha);
}
