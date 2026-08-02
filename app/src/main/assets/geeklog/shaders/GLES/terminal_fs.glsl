precision mediump float;

uniform sampler2D uTexture;

varying vec2 vUV;
varying vec3 vColor;

void main() {
    float a = texture2D(uTexture, vUV).a;
    gl_FragColor = vec4(vColor, a);
}
