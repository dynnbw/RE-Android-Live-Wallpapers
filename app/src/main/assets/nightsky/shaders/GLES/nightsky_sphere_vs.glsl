attribute vec2 aUv;

uniform mat4 uProj;
uniform mat4 uViewRot;
uniform float uLatitudeRad;
uniform float uLSTRad;

varying vec2 vUv;

const float PI = 3.14159265358979323846;

vec3 equatorialToHorizon(float ra, float dec, float latitude, float lst) {
    float h = lst - ra;
    float sinDec = sin(dec);
    float cosDec = cos(dec);
    float sinLat = sin(latitude);
    float cosLat = cos(latitude);
    float sinH = sin(h);
    float cosH = cos(h);

    float east = -cosDec * sinH;
    float north = sinDec * cosLat - cosDec * sinLat * cosH;
    float up = sinDec * sinLat + cosDec * cosLat * cosH;
    return vec3(east, north, up);
}

void main() {
    float ra = (aUv.x * PI * 2.0) - PI;
    float dec = (0.5 - aUv.y) * PI;
    vec3 horizon = equatorialToHorizon(ra, dec, uLatitudeRad, uLSTRad);
    vec4 viewPos = uViewRot * vec4(horizon * 5.0, 1.0);
    gl_Position = uProj * vec4(viewPos.xyz, 1.0);
    vUv = aUv;
}
