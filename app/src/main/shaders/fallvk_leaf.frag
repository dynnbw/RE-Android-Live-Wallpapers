#version 450

layout(set = 0, binding = 0) uniform sampler2D uLeafTex;
// binding 1: 与水面共用的 UBO（蓝藻的波纹、噪声参数）
// binding 2: 蓝藻的团状噪声（R8，REPEAT）
layout(set = 0, binding = 2) uniform sampler2D uAlgaeNoise;

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
    vec4 uEmitter;       // (屏幕 x, y, 横向半宽, 纵向半宽)
    vec4 uEmitterColor;  // rgb + 增益
} dropBlock;

layout(push_constant) uniform PushConstants {
    mat4 uMvpMatrix;
    float uAlpha;
    float uLeafFrameIndex;
    float uLeafFrameInvCount;
    float uShadow;   // 1 = 影子（剪影），0 = 正常叶片
    // 与顶点着色器同布局：叶片中心与两个半轴（水面那边这六个槽是涟漪参数）
    vec2 uLeafCenter;
    vec2 uLeafAxisX;
    vec2 uLeafAxisY;
    float _unused;   // 水面那边的 u_rotate 槽
    // 与水面共用最后 5 个 float：叶子这边是日夜染色的 rgb / amount / value
    float uTintR;
    float uTintG;
    float uTintB;
    float uTintAmount;
    float uTintValue;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 1) in vec2 vWorld;
layout(location = 0) out vec4 outColor;

/*
 * 逐像素、逐波纹的蓝藻受光。与 GLES 的 fall_fs.glsl 同一套算法，也与水面的
 * algaeGlow() 逐条对应 —— 两边一旦走样，叶子上的光就和水里那圈错位。
 */
const float ALGAE_ARC_LO = 0.42;
const float ALGAE_ARC_HI = 0.62;
const float ALGAE_LAYER_COARSE = 0.25;
const float ALGAE_LAYER_FINE = 1.15;
const float ALGAE_LAYER_ROT1 = 2.399963;
const float ALGAE_LAYER_ROT2 = 4.799926;
const float ALGAE_LAYER_FALLOFF = 2.5;

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

/**
 * 返回这片叶子受到的**光强**（0..1），不是颜色 —— 颜色由 uLightColor 给。
 *
 * <p>世界坐标先换成与水面对齐的网格坐标，两边采到同一个噪声，环的形状与颗粒才对得上。
 */
float algaeGlowOnLeaf(vec2 world) {
    vec2 meshPos = world * dropBlock.uAlgaeMeshScale + dropBlock.uAlgaeMeshOffset;

    float density = algaeField(meshPos).a;
    if (density <= 0.004) return 0.0;

    float wobble = texture(uAlgaeNoise, meshPos * 0.04 + vec2(7.3, 1.9)).r;
    float wobbleScale = 0.90 + 0.20 * wobble;

    float acc = 0.0;
    for (int i = 0; i < 80; i++) {
        if (i >= dropBlock.u_dropCount) break;
        float power = dropBlock.uAlgae[i].x;
        if (power < dropBlock.uAlgaeThreshold) continue;      // 没惊动这片水
        vec4 d4 = dropBlock.u_drop[i];
        vec2 delta = (world - dropBlock.uAlgae[i].yz)
                * vec2(dropBlock.uAlgaeMetricX, dropBlock.uAlgaeMetricY);
        float dist = length(delta);
        if (dist > d4.w * wobbleScale + dropBlock.uAlgaeBand * 0.30) continue;

        // 亮弧：与水面同一套，散列偏移也用**波纹序号**（两边位置坐标不同）
        vec2 dir = delta / max(dist, 1e-4);
        vec2 seed = fract(vec2(float(i) * 0.7548, float(i) * 0.5693));
        float arcGate = smoothstep(ALGAE_ARC_LO, ALGAE_ARC_HI,
                texture(uAlgaeNoise, dir * dropBlock.uAlgaeArcScale + seed).r);
        if (arcGate <= 0.004) continue;

        // 与水面同一条带子：峰值靠外沿，两头归零
        float t = clamp((dist - (d4.w * wobbleScale - dropBlock.uAlgaeBand))
                / (dropBlock.uAlgaeBand * 1.30), 0.0, 1.0);
        float band = smoothstep(0.0, 0.75, t) * (1.0 - smoothstep(0.75, 1.0, t));
        acc += band * smoothstep(dropBlock.uAlgaeThreshold, 1.0, power) * arcGate;
    }
    return acc / (1.0 + acc);
}

void main() {
    vec4 texel = texture(uLeafTex, vUv);
    // 与 GLES 一致：往目标色混（不是乘），再单独压明度
    vec3 rgb = mix(texel.rgb, vec3(pc.uTintR, pc.uTintG, pc.uTintB), pc.uTintAmount)
            * pc.uTintValue;

    // 蓝藻打在叶片上：**乘**在本色上，亮部提亮、暗部保持暗，才读作"被照亮"。影子不发光。
    if (pc.uShadow < 0.5 && dropBlock.uLeafAlgaeAmount > 0.001) {
        rgb *= vec3(1.0) + dropBlock.uLightColor.rgb * algaeGlowOnLeaf(vWorld);
    }

    // 影子只取叶形，颜色压成黑色，靠 alpha 混合把地面压暗
    rgb = mix(rgb, vec3(0.0), pc.uShadow);
    outColor = vec4(rgb, texel.a * pc.uAlpha);
}
