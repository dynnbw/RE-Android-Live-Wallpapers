#version 300 es
precision mediump float;

out vec4 fragColor;
uniform sampler2D uTexture;

in vec2 vUV;
in vec3 vColor;

void main() {
    float a = texture(uTexture, vUV).a;
    fragColor = vec4(vColor, a);
}
