#version 300 es
precision mediump float;									
out vec4 fragColor;
uniform sampler2D u_texture;								
uniform sampler2D s_AlphaTexture;							
uniform float u_AddAlpha;									
in float alpha;										
in vec4 v_AddColor;									
void main(){												
	vec4 tex = texture(u_texture, gl_PointCoord);			
	vec4 alphaTexture;										
	alphaTexture = texture(s_AlphaTexture, gl_PointCoord);
	fragColor = tex + v_AddColor;						
	fragColor.w = (alphaTexture.r * alpha + u_AddAlpha) * 0.5;			
}