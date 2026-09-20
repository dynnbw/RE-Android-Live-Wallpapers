#version 300 es
// Port of the original vivo CSilk vertex shader.
// Hardcoded MVP maps world 0..1080 x 0..1920 to full screen
// (design space 540x960, world = 2x design), matching the original exactly.
precision mediump float;
in vec4 a_position;
in float a_color;
in vec2 a_coord;
uniform vec3 rotateAngleFlash;   // (sin(rot), cos(rot), flash)
uniform vec4 uNewPos;            // ribbon placement (x, y, 0, 0)
// 接口变量的精度必须两侧一致(ESSL 3.00 会因此链接报错)，所以这里显式写死；
// 不写的话顶点侧会落到 mediump、片段侧的 lowp 默认上，两边对不上。
out mediump float v_fragmentColor;
out mediump vec2 v_coord;
out mediump vec4 localPos;
out mediump float flash;
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
