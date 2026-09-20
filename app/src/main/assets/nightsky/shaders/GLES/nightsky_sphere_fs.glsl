#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

out vec4 fragColor;
in highp vec2 vUv;

uniform sampler2D uMilkyTex;
uniform float uMilkyBrightness;

void main() {
    // Align Milky Way map longitude with the sky model (summer/winter side fix).
    vec2 uv = vec2(fract(vUv.x + 0.5), clamp(vUv.y, 0.0, 1.0));
    vec3 color = texture(uMilkyTex, uv).rgb * uMilkyBrightness;
    fragColor = vec4(color, 1.0);
}
