// Magic Smoke 4-texture vertex shader
attribute vec4 aPosition;

uniform vec4 uLayer0;
uniform vec4 uLayer1;
uniform vec4 uLayer2;
uniform vec4 uLayer3;
uniform vec2 uPanOffset;
uniform vec2 uAspectScale;

varying vec2 vTexCoord0;
varying vec2 vTexCoord1;
varying vec2 vTexCoord2;
varying vec2 vTexCoord3;

vec2 computeTexCoord(vec4 layer, vec2 position, float depth, vec2 panOffset) {
    float invZ = 0.35 + depth * 0.05;
    vec2 p = vec2((position.x + panOffset.x) * uAspectScale.x,
                  (position.y + panOffset.y) * uAspectScale.y);
    float x = 0.5 + 0.5 * invZ * (layer.z * (layer.y * p.x + layer.x * p.y)) + layer.w;
    float y = 0.5 + 0.5 * invZ * (layer.z * (-layer.x * p.x + layer.y * p.y));
    return vec2(x, y);
}

void main() {
    vTexCoord0 = computeTexCoord(uLayer0, aPosition.xy, 1.0, uPanOffset);
    vTexCoord1 = computeTexCoord(uLayer1, aPosition.xy, 2.0, uPanOffset);
    vTexCoord2 = computeTexCoord(uLayer2, aPosition.xy, 3.0, uPanOffset);
    vTexCoord3 = computeTexCoord(uLayer3, aPosition.xy, 4.0, uPanOffset);
    gl_Position = aPosition;
}
