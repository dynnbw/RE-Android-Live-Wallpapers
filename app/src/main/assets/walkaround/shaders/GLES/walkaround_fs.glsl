#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
out vec4 fragColor;
in vec2 vTex;
uniform samplerExternalOES uTex;
void main() {
  fragColor = texture(uTex, vTex);
}
