uniform mat4 u_MVPMatrix;						
attribute vec4 a_Position;						
attribute float a_Scale;						
attribute vec4 a_AddColor;						
attribute vec2 a_TexCoord;						
varying vec4 v_AddColor;						
varying vec2 v_TexCoord;						
void main(){									
	gl_Position = a_Position;					
	gl_Position.x *= a_Scale;					
	gl_Position.y *= a_Scale;					
	gl_Position = u_MVPMatrix * gl_Position;	
 	v_TexCoord = a_TexCoord;					
 	v_AddColor = a_AddColor;					
}