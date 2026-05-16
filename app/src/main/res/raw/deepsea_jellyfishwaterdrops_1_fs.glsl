precision mediump float;									
uniform sampler2D u_texture;								
uniform sampler2D s_AlphaTexture;							
uniform float u_AddAlpha;									
varying float alpha;										
varying vec4 v_AddColor;									
void main(){												
	vec4 tex = texture2D(u_texture, gl_PointCoord);			
	vec4 alphaTexture;										
	alphaTexture = texture2D(s_AlphaTexture, gl_PointCoord);
	gl_FragColor = tex + v_AddColor;						
	gl_FragColor.w = (alphaTexture.r * alpha + u_AddAlpha) * 0.5;			
}