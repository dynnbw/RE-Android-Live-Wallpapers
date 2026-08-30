precision mediump float;

uniform vec3 u_PrimaryColor;
uniform vec3 u_SecondaryColor;

varying vec4 vPosition;
varying vec4 vDx;
varying vec4 vDy;

// ---------------------------------
// (原版声明了顶点 shader 未输出的 varying vColor,部分驱动链接报错,已移除)

uniform sampler2D sTexture;

// ---------------------------------

void main() {
    // This texture is used to weigh in a variance of the flow color (the
    // secondary color), indicated by alpha entries
    float col1 = texture2D(sTexture, vPosition.xy).a;

    // Substitute the alpha with a value proportional to the area to the two
    // neighbor positions, this will make the flow less dense where the
    // transformed grid is flatter
    float normalAlpha = (1.1 - (length(cross(vDx.xyz, vDy.xyz))));
    normalAlpha = clamp(normalAlpha, 0.0, 1.0);

    float alpha = normalAlpha * (vPosition.y + 0.2);
    alpha = pow(alpha, 2.3);

    gl_FragColor = vec4(u_PrimaryColor + col1 * u_SecondaryColor, alpha);
}
