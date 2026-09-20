#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

out vec4 fragColor;
in highp vec2 vUv;
uniform sampler2D uTex;
uniform float uDecay;

void main() {
    vec4 c = texture(uTex, vUv);
    fragColor = vec4(c.rgb * uDecay, c.a * uDecay);
}
