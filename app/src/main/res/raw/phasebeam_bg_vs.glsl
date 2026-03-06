varying lowp vec4 color;
varying vec3 adjust;

attribute vec3 ATTRIB_position;
attribute float ATTRIB_offsetX;
attribute vec4 ATTRIB_realColor;
attribute vec3 ATTRIB_adjust;

void main() {
    adjust = ATTRIB_adjust;
    color = ATTRIB_realColor;
    gl_Position = vec4(ATTRIB_position.x + ATTRIB_offsetX/3.5, ATTRIB_position.y, 0.0, 1.0);
}
