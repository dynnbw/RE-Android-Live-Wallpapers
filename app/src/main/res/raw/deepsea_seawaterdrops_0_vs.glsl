precision mediump float;									
uniform mat4 u_MVPMatrix;									
attribute vec4 a_Position;									
attribute vec4 a_EmitterPosition;							
attribute vec4 a_move;										
uniform float a_time;										
attribute float a_life;									
attribute float a_age;										
attribute float a_size;									
attribute float a_angle;									
attribute float a_speed;									
varying float alpha;										
float time;												
void main(){												
	alpha = a_life - (a_time * 10.0 * a_age);				
	time = a_time;											
	if(alpha < 0.0){										
		float td = a_life/a_age;							
		td /= 10.0;											
		float df = a_time/td;								
		int div = int(df);									
		df = float(div);									
		td *= df;											
		time = a_time - td;									
		alpha = a_life - (time * 10.0 * a_age);				
	}														
	gl_PointSize = a_size;									
 	if(gl_PointSize < 0.0)gl_PointSize = 0.0;				
	gl_Position = a_Position;								
	gl_Position += (time * a_move * 0.3);					
	gl_Position = u_MVPMatrix * gl_Position;				
}