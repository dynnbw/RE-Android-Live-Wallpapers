#version 300 es
precision mediump float;
out vec4 fragColor;
uniform sampler2D uTexNight;
uniform sampler2D uTexSunrise;
uniform sampler2D uTexSunset;
uniform sampler2D uTexSky;
uniform sampler2D uTexSolarEclipse;
uniform float uWeightNight;
uniform float uWeightSunrise;
uniform float uWeightSunset;
uniform float uWeightSky;
uniform float uWeightSolarEclipse;
uniform float uNightInvert;
in highp vec2 vTexCoord;
void main() {
  vec2 nightUV = mix(vTexCoord, vec2(vTexCoord.x, 1.0 - vTexCoord.y), uNightInvert);
  vec4 night = texture(uTexNight, nightUV);
  vec4 sunrise = texture(uTexSunrise, vTexCoord);
  vec4 sunset = texture(uTexSunset, vTexCoord);
  vec4 sky = texture(uTexSky, vTexCoord);
  vec4 eclipse = texture(uTexSolarEclipse, vTexCoord);
  vec3 rgb = night.rgb * uWeightNight + sunrise.rgb * uWeightSunrise +
             sunset.rgb * uWeightSunset + sky.rgb * uWeightSky;
  float eclipseWeight = clamp(uWeightSolarEclipse, 0.0, 1.0);
  rgb = mix(rgb, eclipse.rgb, eclipseWeight);
  rgb *= (1.0 - 0.55 * eclipseWeight);
  float a = max(max(night.a, sunrise.a), max(sunset.a, sky.a));
  fragColor = vec4(rgb, a);
}
