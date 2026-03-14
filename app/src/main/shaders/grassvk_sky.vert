#version 450

layout(location = 0) out vec2 vUv;

void main() {
    // Full-screen triangle (no vertex buffer needed)
    const vec2 positions[3] = vec2[](
        vec2(-1.0, -1.0),
        vec2( 3.0, -1.0),
        vec2(-1.0,  3.0)
    );
    vec2 pos = positions[gl_VertexIndex];
    // UV: map from [-1,3] to [0,2], clamp happens naturally in fragment
    vUv = pos * 0.5 + 0.5;
    gl_Position = vec4(pos, 0.0, 1.0);
}
