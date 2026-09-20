#version 300 es
precision mediump float;									
uniform mat4 u_MVPMatrix;									
in vec4 a_Position;									
in vec4 a_move;										
uniform float a_time;										
in float a_life;									
in float a_age;										
in float a_size;									
in float a_angle;									
in float a_speed;									
out float alpha;										
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
	gl_Position += (time * a_move * 0.5);					
	float angle = time * 6.0;								
	float r = 0.2;											
	float moveX = gl_Position.x + r * cos(angle);			
	gl_Position.x += moveX;									
	gl_Position = u_MVPMatrix * gl_Position;				
}