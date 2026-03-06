precision mediump float;
uniform sampler2D u_texture;
uniform sampler2D s_AlphaTexture;
varying float alpha;
void main(){
    vec4 tex = texture2D(u_texture, gl_PointCoord);
    vec4 alphaTexture;
    alphaTexture = texture2D(s_AlphaTexture, gl_PointCoord);
    gl_FragColor = tex;
    gl_FragColor.w = alphaTexture.r * alpha * 0.5;
}
