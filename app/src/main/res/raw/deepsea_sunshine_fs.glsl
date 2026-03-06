precision mediump float;
uniform sampler2D s_texture;
uniform float u_Light;
uniform float u_AddAlpha;
varying vec2 v_texCoord;
void main(){
    float val = u_Light;
    vec4 addColor = vec4(val, val, val, u_AddAlpha);
    gl_FragColor = texture2D(s_texture, v_texCoord) + addColor;
}
