#version 300 es
out lowp vec4 color;
in vec3 ATTRIB_position;
in vec4 ATTRIB_color;
void main() {
    color = ATTRIB_color;
    gl_Position = vec4(ATTRIB_position.x, ATTRIB_position.y, 0.0, 1.0);
}
