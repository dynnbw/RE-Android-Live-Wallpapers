attribute vec2 aPosition;
attribute vec2 aTexCoord;

uniform mat4 uMvpMatrix;

varying mediump vec2 vTexCoord;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
}
