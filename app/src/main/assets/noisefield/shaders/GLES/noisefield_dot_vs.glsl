#version 300 es
out float alpha;
in vec3 ATTRIB_position;
in float ATTRIB_speed;
in float ATTRIB_alpha;
uniform mat4 UNI_MVP;
uniform float UNI_scaleSize;
void main() {
    vec4 pos = vec4(ATTRIB_position.xyz, 1.0);
    gl_Position = UNI_MVP * pos;
    float pointSize = 1.0 + ATTRIB_speed * UNI_scaleSize * 2500.0;
    alpha = ATTRIB_alpha;
    gl_PointSize = pointSize;
}
