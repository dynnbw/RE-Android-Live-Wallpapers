#version 300 es
precision mediump float;
out vec4 fragColor;
in float alpha;
uniform sampler2D UNI_Tex0;
void main() {
    lowp vec4 texColor = texture(UNI_Tex0, gl_PointCoord);
    texColor.a = texColor.a * alpha;
    fragColor = texColor;
}
