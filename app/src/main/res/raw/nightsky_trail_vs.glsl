attribute vec2 aPos;
attribute vec4 aColor;

varying vec4 vColor;

void main() {
    vColor = aColor;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
