#version 450

/*
 * 夜空银河球的顶点着色器 —— 对应 GLES 的 nightsky_sphere_vs.glsl。
 *
 * uProjViewRot 是 uProj × uViewRot 的预乘结果（viewRot 是仿射矩阵，w=1，可以直接合并），
 * 省掉一个 mat4 的 push constant。矩阵仍是 GL 裁剪空间，最后一行按 GL → Vulkan 修正：
 * y 取反，z 从 [-w, w] 映射到 [0, w]。
 */

layout(push_constant) uniform PushConstants {
    mat4 uProjViewRot;
    float uLatitudeRad;
    float uLSTRad;
    float uMilkyBrightness;
} pc;

layout(location = 0) in vec2 aUv;

layout(location = 0) out vec2 vUv;

const float PI = 3.14159265358979323846;

vec3 equatorialToHorizon(float ra, float dec, float latitude, float lst) {
    float h = lst - ra;
    float sinDec = sin(dec);
    float cosDec = cos(dec);
    float sinLat = sin(latitude);
    float cosLat = cos(latitude);
    float sinH = sin(h);
    float cosH = cos(h);

    float east = -cosDec * sinH;
    float north = sinDec * cosLat - cosDec * sinLat * cosH;
    float up = sinDec * sinLat + cosDec * cosLat * cosH;
    return vec3(east, north, up);
}

void main() {
    float ra = (aUv.x * PI * 2.0) - PI;
    float dec = (0.5 - aUv.y) * PI;
    vec3 horizon = equatorialToHorizon(ra, dec, pc.uLatitudeRad, pc.uLSTRad);
    vec4 clip = pc.uProjViewRot * vec4(horizon * 5.0, 1.0);
    gl_Position = vec4(clip.x, -clip.y, 0.5 * clip.z + 0.5 * clip.w, clip.w);
    vUv = aUv;
}
