// Port of the original vivo CSilk vertex shader.
// Hardcoded MVP maps world 0..1080 x 0..1920 to full screen
// (design space 540x960, world = 2x design), matching the original exactly.
precision mediump float;
attribute vec4 a_position;
attribute float a_color;
attribute vec2 a_coord;
uniform vec3 rotateAngleFlash;   // (sin(rot), cos(rot), flash)
uniform vec4 uNewPos;            // ribbon placement (x, y, 0, 0)
varying float v_fragmentColor;
varying vec2 v_coord;
varying vec4 localPos;
varying float flash;
#define MVPMatrix0 vec4(3.0792017, 0.0, 0.0, 0.0)
#define MVPMatrix1 vec4(0.0, 1.7320509, 0.0, 0.0)
#define MVPMatrix2 vec4(0.0, 0.0, -1.00766277, -1.0)
#define MVPMatrix3 vec4(-1662.76892, -1662.7688, 1652.68188, 1660.03809)
#define RotationMatrix2 vec4(0.0, 0.0, 1.0, 0.0)
#define RotationMatrix3 vec4(0.0, 0.0, 0.0, 1.0)
void main() {
    mat4 rotation_matrix, CC_MVPMatrix;
    CC_MVPMatrix[0] = MVPMatrix0;
    CC_MVPMatrix[1] = MVPMatrix1;
    CC_MVPMatrix[2] = MVPMatrix2;
    CC_MVPMatrix[3] = MVPMatrix3;
    rotation_matrix[0] = vec4(rotateAngleFlash.y, -rotateAngleFlash.x, 0.0, 0.0);
    rotation_matrix[1] = vec4(rotateAngleFlash.xy, 0.0, 0.0);
    rotation_matrix[2] = RotationMatrix2;
    rotation_matrix[3] = RotationMatrix3;
    vec4 new_Position = rotation_matrix * a_position + uNewPos;
    gl_Position = CC_MVPMatrix * new_Position;
    localPos = a_position;
    v_fragmentColor = a_color;
    v_coord = a_coord;
    flash = rotateAngleFlash.z;
}
