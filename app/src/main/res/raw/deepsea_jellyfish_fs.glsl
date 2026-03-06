precision mediump float;
varying vec4 v_AddColor;
varying vec2 v_TexCoord;
uniform sampler2D s_Texture;
uniform sampler2D s_AlphaTexture;
uniform float u_AddAlpha;
void main(){
    vec4 texture;
    vec4 alphaTexture;
    texture = texture2D(s_Texture, v_TexCoord);
    alphaTexture = texture2D(s_AlphaTexture, v_TexCoord);
    texture.a = alphaTexture.r;
    gl_FragColor = texture + v_AddColor + vec4(0.0, 0.0, 0.0, u_AddAlpha);
}
