package com.reandroid.wallpaper.musicvis;

/**
 * Minimal 4x4 column-major matrix math (replaces android.opengl.Matrix).
 * All static methods operate on float[16] in place.
 */
final class Mat4 {
    private Mat4() {}

    static void setIdentityM(float[] m) {
        for (int i = 0; i < 16; i++) m[i] = 0f;
        m[0] = m[5] = m[10] = m[15] = 1f;
    }

    /** Build an orthographic projection matrix into m (offset 0). */
    static void orthoM(float[] m, float left, float right,
                       float bottom, float top, float near, float far) {
        float rml = right - left;
        float tmb = top - bottom;
        float fmn = far - near;
        for (int i = 0; i < 16; i++) m[i] = 0f;
        m[0] = 2f / rml;
        m[5] = 2f / tmb;
        m[10] = -2f / fmn;
        m[12] = -(right + left) / rml;
        m[13] = -(top + bottom) / tmb;
        m[14] = -(far + near) / fmn;
        m[15] = 1f;
    }

    /** Build a frustum projection matrix into m (offset 0). */
    static void frustumM(float[] m, float left, float right,
                         float bottom, float top, float near, float far) {
        float rml = right - left;
        float tmb = top - bottom;
        float fmn = far - near;
        for (int i = 0; i < 16; i++) m[i] = 0f;
        m[0] = 2f * near / rml;
        m[5] = 2f * near / tmb;
        m[8] = (right + left) / rml;
        m[9] = (top + bottom) / tmb;
        m[10] = -(far + near) / fmn;
        m[11] = -1f;
        m[14] = -2f * far * near / fmn;
    }

    /** Right-multiply m by a translation matrix: m = m * T(x,y,z). */
    static void translateM(float[] m, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z;
        }
    }

    /** Right-multiply m by a scale matrix: m = m * S(x,y,z). */
    static void scaleM(float[] m, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            m[i]     *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
    }

    /** Right-multiply m by a rotation matrix: m = m * R(angle, axis). */
    static void rotateM(float[] m, float angle, float ax, float ay, float az) {
        float rad = (float) Math.toRadians(angle);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float t = 1f - c;

        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len == 0f) return;
        float nx = ax / len, ny = ay / len, nz = az / len;

        float[] r = new float[16];
        r[0]  = t * nx * nx + c;
        r[1]  = t * nx * ny + s * nz;
        r[2]  = t * nx * nz - s * ny;
        r[3]  = 0f;
        r[4]  = t * nx * ny - s * nz;
        r[5]  = t * ny * ny + c;
        r[6]  = t * ny * nz + s * nx;
        r[7]  = 0f;
        r[8]  = t * nx * nz + s * ny;
        r[9]  = t * ny * nz - s * nx;
        r[10] = t * nz * nz + c;
        r[11] = 0f;
        r[12] = r[13] = r[14] = 0f;
        r[15] = 1f;

        float[] tmp = new float[16];
        multiplyMM(tmp, m, r);
        System.arraycopy(tmp, 0, m, 0, 16);
    }

    /** Multiply two 4x4 matrices: result = a * b. */
    static void multiplyMM(float[] result, float[] a, float[] b) {
        float[] tmp = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int idx = j * 4 + i;
                tmp[idx] = 0f;
                for (int k = 0; k < 4; k++) {
                    tmp[idx] += a[k * 4 + i] * b[j * 4 + k];
                }
            }
        }
        System.arraycopy(tmp, 0, result, 0, 16);
    }

    /** Copy src into dst (both float[16]). */
    static void copy(float[] dst, float[] src) {
        System.arraycopy(src, 0, dst, 0, 16);
    }
}
