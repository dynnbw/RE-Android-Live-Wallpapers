#version 300 es
precision mediump float;

out vec4 fragColor;
uniform sampler2D uTexture;
uniform vec4 uColor;

in mediump vec2 vTexCoord;

void main() {
    vec4 texColor = texture(uTexture, vTexCoord);
    fragColor = texColor * uColor;
}
