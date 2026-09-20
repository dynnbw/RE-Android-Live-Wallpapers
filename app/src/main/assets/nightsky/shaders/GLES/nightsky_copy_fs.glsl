#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

out vec4 fragColor;
in highp vec2 vUv;
uniform sampler2D uTex;

void main() {
    fragColor = texture(uTex, vUv);
}
