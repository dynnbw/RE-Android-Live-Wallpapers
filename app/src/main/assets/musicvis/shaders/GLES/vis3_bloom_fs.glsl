#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uStrength;
in vec2 vUv;
void main() {
  // 冷光外溢：波形本体保持原样，只把亮部的模糊叠回去
  fragColor = vec4(texture(uScene, vUv).rgb + texture(uBloom, vUv).rgb * uStrength, 1.0);
}
