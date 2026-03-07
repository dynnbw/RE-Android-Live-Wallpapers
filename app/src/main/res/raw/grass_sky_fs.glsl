precision mediump float;
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
varying vec2 vTexCoord;
void main() {
  vec2 nightUV = mix(vTexCoord, vec2(vTexCoord.x, 1.0 - vTexCoord.y), uNightInvert);
  vec4 night = texture2D(uTexNight, nightUV);
  vec4 sunrise = texture2D(uTexSunrise, vTexCoord);
  vec4 sunset = texture2D(uTexSunset, vTexCoord);
  vec4 sky = texture2D(uTexSky, vTexCoord);
  vec4 eclipse = texture2D(uTexSolarEclipse, vTexCoord);
  vec3 rgb = night.rgb * uWeightNight + sunrise.rgb * uWeightSunrise +
             sunset.rgb * uWeightSunset + sky.rgb * uWeightSky;
  float eclipseWeight = clamp(uWeightSolarEclipse, 0.0, 1.0);
  rgb = mix(rgb, eclipse.rgb, eclipseWeight);
  rgb *= (1.0 - 0.55 * eclipseWeight);
  float a = max(max(night.a, sunrise.a), max(sunset.a, sky.a));
  gl_FragColor = vec4(rgb, a);
}
