uniform mat4 uMVPMatrix;
uniform vec2 uResolution;
uniform vec2 uSunPos;

attribute vec2 aPosition;
attribute vec2 aTexCoord;

varying vec2 vUv;
varying vec2 vSunPos;

void main() {
  gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
  vUv = aTexCoord - 0.5;
  vUv.x *= uResolution.x / uResolution.y;
  vSunPos = uSunPos / uResolution - 0.5;
  vSunPos.x *= uResolution.x / uResolution.y;
}
