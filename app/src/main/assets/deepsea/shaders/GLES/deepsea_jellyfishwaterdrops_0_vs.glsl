precision mediump float;									
uniform mat4 u_MVPMatrix;									
attribute vec4 a_Position;									
attribute vec4 a_EmitterPosition;							
attribute vec4 a_move;										uniform float a_time;										
attribute float a_Scale;									
attribute float a_life;									
attribute float a_age;										
attribute float a_size;									
attribute float a_angle;									
attribute float a_speed;									
attribute vec4 a_AddColor;									
varying float alpha;										
varying vec4 v_AddColor;									
float time;												
void main(){												
		float td = a_life/a_age;							
		td /= 10.0;											
		float df = a_time/td;								
		int div = int(df);									
		df = float(div);									
		td *= df;											
		time = a_time - td;									
	float tempAlpha = (time * 20.0 * a_age);				
	if(tempAlpha >= a_life){								
		alpha = a_life * 2.0 - tempAlpha;					
	}else{													
		alpha = tempAlpha;									
	}														
	gl_Position = a_Position;								
	vec4 move = a_move;										
	move.x *= a_Scale;										
	move.y *= a_Scale;										
	gl_Position += (time * move * 0.6);						
	gl_Position = u_MVPMatrix * gl_Position;				
	gl_PointSize = (a_size - gl_Position.z) * a_Scale;		
 	if(gl_PointSize < 0.0)gl_PointSize = 0.0;				
	vec4 ePosition = a_EmitterPosition + gl_Position;		
	v_AddColor = a_AddColor;								
}