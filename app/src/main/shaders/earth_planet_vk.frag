#version 450

/*
 * 球体层着色，对应 GLES 的 earth_planet_fs.glsl。
 *
 * 光源在世界空间：默认材质的 ambient=0.2 / diffuse=0.8，点光源 (0,3,10)，
 * 城市灯光不参与光照调制，晨昏线用 smoothstep 柔化。
 */

layout(set = 0, binding = 0) uniform sampler2D uDay;
layout(set = 0, binding = 1) uniform sampler2D uNight;

layout(push_constant) uniform PushConstants {
    mat4 uViewProj;
    float uAngleY;
    float uScale;
    float uTx;
    float uTz;
    float uLightX;
    float uLightY;
    float uLightZ;
    float uDeltaR;
    float uDeltaG;
    float uDeltaB;
    float uAmbient;
    float uNightGain;
    float uUseNight;
} pc;

layout(location = 0) in vec3 vWorldPos;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec2 vTexCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 n = normalize(vNormal);
    vec3 l = normalize(vec3(pc.uLightX, pc.uLightY, pc.uLightZ) - vWorldPos);
    float nl = dot(n, l);

    // 网格 UV 与标准等距圆柱贴图差 180° 经度，与 GLES 版一致地平移半个 U
    vec2 uv = vec2(fract(vTexCoord.x + 0.5), vTexCoord.y);

    vec4 dayTex = texture(uDay, uv);
    vec3 delta = vec3(pc.uDeltaR, pc.uDeltaG, pc.uDeltaB);
    vec3 dayCol = dayTex.rgb * (pc.uAmbient + (1.0 - pc.uAmbient) * max(nl, 0.0)) + delta;

    vec3 col = dayCol;
    if (pc.uUseNight > 0.5) {
        vec3 nightCol = texture(uNight, uv).rgb * pc.uNightGain;
        col = mix(nightCol, dayCol, smoothstep(-0.15, 0.25, nl));
    }
    fragColor = vec4(col, dayTex.a);
}
