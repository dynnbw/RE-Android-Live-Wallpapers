precision mediump float;									
uniform mat4 u_MVPMatrix;									
attribute vec4 a_Position;									
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
	float temp;												
	alpha = a_life - (a_time * 10.0 * a_age);				
	temp = alpha;											
	time = a_time;											
	alpha = 0.0;											
	if(temp < 0.0){											
		float td = a_life/a_age;							
		td /= 10.0;											
		float df = a_time/td;								
		int div = int(df);									
		df = float(div);									
		td *= df;											
		time = a_time - td;									
		alpha = a_life - (time * 10.0 * a_age);				
		if(div > 1)alpha=0.0;								
	}														
	gl_PointSize = a_size;									
 	if(gl_PointSize < 0.0)gl_PointSize = 0.0;				
	gl_Position = a_Position;								
	gl_Position += (time * a_move * 1.2);					
	float angle = time * 6.0;								
	float r = 0.5 * a_life;										
	float moveX = gl_Position.x + r * cos(angle);			
	gl_Position.x += moveX;									
	gl_Position = u_MVPMatrix * gl_Position;				
}