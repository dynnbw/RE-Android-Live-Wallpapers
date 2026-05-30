package com.reandroid.wallpaper.walkaround;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.view.Surface;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.reandroid.gles.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

public class WalkAroundGL extends GLESScene {
    private final Context mContext;

    private int mProgram;
    private int mPosLoc;
    private int mTexLoc;
    private int mMvpLoc;
    private int mTexMatrixLoc;
    private int mSamplerLoc;

    private int mOesTexId;
    private SurfaceTexture mSurfaceTexture;
    private Camera mCamera;
    private int mCameraId = -1;
    private int mCameraRotation = 0;
    private int mPreviewWidth = 0;
    private int mPreviewHeight = 0;
    private int mDisplayRotation = Surface.ROTATION_0;

    private FloatBuffer mPosBuffer;
    private FloatBuffer mTexBuffer;

    private final float[] mMvp = new float[16];
    private final float[] mTexMatrix = new float[16];

    private boolean mUseFront = false;
    private boolean mMirrorFront = true;
    private boolean mHasPrefInit = false;

    public WalkAroundGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
        float[] quadPos = AssetLoader.readFloatArray(mContext, "walkaround/data/walkaround_quad_pos.csv");
        float[] quadTex = AssetLoader.readFloatArray(mContext, "walkaround/data/walkaround_quad_tex.csv");
        mPosBuffer = toFloatBuffer(quadPos);
        mTexBuffer = toFloatBuffer(quadTex);
        Matrix.setIdentityM(mMvp, 0);
        Matrix.setIdentityM(mTexMatrix, 0);
    }

    @Override
    protected void onCreate() {
        // no-op
    }

    @Override
    public void start() {
        ensureCamera();
    }

    @Override
    public void stop() {
        releaseCamera();
    }

    @Override
    public void release() {
        releaseCamera();
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        if (mOesTexId != 0) {
            int[] tex = new int[] { mOesTexId };
            GLES20.glDeleteTextures(1, tex, 0);
            mOesTexId = 0;
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        updateMvp();
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (mProgram == 0) return;

        updatePrefs();
        ensureCamera();

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport(0, 0, mWidth, mHeight);

        if (mSurfaceTexture == null) return;

        try {
            mSurfaceTexture.updateTexImage();
            mSurfaceTexture.getTransformMatrix(mTexMatrix);
        } catch (RuntimeException ignored) {
            return;
        }

        float[] tex = new float[16];
        buildTexMatrix(tex);

        GLES20.glUseProgram(mProgram);
        GLES20.glUniformMatrix4fv(mMvpLoc, 1, false, mMvp, 0);
        GLES20.glUniformMatrix4fv(mTexMatrixLoc, 1, false, tex, 0);
        GLES20.glUniform1i(mSamplerLoc, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mOesTexId);

        GLES20.glEnableVertexAttribArray(mPosLoc);
        GLES20.glEnableVertexAttribArray(mTexLoc);
        GLES20.glVertexAttribPointer(mPosLoc, 2, GLES20.GL_FLOAT, false, 0, mPosBuffer);
        GLES20.glVertexAttribPointer(mTexLoc, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPosLoc);
        GLES20.glDisableVertexAttribArray(mTexLoc);
    }

    private void initGLIfNeeded() {
        if (mProgram != 0) return;

        String vs = AssetLoader.readText(mContext, "walkaround/shaders/GLES/walkaround_vs.glsl");
        String fs = AssetLoader.readText(mContext, "walkaround/shaders/GLES/walkaround_fs.glsl");
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return;

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, v);
        GLES20.glAttachShader(mProgram, f);
        GLES20.glLinkProgram(mProgram);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
            return;
        }

        mPosLoc = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexLoc = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMvpLoc = GLES20.glGetUniformLocation(mProgram, "uMVP");
        mTexMatrixLoc = GLES20.glGetUniformLocation(mProgram, "uTexMatrix");
        mSamplerLoc = GLES20.glGetUniformLocation(mProgram, "uTex");

        mOesTexId = createOesTexture();
        if (mOesTexId != 0) {
            mSurfaceTexture = new SurfaceTexture(mOesTexId);
            if (Build.VERSION.SDK_INT >= 16) {
                mSurfaceTexture.setDefaultBufferSize(mWidth, mHeight);
            }
        }
    }

    private void updatePrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean useFront = prefs.getBoolean("walkaround_use_front", false);
        boolean mirrorFront = prefs.getBoolean("walkaround_mirror_front", true);
        if (!mHasPrefInit || useFront != mUseFront || mirrorFront != mMirrorFront) {
            mUseFront = useFront;
            mMirrorFront = mirrorFront;
            mHasPrefInit = true;
            restartCamera();
        }
    }

    private void ensureCamera() {
        if (mCamera != null) return;
        if (ContextCompat.checkSelfPermission(mContext, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (mSurfaceTexture == null) return;

        int id = findCameraId(mUseFront);
        if (id == -1) {
            id = findCameraId(!mUseFront);
        }
        if (id == -1) return;

        try {
            mCamera = Camera.open(id);
            mCameraId = id;

            Camera.Parameters params = mCamera.getParameters();
            mDisplayRotation = getDisplayRotation();
            float targetRatio = getTargetRatioForDisplay(mDisplayRotation, mWidth, mHeight);
            Camera.Size size = chooseBestSize(params.getSupportedPreviewSizes(), targetRatio);
            if (size != null) {
                params.setPreviewSize(size.width, size.height);
                mPreviewWidth = size.width;
                mPreviewHeight = size.height;
            }
            if (params.getSupportedFocusModes() != null
                    && params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            mCamera.setParameters(params);
            mCameraRotation = getCameraDisplayOrientation(id, mDisplayRotation);
            mCamera.setDisplayOrientation(mCameraRotation);
            mCamera.setPreviewTexture(mSurfaceTexture);
            if (Build.VERSION.SDK_INT >= 16 && mPreviewWidth > 0 && mPreviewHeight > 0) {
                mSurfaceTexture.setDefaultBufferSize(mPreviewWidth, mPreviewHeight);
            }
            mCamera.startPreview();
            updateMvp();
        } catch (RuntimeException | IOException e) {
            releaseCamera();
        }
    }

    private void restartCamera() {
        releaseCamera();
        ensureCamera();
    }

    private void releaseCamera() {
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
            } catch (RuntimeException ignored) {
            }
            mCamera.release();
            mCamera = null;
            mCameraId = -1;
            mCameraRotation = 0;
            mPreviewWidth = 0;
            mPreviewHeight = 0;
        }
    }

    private int findCameraId(boolean front) {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (front && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
            if (!front && info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return -1;
    }

    private int getDisplayRotation() {
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        return wm != null ? wm.getDefaultDisplay().getRotation() : Surface.ROTATION_0;
    }

    private int getCameraDisplayOrientation(int cameraId, int displayRotation) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int degrees = 0;
        if (displayRotation == Surface.ROTATION_90) degrees = 90;
        else if (displayRotation == Surface.ROTATION_180) degrees = 180;
        else if (displayRotation == Surface.ROTATION_270) degrees = 270;

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    private Camera.Size chooseBestSize(List<Camera.Size> sizes, float targetRatio) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        float bestDiff = Float.MAX_VALUE;
        int bestArea = 0;
        for (Camera.Size size : sizes) {
            float ratio = (float) size.width / (float) size.height;
            float diff = Math.abs(ratio - targetRatio);
            int area = size.width * size.height;
            if (diff < bestDiff || (Math.abs(diff - bestDiff) < 0.001f && area > bestArea)) {
                bestDiff = diff;
                best = size;
                bestArea = area;
            }
        }
        return best != null ? best : sizes.get(0);
    }

    private float getTargetRatioForDisplay(int displayRotation, int width, int height) {
        if (width <= 0 || height <= 0) return 1f;
        boolean portrait = displayRotation == Surface.ROTATION_0
                || displayRotation == Surface.ROTATION_180;
        return portrait ? (float) height / (float) width : (float) width / (float) height;
    }

    private void updateMvp() {
        if (mWidth <= 0 || mHeight <= 0) return;
        int pw = mPreviewWidth > 0 ? mPreviewWidth : mWidth;
        int ph = mPreviewHeight > 0 ? mPreviewHeight : mHeight;
        boolean swap = mCameraRotation == 90 || mCameraRotation == 270;
        float camAspect = swap ? (float) ph / (float) pw : (float) pw / (float) ph;
        float viewAspect = (float) mWidth / (float) mHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (viewAspect > camAspect) {
            scaleX = viewAspect / camAspect;
        } else if (viewAspect < camAspect) {
            scaleY = camAspect / viewAspect;
        }
        Matrix.setIdentityM(mMvp, 0);
        Matrix.scaleM(mMvp, 0, scaleX, scaleY, 1f);
    }

    private void buildTexMatrix(float[] out) {
        System.arraycopy(mTexMatrix, 0, out, 0, 16);

        // Use SurfaceTexture's matrix plus optional mirror; avoid double-rotating.

        if (mUseFront && mMirrorFront) {
            float[] mirror = new float[16];
            Matrix.setIdentityM(mirror, 0);
            Matrix.translateM(mirror, 0, 1f, 0f, 0f);
            Matrix.scaleM(mirror, 0, -1f, 1f, 1f);
            float[] temp = new float[16];
            Matrix.multiplyMM(temp, 0, mirror, 0, out, 0);
            System.arraycopy(temp, 0, out, 0, 16);
        }
    }

    private int createOesTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int id = tex[0];
        if (id == 0) return 0;
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return id;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private FloatBuffer toFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }
}
