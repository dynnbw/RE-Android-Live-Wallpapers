package com.reandroid.wallpaper.nightsky;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GLES 星轨绘制：几何完全交给 {@link NightSkyTrailBuilder}（与 VK 共用），
 * 这里只负责把交错顶点上传和一次 {@code glDrawArrays}。
 */
final class NightSkyTrailRenderer {

    private int program;
    private int aPos = -1;
    private int aColor = -1;
    private FloatBuffer lineBuffer;
    private final NightSkyTrailBuilder builder = new NightSkyTrailBuilder();

    void init(int program) {
        this.program = program;
        if (program != 0) {
            aPos = GLES30.glGetAttribLocation(program, "aPos");
            aColor = GLES30.glGetAttribLocation(program, "aColor");
        } else {
            aPos = -1;
            aColor = -1;
        }
    }

    void draw(
            NightSkyCatalog catalog,
            float latRad,
            float lonDeg,
            long astronomyMs,
            long frameTimeMs,
            float[] viewRot,
            float[] proj,
            int viewportWidth,
            int viewportHeight,
            long trailLookbackSimMs,
            boolean accelerating) {
        if (program == 0) {
            return;
        }

        boolean changed = builder.update(
                catalog,
                latRad,
                lonDeg,
                astronomyMs,
                frameTimeMs,
                viewRot,
                proj,
                viewportWidth,
                viewportHeight,
                trailLookbackSimMs,
                accelerating);
        if (changed) {
            uploadBuffers();
        }

        if (builder.vertexCount <= 0 || lineBuffer == null) {
            return;
        }

        GLES30.glUseProgram(program);
        GLES30.glEnable(GLES30.GL_BLEND);
        // 星轨片元着色器输出预乘 RGB（rgb * a），所以用 ONE, ONE。
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);

        lineBuffer.position(0);
        GLES30.glEnableVertexAttribArray(aPos);
        GLES30.glVertexAttribPointer(
                aPos, 2, GLES30.GL_FLOAT, false, NightSkyTrailBuilder.STRIDE_BYTES, lineBuffer);
        lineBuffer.position(2);
        GLES30.glEnableVertexAttribArray(aColor);
        GLES30.glVertexAttribPointer(
                aColor, 4, GLES30.GL_FLOAT, false, NightSkyTrailBuilder.STRIDE_BYTES, lineBuffer);
        lineBuffer.position(0);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, builder.vertexCount);

        GLES30.glDisableVertexAttribArray(aPos);
        GLES30.glDisableVertexAttribArray(aColor);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void uploadBuffers() {
        int floats = builder.vertexCount * NightSkyTrailBuilder.FLOATS_PER_VERTEX;
        if (floats <= 0) {
            return;
        }
        if (lineBuffer == null || lineBuffer.capacity() < floats) {
            lineBuffer = ByteBuffer.allocateDirect(floats * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }

        lineBuffer.clear();
        lineBuffer.put(builder.data, 0, floats);
        lineBuffer.position(0);
    }
}
