#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uSampler;
in vec4 vColor;
in highp vec2 vTexCoord;
void main() {
  float a = texture(uSampler, vTexCoord).r;
  // vColor.a 现在装的是叶尖位置（原来是常数 1），不能再乘进输出 alpha —— 那会把叶片淡掉。
  // 与之配对的是 GrassVertexChannelTest。
  fragColor = vec4(vColor.rgb, a);
}
