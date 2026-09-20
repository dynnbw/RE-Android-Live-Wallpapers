#version 300 es
uniform mat4 u_MVPMatrix;							
in vec4 a_position;							
in vec2 a_texCoord;							
out vec2 v_texCoord;							
void main(){										
	gl_Position = u_MVPMatrix * a_position;			
	v_texCoord = a_texCoord;						
}