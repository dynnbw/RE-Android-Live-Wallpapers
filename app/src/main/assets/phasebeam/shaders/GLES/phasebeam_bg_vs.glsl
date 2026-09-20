#version 300 es
out lowp vec4 color;
out vec3 adjust;

in vec3 ATTRIB_position;
in float ATTRIB_offsetX;
in vec4 ATTRIB_realColor;
in vec3 ATTRIB_adjust;

void main() {
    adjust = ATTRIB_adjust;
    color = ATTRIB_realColor;
    gl_Position = vec4(ATTRIB_position.x + ATTRIB_offsetX/3.5, ATTRIB_position.y, 0.0, 1.0);
}
