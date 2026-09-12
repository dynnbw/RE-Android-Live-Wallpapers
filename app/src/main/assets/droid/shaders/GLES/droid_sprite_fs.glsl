// 部件着色:原版按 new = c*(255-color)/255 + color 把黑色剪影染成目标颜色
// (白色细节保持不变),即 mix(color, white, tex.rgb)
precision mediump float;

uniform sampler2D uTexture;
uniform vec3 uTint;
uniform float uAlpha;

varying vec2 vTexCoord;

void main() {
    vec4 tex = texture2D(uTexture, vTexCoord);
    vec3 rgb = mix(uTint, vec3(1.0), tex.rgb);
    gl_FragColor = vec4(rgb, tex.a * uAlpha);
}
