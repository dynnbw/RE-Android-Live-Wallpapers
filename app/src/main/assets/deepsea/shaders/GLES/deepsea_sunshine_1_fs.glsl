#version 300 es
precision mediump float;							
out vec4 fragColor;
uniform sampler2D s_texture;						
uniform float u_Light;								
uniform float u_AddAlpha;							
in vec2 v_texCoord;							
void main(){										
	float val = u_Light;							
	vec4 addColor = vec4(val, val, val, u_AddAlpha);		
	fragColor = texture(s_texture, v_texCoord) + addColor; 
}													
