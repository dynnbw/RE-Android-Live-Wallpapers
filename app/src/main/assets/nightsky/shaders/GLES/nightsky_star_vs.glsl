attribute vec4 aStar;
attribute vec3 aColor;

uniform mat4 uProj;
uniform mat4 uViewRot;
uniform float uLatitudeRad;
uniform float uLSTRad;
uniform float uTimeSec;
uniform float uTrailMode;

varying vec4 vColor;

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
    float ra = radians(aStar.x);
    float dec = radians(aStar.y);
    float brightness = max(aStar.z, 0.0);
    float normBrightness = clamp(aStar.w, 0.0, 1.0);

    vec3 horizon = equatorialToHorizon(ra, dec, uLatitudeRad, uLSTRad);
    vec4 viewPos = uViewRot * vec4(horizon * 5.0, 1.0);
    gl_Position = uProj * vec4(viewPos.xyz, 1.0);

    float pointSize = 10.0 + 80.0 * pow(normBrightness, 0.45);

    float twinkleSeed = fract(abs(sin(ra * 12.9898 + dec * 78.233)) * 43758.5453);
    float twinkle = 0.94 + 0.06 * sin(uTimeSec * (1.3 + twinkleSeed * 1.9) + ra * 11.0 + dec * 5.0);

    float alpha = clamp(0.15 + 0.85 * pow( brightness, 0.33), 0.0, 1.0) * twinkle;

    if (uTrailMode > 0.5) {
        pointSize *= 1.0;
        alpha *= 0.40;
    }

    gl_PointSize = pointSize;
    vColor = vec4(aColor, alpha);
}
