#version 300 es
precision mediump float;

out vec4 fragColor;
uniform sampler2D uTexture;
uniform vec4 uColor;

in vec2 vTexCoord;

void main() {
    fragColor = texture(uTexture, vTexCoord) * uColor;
}
