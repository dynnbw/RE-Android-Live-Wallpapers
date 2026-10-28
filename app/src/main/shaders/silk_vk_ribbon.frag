#version 450

/*
 * Silk 丝带的片元着色器，与 GLES 版 silk_fs.glsl 等价。
 * 原版按丝带拆开的 originColor / length / DIVISION_FACTOR / decay 常量，
 * 这里和 GLES 版一样提到推送常量里，三条丝带共用同一个 pass 定义。
 */

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(push_constant) uniform PushConstants {
    mat4 uClip;
    vec4 uRotateFlash;  // (sin(rot), cos(rot), flash, 未用)
    vec4 uNewPos;
    vec4 uOriginColor;  // rgb = 主题色，a = 1
    vec4 uParams;       // (originAlpha, decayLen, divFactor, decay)
} pc;

layout(location = 0) in float vFragmentColor;
layout(location = 1) in vec2 vCoord;
layout(location = 2) in vec2 vLocalPos;
layout(location = 3) in float vFlash;

layout(location = 0) out vec4 fragColor;

void main() {
    // 用 x*x 而不是 pow(x, 2.0)：pow 对负底数无定义，衰减带大部分是负的。
    float diff = (2.0 * vLocalPos.x - pc.uParams.y) * pc.uParams.z;
    float alpha = (pc.uParams.w < 0.5) ? 1.0 : 1.0 - diff * diff;
    alpha = clamp(alpha, 0.0, 1.0);

    // GLES 侧贴图是按预乘 alpha 解码后上传的，混合用 GL_ONE；VK 的 setTexture
    // 传的是直通 alpha 的像素，所以采样后先乘回 alpha，后面的公式才能逐字照抄。
    vec4 texel = texture(uTexture, vCoord);
    texel.rgb *= texel.a;

    fragColor = (vFragmentColor * texel + (1.0 - vFragmentColor) * pc.uOriginColor)
            * pc.uParams.x * alpha * vFlash;

    // NaN 防护，与 GLES 版相同：波谷处三角形退化，部分 GPU 会插值出 NaN，
    // 表现为黑块或过饱和闪烁。step() 对任何 NaN 比较都返回 0；
    // 必须整体替换而不是乘 0（IEEE 754 下 NaN * 0 仍是 NaN）。
    float safe = step(0.0, vFragmentColor) * step(vFragmentColor, 1.0);
    safe *= step(0.0, vCoord.x) * step(vCoord.x, 1.0);
    safe *= step(0.0, vCoord.y) * step(vCoord.y, 1.0);
    safe *= step(0.0, fragColor.r) * step(0.0, fragColor.g);
    safe *= step(0.0, fragColor.b) * step(0.0, fragColor.a);
    if (safe < 0.5) {
        fragColor = vec4(0.0);
    }
}
