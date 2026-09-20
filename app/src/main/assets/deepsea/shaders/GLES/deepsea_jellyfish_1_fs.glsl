#version 300 es
precision mediump float;						
out vec4 fragColor;
in vec4 v_AddColor;						
in vec2 v_TexCoord;						
uniform sampler2D s_Texture;					
uniform sampler2D s_AlphaTexture;				
uniform float u_AddAlpha;						
void main(){									
	vec4 texel;								
	vec4 alphaTexture;							
	texel = texture(s_Texture, v_TexCoord);	
	alphaTexture = texture(s_AlphaTexture, v_TexCoord);
	texel.a = alphaTexture.r;					
	fragColor = texel + v_AddColor + vec4(0.0, 0.0, 0.0, u_AddAlpha); 
}