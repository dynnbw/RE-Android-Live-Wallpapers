precision mediump float;

varying vec4 vColor;

void main() {
    vec2 d = gl_PointCoord - vec2(0.5);
    float r = length(d) * 2.0;
    float edge = smoothstep(1.0, 0.0, r);
    float alpha = vColor.a * edge * edge;
    gl_FragColor = vec4(vColor.rgb * alpha, alpha);
}
