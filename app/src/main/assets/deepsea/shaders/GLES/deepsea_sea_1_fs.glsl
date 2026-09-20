#version 300 es
precision mediump float;							
out vec4 fragColor;
in vec2 v_texCoord;							
uniform float u_brightness;						
uniform sampler2D s_texture;						
void main(){										
	float val = u_brightness;						
 	vec4 color = vec4(val, val, val, 0.0);			
	fragColor = texture(s_texture, v_texCoord) + color; 
}													
