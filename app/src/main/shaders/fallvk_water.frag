#version 450

// binding 0: 河床的树遮罩（R8_UNORM，白=天空 黑=树）
// binding 1: 雨滴与天空权重（与顶点着色器同一块）
// binding 2: 天空色带（24x256，四条 24x64 竖直渐变竖排）
layout(set = 0, binding = 0) uniform sampler2D uMaskTex;
layout(set = 0, binding = 2) uniform sampler2D uSkyTex;

layout(set = 0, binding = 1) uniform DropBlock {
    vec4 u_drop[80];
    int u_dropCount;
    vec4 uSkyWeights;
} dropBlock;

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
    float uShadow;      // 叶片影子标志；水面不用，只为与 C++ 结构体对齐
    float u_glHeight;
    float u_bgScale;
    float u_meshScaleX;
    float u_meshScaleY;
    float u_dxMul;
    float u_xOffset;
    int u_rotate;
    float uStarAmount;
    float uStarTime;
    float uStarAspect;
    float _pad0;
    float _pad1;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 1) in vec2 vScreenUv;
layout(location = 0) out vec4 outColor;

/*
 * 夜空星星：与 GLES 的 fall_water_fs.glsl 同一套哈希/闪烁逻辑。
 * 时间在 Java 侧按 30s 回绕；速度取整数倍，回绕处才不会整片一起跳。
 */
const float STAR_WRAP_S = 30.0;
const float STAR_BASE_W = 6.2831853 / STAR_WRAP_S;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

vec3 starLayer(vec2 uv, float scale, float seed, float thresh) {
    vec2 gv = fract(uv * scale) - 0.5;
    vec2 id = floor(uv * scale);

    float present = hash21(id + seed);
    if (present < thresh) return vec3(0.0);
    float hBright = hash21(id + 11.3);
    float hSpeed = hash21(id + 27.9);
    float hPhase = hash21(id + 43.1);
    float hTint = hash21(id + 61.7);

    vec2 off = (vec2(hash21(id + 1.7), hash21(id + 9.3)) - 0.5) * 0.8;
    vec2 delta = gv - off;
    delta.y *= pc.uStarAspect;
    float d = length(delta);

    float k = 2.0 + floor(hSpeed * 5.0);
    float twinkle = 0.70 + 0.30 * sin(pc.uStarTime * (STAR_BASE_W * k) + hPhase * 6.2831853);
    float core = smoothstep(0.17, 0.0, d);
    vec3 tint = mix(vec3(0.86, 0.91, 1.00), vec3(1.0), hTint);
    return tint * (core * twinkle * (0.30 + hBright * 0.70));
}

vec3 starField(vec2 uv) {
    return starLayer(uv,  50.0, 0.0, 0.90)
         + starLayer(uv, 100.0, 3.1, 0.94);
}

/*
 * 天空取第 band 条色带。四条带各占贴图 1/4 高度；先夹到带内首末行的纹素中心，
 * 线性过滤就不会跨带取样（边缘行为与原单条 24x64 贴图的 CLAMP_TO_EDGE 相同）。
 */
vec3 skyBand(float v, float band) {
    float local = clamp(v, 0.5 / 64.0, 63.5 / 64.0);
    return texture(uSkyTex, vec2(0.5, (band + local) * 0.25)).rgb;
}

void main() {
    // 天空只取 v，并且和树用同一个 vUv —— 顶点着色器把 UV 按水面波纹扭曲过，
    // 天空跟着一起扭，与两者烤在一张贴图里时观感一致。
    float m = texture(uMaskTex, vUv).r;
    // 权重每帧更新，日夜过渡因此是连续的
    vec3 sky = skyBand(vUv.y, 0.0) * dropBlock.uSkyWeights.x
             + skyBand(vUv.y, 1.0) * dropBlock.uSkyWeights.y
             + skyBand(vUv.y, 2.0) * dropBlock.uSkyWeights.z
             + skyBand(vUv.y, 3.0) * dropBlock.uSkyWeights.w;
    // 星星乘遮罩之前：树的倒影应当挡住它们
    if (pc.uStarAmount > 0.001) {
        sky += starField(vScreenUv) * pc.uStarAmount;
    }
    outColor = vec4(sky * m, pc.uAlpha);
}
