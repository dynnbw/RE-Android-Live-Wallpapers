#version 300 es
precision mediump float;

out vec4 fragColor;
uniform sampler2D uTexture0;

in lowp vec4 vColor;
in lowp float vFactor1;
in lowp float vFactor2;

void main() {
    lowp vec4 texColor = texture(uTexture0, gl_PointCoord);
    fragColor.a = vColor.a * (texColor.r * vFactor1 + texColor.g * vFactor2);
    fragColor.rgb = vColor.rgb;
}
