#version 300 es
precision mediump float;									
out vec4 fragColor;
uniform sampler2D u_texture;								
uniform sampler2D s_AlphaTexture;							
in float alpha;										
void main(){												
	vec4 tex = texture(u_texture, gl_PointCoord);			
	vec4 alphaTexture;										
	alphaTexture = texture(s_AlphaTexture, gl_PointCoord);
	fragColor = tex;										
	fragColor.w = alphaTexture.r * alpha * 0.5;							
}