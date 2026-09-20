#version 300 es
uniform mat4 u_MVPMatrix;						
in vec4 a_Position;						
in float a_Scale;						
in vec4 a_AddColor;						
in vec2 a_TexCoord;						
out vec4 v_AddColor;						
out vec2 v_TexCoord;						
void main(){									
	gl_Position = a_Position;					
	gl_Position.x *= a_Scale;					
	gl_Position.y *= a_Scale;					
	gl_Position = u_MVPMatrix * gl_Position;	
 	v_TexCoord = a_TexCoord;					
 	v_AddColor = a_AddColor;					
}