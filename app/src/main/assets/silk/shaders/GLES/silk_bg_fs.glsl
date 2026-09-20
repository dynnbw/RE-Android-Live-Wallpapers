#version 300 es
// Full-screen background quad (port of COriginal pass-through shader).
precision lowp float;
out vec4 fragColor;
uniform sampler2D sTexture;
in vec2 vTextureCoord;
void main() {
    fragColor = texture(sTexture, vTextureCoord);
}
