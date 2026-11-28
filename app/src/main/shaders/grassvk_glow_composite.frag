#version 450

layout(set = 0, binding = 0) uniform sampler2D uScene;
layout(set = 0, binding = 1) uniform sampler2D uBloom;

layout(push_constant) uniform PushConstants {
    float uStrength;
    float uTone;       // 黄昏影调强度（0 = 不压）
    float uToneGamma;  // 曲线指数
    float _pad;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 scene = texture(uScene, vUv).rgb;
    vec3 bloom = texture(uBloom, vUv).rgb;
    vec3 composed = scene + bloom * pc.uStrength;

    /*
     * 黄昏影调：pow 型曲线，0 与 1 上都是恒等 —— 动的只有中间段。
     *
     * **大于 1 的部分原样留住**（场景缓冲是 RGBA16F，逆光的溢光本来就超过 1）：
     * 压它等于把逆光那道金擦掉；留住它，中间调沉下去之后它反而更突出。
     * 与 GLES 的 glow_composite_fs.glsl 同一套。
     */
    if (pc.uTone > 0.0) {
        vec3 flattened = clamp(composed, 0.0, 1.0);
        vec3 shaped = pow(flattened, vec3(pc.uToneGamma));
        shaped += max(composed - 1.0, 0.0);
        composed = mix(composed, shaped, pc.uTone);
    }

    // alpha 必须写 1：场景纹理带 alpha，照抄到屏幕会改变壁纸自身的合成方式
    outColor = vec4(composed, 1.0);
}
