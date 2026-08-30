attribute vec4 aPosition;

uniform mat4 uMVPMatrix;

uniform vec3 u_Time_NoiseScale_Color;
uniform vec4 u_NoisePos01;

varying vec4 vPosition;
varying vec4 vDx;
varying vec4 vDy;

// ----------------------------------------------------------------------
// 注:原版在文件作用域用 uniform 初始化别名(float u_NoiseScale = u_Time_NoiseScale_Color.y;),
// 现代 GLSL ES 编译器禁止(初始化器须为常量表达式),已改为函数内直接引用 uniform。
// u_NoiseScale 原版即未使用(仅注释提及),uColorTime 亦为死代码,一并省略。

const float POWER_LO = 0.03;
const float POWER_HI = 0.05;
const float TURB = 3.5;
const float SPEED = 0.2;

// Using constants for values used more than once, is an optimization due to the nature of
// shader compilation
const float ZERO = 0.0;

const float PI_TWICE = 3.1415926535 * 2.0;

const vec4 NOISE_SCALE = vec4(0.7, 1.3, 0.7, 1.0);

float wave(float x, vec3 params)
{
    float waveParam = x * params.x + params.y + params.z;
    return sin(mod(waveParam, PI_TWICE));
}

float addNoiseForAxis(float wave1, float wave2, float wave3, float wave4)
{
    float ret = ZERO;

    ret += POWER_HI * wave1 * wave2;
    ret += POWER_HI * wave3 * wave4;
    ret += POWER_LO * wave3 * wave2;

    return ret;
}

vec4 addNoise0(vec4 pos)
{
    // This represents the moving texture noise coordinate that is in the noise lookup version of Cosmic Flow.
    vec2 texPos0 = pos.xy + u_NoisePos01.xy * 3.0;

    float x = texPos0.x;
    float y = texPos0.y;
    float timeSpeedHi = u_Time_NoiseScale_Color.x * SPEED;
    float ret = ZERO;

    float wave1 = wave(x, vec3((TURB * 5.34), 9.13, timeSpeedHi));
    float wave2 = wave(y, vec3((TURB * 7.54), 3.33, -timeSpeedHi));
    float wave3 = wave(x, vec3(TURB * 3.54, 6.13, -timeSpeedHi));
    float wave4 = wave(y, vec3(TURB * 5.24, 4.33, timeSpeedHi));

    float wave5 = wave(x, vec3(TURB * 7.12, 5.14, timeSpeedHi));
    float wave6 = wave(y, vec3(TURB * 1.375, 7.43, -timeSpeedHi));
    float wave7 = wave(x, vec3(TURB * 3.54, 3.43, -timeSpeedHi));

    float wave8 = wave(x, vec3(TURB * 2.23, 4.44, timeSpeedHi));
    float wave9 = wave(y, vec3(TURB * 4.199, 1.84, -timeSpeedHi));
    float wave10 = wave(x, vec3(TURB * 3.54, 5.13, -timeSpeedHi));

    float nx = addNoiseForAxis(wave1, wave2, wave3, wave4);
    //float ny = addNoiseForAxis(wave5, wave6, wave7, wave8);
    float nz = addNoiseForAxis(wave8, wave9, wave10, wave1);

    // Since the Mali 400 GPU cannot compile this shader with addNoiseForAxis for ny,
    // most likely since it results in too much binary shader code, we let the noise
    // for ny be equal to the noise for nx. However after reviewing this carefully,
    // there doesn't seem to be any difference at all in the look of the flow so
    // this should not be a problem. The noise for the X and Z axises is what is most
    // apparent since the Y axis goes into the screen due to the rotated flow mesh.
    float ny = nx;

    return vec4(pos.x + nx, pos.y + ny, ZERO + nz, 1.0);
}

/*
 * Calculate the distance to another position in the grid. The result will be
 * an approximation of the distance across the defined interval over the
 * transformed crinkled grid. The exact distance is unknown since we will have
 * different values in u_NoisePos01 next time.
 */
vec4 delta(vec4 pos, float dx, float dy, vec4 newPos)
{
    return uMVPMatrix * addNoise0(pos + vec4(dx, dy, ZERO, ZERO)) * NOISE_SCALE - newPos;
}

void main() {
    gl_Position = uMVPMatrix * addNoise0(aPosition) * NOISE_SCALE;

    // Calculate the transparency, based on the normal and area size
    vDx = delta(aPosition, 0.045, ZERO, gl_Position);
    vDy = delta(aPosition, ZERO, 0.045, gl_Position);

    vPosition = aPosition;
}
