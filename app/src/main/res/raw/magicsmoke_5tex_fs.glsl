// Magic Smoke 5-texture fragment shader
precision mediump float;

uniform vec4 uClearColor;
uniform sampler2D uTexture0;
uniform sampler2D uTexture1;
uniform sampler2D uTexture2;
uniform sampler2D uTexture3;
uniform sampler2D uTexture4;

varying vec2 vTexCoord0;
varying vec2 vTexCoord1;
varying vec2 vTexCoord2;
varying vec2 vTexCoord3;
varying vec2 vTexCoord4;

void main() {
    vec4 color = uClearColor;
    
    vec4 tex = texture2D(uTexture0, vTexCoord0);
    color = mix(color, tex, tex.a);
    
    tex = texture2D(uTexture1, vTexCoord1);
    color = mix(color, tex, tex.a);
    
    tex = texture2D(uTexture2, vTexCoord2);
    color = mix(color, tex, tex.a);
    
    tex = texture2D(uTexture3, vTexCoord3);
    color = mix(color, tex, tex.a);
    
    tex = texture2D(uTexture4, vTexCoord4);
    color = mix(color, tex, tex.a);
    
    gl_FragColor = color;
}
