package com.reandroid.wallpaper.weatherwallpapers;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.FloatBuffer;

public class SpriteDrawer {
    private final float[] mModelMatrix = new float[16];
    private final float[] mMVPMatrix = new float[16];

    private float[] mProjectionMatrix;

    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mColorHandle;
    private int mSamplerHandle;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexBuffer;
    private FloatBuffer mRectOneToTwoBuffer;
    private FloatBuffer mRectOneToFourBuffer;

    public void configure(int program,
                   int positionHandle,
                   int texCoordHandle,
                   int matrixHandle,
                   int colorHandle,
                   int samplerHandle,
                   float[] projectionMatrix,
                   FloatBuffer vertexBuffer,
                   FloatBuffer texBuffer,
                   FloatBuffer rectOneToTwoBuffer,
                   FloatBuffer rectOneToFourBuffer) {
        mProgram = program;
        mPositionHandle = positionHandle;
        mTexCoordHandle = texCoordHandle;
        mMatrixHandle = matrixHandle;
        mColorHandle = colorHandle;
        mSamplerHandle = samplerHandle;

        mProjectionMatrix = projectionMatrix;
        mVertexBuffer = vertexBuffer;
        mTexBuffer = texBuffer;
        mRectOneToTwoBuffer = rectOneToTwoBuffer;
        mRectOneToFourBuffer = rectOneToFourBuffer;
    }

    public void drawSprite(int texture, float x, float y, float z,
                    float scaleX, float scaleY, float rotation, float alpha) {
        drawSpriteColored(texture, x, y, z, scaleX, scaleY, rotation, alpha, alpha, alpha, alpha);
    }

    public void drawSpriteRectOneToTwo(int texture, float x, float y, float z,
                                float scaleX, float scaleY, float rotation, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                alpha, alpha, alpha, alpha, mRectOneToTwoBuffer);
    }

    public void drawSpriteColoredRectOneToTwo(int texture, float x, float y, float z,
                                       float scaleX, float scaleY, float rotation,
                                       float r, float g, float b, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                r, g, b, alpha, mRectOneToTwoBuffer);
    }

    public void drawSpriteRectOneToFour(int texture, float x, float y, float z,
                                 float scaleX, float scaleY, float rotation, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                alpha, alpha, alpha, alpha, mRectOneToFourBuffer);
    }

    public void drawSpriteColored(int texture, float x, float y, float z,
                           float scaleX, float scaleY, float rotation,
                           float r, float g, float b, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                r, g, b, alpha, mVertexBuffer);
    }

    public void drawSpriteColoredWithBuffer(int texture, float x, float y, float z,
                                     float scaleX, float scaleY, float rotation,
                                     float r, float g, float b, float alpha,
                                     FloatBuffer vertexBuffer) {
        if (texture == 0 || mProjectionMatrix == null || mTexBuffer == null || vertexBuffer == null) {
            return;
        }

        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, x, y, z);
        Matrix.rotateM(mModelMatrix, 0, rotation, 0f, 0f, 1f);
        Matrix.scaleM(mModelMatrix, 0, scaleX, scaleY, 1.0f);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);

        GLES20.glUseProgram(mProgram);

        vertexBuffer.position(0);
        mTexBuffer.position(0);

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);

        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniform4f(mColorHandle, r, g, b, alpha);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }
}