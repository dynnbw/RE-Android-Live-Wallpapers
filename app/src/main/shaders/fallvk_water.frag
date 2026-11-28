#version 450

// binding 0: 河床的树遮罩（R8_UNORM，白=天空 黑=树）
// binding 1: 雨滴与天空权重（与顶点着色器同一块）
// binding 2: 天空色带（24x256，四条 24x64 竖直渐变竖排）
layout(set = 0, binding = 0) uniform sampler2D uMaskTex;
layout(set = 0, binding = 2) uniform sampler2D uSkyTex;
// binding 3: 蓝藻的团状噪声（R8，REPEAT）
layout(set = 0, binding = 3) uniform sampler2D uAlgaeNoise;

layout(set = 0, binding = 1) uniform DropBlock {
    vec4 u_drop[80];
    int u_dropCount;
    vec4 uSkyWeights;
    vec4 uAlgae[80];    // (强度, 世界x, 世界y, 0)
    float uAlgaeAmount;
    float uLeafAlgaeAmount;
    float uAlgaeMetricX;
    float uAlgaeMetricY;
    vec2 uAlgaeMeshScale;
    vec2 uAlgaeMeshOffset;
    float uAlgaeThreshold;
    float uAlgaeBand;
    float uAlgaeNoiseTile;
    float uAlgaeArcScale;
    float uAlgaeGain;
    vec4 uAlgaeLow;
    vec4 uAlgaeHigh;
    vec4 uLightColor;
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
layout(location = 2) in vec2 vMeshPos;
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
 * 夜里被搅动的水里，蓝藻发出的生物光。与 GLES 的 fall_water_fs.glsl 同一套算法：
 * 过阈值才闪、极短（光只贴在波前后面一段）、颗粒状（乘一层团状噪声）。
 */
const float ALGAE_ARC_LO = 0.42;
const float ALGAE_ARC_HI = 0.62;
const float ALGAE_LAYER_COARSE = 0.25;
const float ALGAE_LAYER_FINE = 1.15;
const float ALGAE_LAYER_ROT1 = 2.399963;
const float ALGAE_LAYER_ROT2 = 4.799926;
const float ALGAE_LAYER_FALLOFF = 2.5;

/** 一层噪声 → **预乘**的颜色（rgb 已经乘过 a）：两端最强、中灰处归零。 */
vec4 algaeLayer(float lum, float weight) {
    float a = abs(lum * 2.0 - 1.0) * weight;
    vec3 c = lum < 0.5 ? dropBlock.uAlgaeLow.rgb : dropBlock.uAlgaeHigh.rgb;
    return vec4(c * a, a);
}

mat2 rot2(float a) {
    float c = cos(a);
    float s = sin(a);
    return mat2(c, -s, s, c);
}

/** 藻的场：多层不同旋转的噪声叠出团状密度，返回预乘颜色与覆盖度。 */
vec4 algaeField(vec2 meshPos) {
    vec2 p = meshPos / max(dropBlock.uAlgaeNoiseTile, 1e-4);
    vec4 acc = algaeLayer(texture(uAlgaeNoise, p * ALGAE_LAYER_COARSE).r, 1.0);
    acc += algaeLayer(texture(uAlgaeNoise,
            rot2(ALGAE_LAYER_ROT1) * p + vec2(0.31, 0.77)).r, 1.0 / ALGAE_LAYER_FALLOFF);
    acc += algaeLayer(texture(uAlgaeNoise,
            rot2(ALGAE_LAYER_ROT2) * (p * ALGAE_LAYER_FINE) + vec2(0.63, 0.19)).r,
            1.0 / (ALGAE_LAYER_FALLOFF * ALGAE_LAYER_FALLOFF));
    return min(acc, vec4(1.0));
}

/** 这一圈上哪几段在闪（0..1）。按**方向**采样，断口由波纹序号散列而来。 */
float algaeArc(int index, vec2 delta, float dist) {
    vec2 dir = delta / max(dist, 1e-4);
    vec2 seed = fract(vec2(float(index) * 0.7548, float(index) * 0.5693));
    return smoothstep(ALGAE_ARC_LO, ALGAE_ARC_HI,
            texture(uAlgaeNoise, dir * dropBlock.uAlgaeArcScale + seed).r);
}

vec3 algaeGlow(vec2 meshPos) {
    vec4 algae = algaeField(meshPos);
    if (algae.a <= 0.004) return vec3(0.0);

    // 波前的不完美：粗尺度噪声把半径推出去/收回来一点
    float wobble = texture(uAlgaeNoise, meshPos * 0.04 + vec2(7.3, 1.9)).r;
    float wobbleScale = 0.90 + 0.20 * wobble;

    float acc = 0.0;
    for (int i = 0; i < 80; i++) {
        if (i >= dropBlock.u_dropCount) break;
        float power = dropBlock.uAlgae[i].x;
        if (power < dropBlock.uAlgaeThreshold) continue;
        vec4 d = dropBlock.u_drop[i];
        // 与顶点着色器的 addDrop() 同一套距离度量（X 方向同样乘 dxMul）
        vec2 delta = vec2((meshPos.x - d.x) * pc.u_dxMul, meshPos.y - d.y);
        float dist = length(delta);
        if (dist > d.w * wobbleScale + dropBlock.uAlgaeBand * 0.30) continue;

        float arcGate = algaeArc(i, delta, dist);
        if (arcGate <= 0.004) continue;

        // 闪光带：峰值靠外沿（刚被搅动的地方最亮），两头归零
        float t = clamp((dist - (d.w * wobbleScale - dropBlock.uAlgaeBand))
                / (dropBlock.uAlgaeBand * 1.30), 0.0, 1.0);
        float band = smoothstep(0.0, 0.75, t) * (1.0 - smoothstep(0.75, 1.0, t));
        acc += band * smoothstep(dropBlock.uAlgaeThreshold, 1.0, power) * arcGate;
    }
    return algae.rgb * (acc / (1.0 + acc));
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

    vec3 color = sky * m;
    // 蓝藻是水面自己发出的光，在倒影**前面** —— 所以加在乘遮罩之后，树挡不住它
    if (dropBlock.uAlgaeAmount > 0.001) {
        color += algaeGlow(vMeshPos) * (dropBlock.uAlgaeGain * dropBlock.uAlgaeAmount);
    }
    outColor = vec4(color, pc.uAlpha);
}
