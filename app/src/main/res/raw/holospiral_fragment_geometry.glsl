precision mediump float;

uniform sampler2D uTexture0;

varying lowp vec4 vColor;
varying lowp float vFactor1;
varying lowp float vFactor2;

void main() {
    lowp vec4 texColor = texture2D(uTexture0, gl_PointCoord);
    gl_FragColor.a = vColor.a * (texColor.r * vFactor1 + texColor.g * vFactor2);
    gl_FragColor.rgb = vColor.rgb;
}
