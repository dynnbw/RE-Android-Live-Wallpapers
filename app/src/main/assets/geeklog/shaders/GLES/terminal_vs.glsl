attribute vec2 aPosition;
attribute vec2 aUV;
attribute vec3 aColor;

varying vec2 vUV;
varying vec3 vColor;

void main() {
    vUV = aUV;
    vColor = aColor;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
