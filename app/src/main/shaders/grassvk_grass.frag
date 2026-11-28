#version 450

layout(binding = 0) uniform sampler2D uAATexture;

/*
 * 草叶光照的每帧参数（binding 1）。布局见 grassvk_jni.cpp 的 GrassLightUbo。
 *
 * <p>与 GLES 的 grass_grass_fs.glsl 是同一套模型：萤火虫照明照出本色、逆光按
 * 高度/横截面法线做透射与迎光边、不受光处压暗并偏冷。常量在两个渲染器同源
 * （GrassConstants），这里只负责算。
 */
layout(set = 0, binding = 1) uniform GrassLightBlock {
    vec4 uFirefly[16];       // xyz = (x, y, 亮度)
    vec4 uTintAndBoost;      // rgb = 萤火虫光色，w = 本色还原增益
    vec4 uFireflyParams;     // x = 增益，y = 半径
    vec4 uLightParams;       // x = 总强度，y = 高光门控，z = 横截面张角，w = 叶根系数
    vec4 uLightParams2;      // x = 叶尖系数，y = 迎光边增益，z = 阴影增益
    vec4 uTransmit;          // rgb = 透过的光色
    vec4 uCool;              // rgb = 冷影
    vec4 uRimColor;          // rgb = 迎光边颜色
} uLighting;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vTexCoord;
layout(location = 2) in vec2 vPos;
layout(location = 3) in float vFireflyShadow;
layout(location = 0) out vec4 fragColor;

/**
 * 各光源在 p 点处的叠加照度。
 *
 * <p>不用开方：{@code w = 1 - d²/r²}。再平方一次是为了让半径处的一阶导也是 0 ——
 * 只取到 {@code 1-d²/r²} 的话圆边上还剩一个折角，画面上就是一个看得见的圈。
 */
float fireflyLight(vec2 p) {
    float radius = max(uLighting.uFireflyParams.y, 1.0);
    float r2 = radius * radius;
    float invR2 = 1.0 / r2;
    float sum = 0.0;
    for (int i = 0; i < 16; i++) {
        vec4 f = uLighting.uFirefly[i];
        if (f.z <= 0.0) continue;          // 空槽位
        vec2 d = p - f.xy;
        float d2 = dot(d, d);
        if (d2 >= r2) continue;
        float w = 1.0 - d2 * invR2;
        sum += w * w * f.z;
    }
    return sum;
}

void main() {
    // AA texture encodes edge softness in the R channel (uploaded as RGBA with R=alpha)
    float a = texture(uAATexture, vTexCoord).r;
    // 与 GLES 那条同步：vColor.a 装叶尖位置，不能乘进输出 alpha
    vec3 color = vColor.rgb;

    // ---- 萤火虫照亮草叶 ----
    // 挂在特效开关下；关掉时增益是 0，这一段是恒等。
    // **照出本色**，不是往底色上加光：夜里底色被压得很暗，加光只会剩下灯自己的颜色。
    float fireflyGain = uLighting.uFireflyParams.x;
    if (fireflyGain > 0.0) {
        color += vColor.rgb * uLighting.uTintAndBoost.w
                * uLighting.uTintAndBoost.rgb * fireflyGain
                * fireflyLight(vPos) * vFireflyShadow;
    }

    // 逆光总强度同时是总开关：提前返回保证"关掉时逐像素不变"
    float light = uLighting.uLightParams.x;
    if (light <= 0.0) {
        fragColor = vec4(color, a);
        return;
    }

    float u     = vTexCoord.x;       // 横截面位置：0/1 是叶片两条边
    float tip   = vColor.a;          // 0 叶根 → 1 叶尖，同时是厚度代理
    float beam  = vTexCoord.y;       // 带符号的逐叶受光

    float m = abs(beam);             // 强弱
    float s = sign(beam);            // 光在叶片的哪一侧

    // 叶片横截面是个浅弧，法线沿宽度扫过 —— 解析法线，不需要法线贴图
    float theta = (u - 0.5) * uLighting.uLightParams.z;
    float edge  = sin(theta) * s;    // 正 = 迎光侧
    // 轮廓要用 alpha 本身：它才是 AA 贴图淡出的那圈
    float rim   = 4.0 * a * (1.0 - a);

    // ① 高度明暗：根厚而暗、尖薄而亮
    float height = mix(uLighting.uLightParams.w, uLighting.uLightParams2.x, tip);
    color *= mix(1.0, height, light);

    // ② 透射：往"光的颜色"插值（不是乘），薄处透得多
    float transmit = sqrt(m) * tip * mix(0.85, 1.10, max(-edge, 0.0))
            * uLighting.uLightParams.y;
    vec3 transmitted = uLighting.uTransmit.rgb * mix(0.90, 1.15, tip);
    color = mix(color, transmitted, transmit);

    // ③ 迎光边：只在迎光那一侧，且随受光缩放
    color += uLighting.uRimColor.rgb * rim * m * max(edge, 0.0)
            * uLighting.uLightParams2.y * uLighting.uLightParams.y;

    // ④ 暖光冷影：不受光处压暗并偏冷，阴影同样跟着总强度缩放
    color = mix(color, color * uLighting.uCool.rgb,
            (1.0 - m) * uLighting.uLightParams2.z * light);

    fragColor = vec4(color, a);
}
