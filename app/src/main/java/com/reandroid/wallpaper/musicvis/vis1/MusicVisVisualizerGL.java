package com.reandroid.wallpaper.musicvis.vis1;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES30;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * vis1（Visualizer）的 GL 渲染。场景逻辑全在 {@link VisualizerScene}。
 *
 * <p>原版是 Canvas 画的，这里换成 GL 但保持同一套像素数学：投影是**屏幕像素正交**
 * （y 向下），顶点直接就是像素坐标 —— 所以"中线 + 采样值 × 系数"这句话在两边长得一样。
 */
public class MusicVisVisualizerGL extends GLESScene {

    /** 原版 {@code paint.setColor(0xffffffff)} —— 纯白的线，没有贴图。 */
    private static final float[] LINE_COLOR = {1.0f, 1.0f, 1.0f, 1.0f};

    private final Context mContext;
    private final VisualizerScene mScene;

    private int mProgram;
    private int mPosLoc;
    private int mMvpLoc;
    private int mColorLoc;
    private FloatBuffer mVertexBuffer;

    private final float[] mProj = new float[16];

    public MusicVisVisualizerGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new VisualizerScene(width, height, context);
    }

    @Override
    protected void onCreate() {
    }

    /**
     * vis1 的 {@code previewClass} 指向本类，所以注入器只会找这个方法 —— 没有它，
     * {@link com.reandroid.plugin.PluginPrefsInjector} 会打一条日志放过。同
     * {@code MusicVisVuGL}。
     */
    public void setPluginPrefs(SharedPreferences p) {
        mScene.setPluginPrefs(p);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        // 场景要新尺寸：顶点是按屏宽铺满、按屏高定振幅算出来的
        mScene.resize(width, height);
        updateProjection();
    }

    @Override
    public void start() {
        mScene.start();
    }

    @Override
    public void stop() {
        mScene.stop();
    }

    @Override
    public void release() {
        mScene.release();
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();

        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);   // 原版 c.drawColor(0xff000000)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (mProgram == 0) {
            return;
        }

        mScene.updateTrace();
        final int floats = mScene.vertexFloats();
        if (floats <= 0) {
            return;
        }

        mVertexBuffer.clear();
        mVertexBuffer.put(mScene.vertices(), 0, floats);
        mVertexBuffer.position(0);

        GLES30.glUseProgram(mProgram);
        GLES30.glUniformMatrix4fv(mMvpLoc, 1, false, mProj, 0);
        GLES30.glUniform4fv(mColorLoc, 1, LINE_COLOR, 0);
        GLES30.glEnableVertexAttribArray(mPosLoc);
        GLES30.glVertexAttribPointer(mPosLoc, 2, GLES30.GL_FLOAT, false, 0, mVertexBuffer);
        // 带子：每个采样两个顶点（上下两条边），见 VisualizerScene.buildRibbon
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, floats / 2);
        GLES30.glDisableVertexAttribArray(mPosLoc);
    }

    private void initGLIfNeeded() {
        if (mProgram != 0 || mContext == null) {
            return;
        }
        String vs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_line_vs.glsl");
        String fs = AssetLoader.readText(mContext, "musicvis/shaders/GLES/musicvis_line_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) {
            return;
        }
        mPosLoc = GLES30.glGetAttribLocation(mProgram, "aPosition");
        mMvpLoc = GLES30.glGetUniformLocation(mProgram, "uMVP");
        mColorLoc = GLES30.glGetUniformLocation(mProgram, "uColor");

        mVertexBuffer = ByteBuffer
                .allocateDirect(VisualizerScene.CAPTURE_SIZE * 4 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        updateProjection();
    }

    private void updateProjection() {
        // 屏幕像素、y 向下（与 grass 的 orthoM(0,w,h,0) 同一个口径）
        Mat4.orthoM(mProj, 0.0f, mWidth, mHeight, 0.0f, -1.0f, 1.0f);
    }
}
