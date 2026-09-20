#version 300 es
// Magic Smoke 5-texture fragment shader
precision mediump float;

out vec4 fragColor;
uniform vec4 uClearColor;
uniform sampler2D uTexture0;
uniform sampler2D uTexture1;
uniform sampler2D uTexture2;
uniform sampler2D uTexture3;
uniform sampler2D uTexture4;

in vec2 vTexCoord0;
in vec2 vTexCoord1;
in vec2 vTexCoord2;
in vec2 vTexCoord3;
in vec2 vTexCoord4;

void main() {
    vec4 color = uClearColor;
    
    vec4 tex = texture(uTexture0, vTexCoord0);
    color = mix(color, tex, tex.a);
    
    tex = texture(uTexture1, vTexCoord1);
    color = mix(color, tex, tex.a);
    
    tex = texture(uTexture2, vTexCoord2);
    color = mix(color, tex, tex.a);
    
    tex = texture(uTexture3, vTexCoord3);
    color = mix(color, tex, tex.a);
    
    tex = texture(uTexture4, vTexCoord4);
    color = mix(color, tex, tex.a);
    
    fragColor = color;
}
