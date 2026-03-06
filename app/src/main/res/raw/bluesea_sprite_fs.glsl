precision mediump float;

uniform sampler2D uTexture;
uniform vec4 uColor;

varying mediump vec2 vTexCoord;

void main() {
    vec4 texColor = texture2D(uTexture, vTexCoord);
    gl_FragColor = texColor * uColor;
}
