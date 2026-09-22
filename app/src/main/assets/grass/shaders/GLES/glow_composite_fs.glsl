#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uStrength;
in vec2 vUv;
void main() {
  vec3 scene = texture(uScene, vUv).rgb;
  vec3 bloom = texture(uBloom, vUv).rgb;
  // **alpha 必须写 1。** 场景纹理带 alpha（草叶与精灵都是半透明的），
  // 照抄到屏幕会改变壁纸自身的合成方式。
  fragColor = vec4(scene + bloom * uStrength, 1.0);
}
