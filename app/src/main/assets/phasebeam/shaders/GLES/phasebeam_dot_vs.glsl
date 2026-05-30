varying float pointSize;
varying vec3 adjust;

attribute vec3 ATTRIB_position;
attribute float ATTRIB_offsetX;
attribute vec3 ATTRIB_adjust;

uniform float UNI_scaleSize;

void main() {
    vec4 objPos = vec4(ATTRIB_position, 1.0);
    float tmpPointSize = ATTRIB_position.z*7.0;
    pointSize = 0.5-tmpPointSize/1000.0;
    objPos.z = 0.0;
    objPos.x = objPos.x - ATTRIB_offsetX * tmpPointSize/100.0;
    adjust = ATTRIB_adjust;
    gl_Position = objPos;
    gl_PointSize = tmpPointSize*UNI_scaleSize;
}
