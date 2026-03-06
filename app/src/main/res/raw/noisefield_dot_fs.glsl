precision mediump float;
varying float alpha;
uniform sampler2D UNI_Tex0;
void main() {
    lowp vec4 texColor = texture2D(UNI_Tex0, gl_PointCoord);
    texColor.a = texColor.a * alpha;
    gl_FragColor = texColor;
}
