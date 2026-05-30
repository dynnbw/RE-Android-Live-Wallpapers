#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES uTex;
void main() {
  gl_FragColor = texture2D(uTex, vTex);
}
