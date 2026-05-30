attribute vec3 aPosition;
attribute vec4 aColor;

varying lowp vec4 vColor;

void main() {
    vColor = aColor;
    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
}
