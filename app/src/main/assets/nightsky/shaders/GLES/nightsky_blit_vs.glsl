attribute vec2 aPos;
attribute vec2 aUv;
varying highp vec2 vUv;

void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
