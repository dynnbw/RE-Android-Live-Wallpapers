package com.reandroid.wallpaper.polarclock;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.text.format.Time;
import android.util.Log;

import com.reandroid.plugin.VkPass;
import com.reandroid.plugin.VkPassFrame;
import com.reandroid.plugin.VkPassRendererDelegate;
import com.reandroid.plugin.VkPassScene;
import com.reandroid.utils.Mat4;
import com.reandroid.wallpaper.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.TimeZone;

/**
 * 极坐标时钟的 VK 渲染器 —— 壁纸与预览共用。
 *
 * <p>这个壁纸没有文字也没有贴图，每一笔都是几何：每道环是一条内外两圈的三角形条带，
 * 未闭合时的两个端点是三角形扇（圆帽）。GLES 版对每道环做三次 draw（条带 + 两个圆帽），
 * 顺序与这里声明的 pass 完全一致：环 i 的条带、起点帽、终点帽，然后才是环 i+1。
 *
 * <p>顶点与 GLES 版逐字相同（模型空间的像素坐标），位置变换全部交给推送常量里的
 * uMvp：正交投影、平移到表盘中心、旋转 -90°、横屏时 0.9 缩放。矩阵在 GL 裁剪空间
 * 基础上左乘 GL → Vulkan 的修正（y 取反、z 从 [-w, w] 映射到 [0, w]），所以端点圆帽
 * 的朝向与环的起始方向都与 GLES 一致。
 *
 * <p>颜色与 GLES 一样每个环每帧向调色板查询一次，放在推送常量里；混合因子取
 * {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}，颜色与 alpha 两个通道都按这个来 ——
 * 与 GLES 的 {@code glBlendFunc} 相同。
 *
 * <p>时间的取法与 GLES 版一致：{@code android.text.format.Time} 用
 * {@code System.currentTimeMillis()}，通用 VK 基类传进来的 uptimeMillis 不能直接喂。
 */
final class PolarClockVkDelegate extends VkPassRendererDelegate {

    private static final String TAG = "PolarClockVK";

    /** 与 PolarClockGL 相同的端点圆帽分段数。 */
    private static final int CAP_SEGMENTS = 16;

    /** 与 PolarClockScene.MAX_RINGS 一致；场景对外只暴露 ringCount()/ringAt()。 */
    private static final int MAX_RINGS = 5;

    /**
     * 条带顶点上限：GLES 的分段数是 {@code max(2, (int)(360 * sweep))}，
     * sweep 被夹到 1，所以最多 360 段、722 个顶点。
     */
    private static final int MAX_ARC_VERTICES = (360 + 1) * 2;

    /** 圆帽：中心顶点 + CAP_SEGMENTS + 1 个圆周顶点。 */
    private static final int CAP_VERTICES = CAP_SEGMENTS + 2;

    /** 每个顶点只有位置 (x, y)。 */
    private static final int FLOATS_PER_VERTEX = 2;

    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** mat4 + vec4 颜色，80 字节。 */
    private static final int PUSH_FLOATS = 16 + 4;

    private static final int PUSH_BYTES = PUSH_FLOATS * 4;

    private static final String VERT_SHADER = "shaders/polarclock_vk.vert.spv";
    private static final String FRAG_SHADER = "shaders/polarclock_vk.frag.spv";

    /** 场景与调色板会被主线程（设置变更）和渲染线程同时读写。 */
    private final Object mLock = new Object();

    private SharedPreferences mPrefs;
    private PolarClockScene mScene;
    private VkPassScene mPassScene;
    private ClockPalette mPalette;
    private final HashMap<String, ClockPalette> mPalettes = new HashMap<>();
    private Time mCalendar;

    private final VkPass[] mArcPasses = new VkPass[MAX_RINGS];
    private final VkPass[] mStartCapPasses = new VkPass[MAX_RINGS];
    private final VkPass[] mEndCapPasses = new VkPass[MAX_RINGS];

    private final String[] mArcBufferNames = new String[MAX_RINGS];
    private final String[] mStartCapBufferNames = new String[MAX_RINGS];
    private final String[] mEndCapBufferNames = new String[MAX_RINGS];

    private final ByteBuffer[] mArcBuffers = new ByteBuffer[MAX_RINGS];
    private final ByteBuffer[] mStartCapBuffers = new ByteBuffer[MAX_RINGS];
    private final ByteBuffer[] mEndCapBuffers = new ByteBuffer[MAX_RINGS];

    private final int[] mArcVertexCounts = new int[MAX_RINGS];

    /*
     * 几何缓存：半径/厚度/角度变了才重算顶点，其余帧直接上传上一份。
     * 秒环的角度每帧都变，所以它实际上每帧都会重算；其余环按秒/分推进。
     */
    private final boolean[] mGeometryValid = new boolean[MAX_RINGS];
    private final int[] mLastRingId = new int[MAX_RINGS];
    private final int[] mLastRadiusBits = new int[MAX_RINGS];
    private final int[] mLastThicknessBits = new int[MAX_RINGS];
    private final int[] mLastAngleBits = new int[MAX_RINGS];

    private final float[] mPush = new float[PUSH_FLOATS];
    private final float[] mColor = new float[4];
    private final float[] mProj = new float[16];
    private final float[] mModel = new float[16];
    private final float[] mGlMvp = new float[16];
    private final float[] mVkMvp = new float[16];

    private int mWidth;
    private int mHeight;

    /** 表盘中心：{@code 宽 * (1 - 偏移)}，0.5 为居中（与 GLES 的 mOffsetX 初值一致）。 */
    private float mOffsetX = 0.5f;

    private boolean mPreview;

    /** 桌面是否随屏滚动；拿不到页间距前按"会滚动"处理（与 GLES 的默认值一致）。 */
    private boolean mLauncherScrolls = true;

    PolarClockVkDelegate(Context context, SharedPreferences prefs) {
        super(context);
        mPrefs = prefs;
    }

    @Override
    public String logTag() {
        return TAG;
    }

    @Override
    public Object getScene() {
        return mScene;
    }

    @Override
    public boolean isReady() {
        return mScene != null;
    }

    @Override
    public void ensureScene(int width, int height) {
        if (width <= 0 || height <= 0) return;
        synchronized (mLock) {
            mWidth = width;
            mHeight = height;
            if (mScene != null) return;
            loadPalettes();
            mScene = new PolarClockScene();
            applyPrefsLocked();
            // 与 PolarClockGL.onCreate 相同：Time 绑定当前时区。
            mCalendar = new Time(TimeZone.getDefault().getID());
            mCalendar.setToNow();
        }
    }

    @Override
    public void onPluginPrefsChanged(SharedPreferences prefs) {
        // 场景可能还没建（surface 未就绪），先记下来，等 ensureScene 再注入。
        mPrefs = prefs;
        synchronized (mLock) {
            if (mScene == null) return;
            applyPrefsLocked();
            applyClearColorLocked();
        }
    }

    @Override
    public void onSceneOffset(float xOffset) {
        synchronized (mLock) {
            // 与 GLES 的 setOffset 一致：桌面不滚动时 0 表示"停在首页"，应居中。
            mOffsetX = PolarClockScene.effectiveOffsetX(mLauncherScrolls, xOffset);
        }
    }

    @Override
    public void onScrollStep(float xStep) {
        synchronized (mLock) {
            mLauncherScrolls = xStep > 0f;
        }
    }

    @Override
    public void onPreviewChanged(boolean preview) {
        synchronized (mLock) {
            mPreview = preview;
        }
    }

    /**
     * native 销毁后句柄就是野指针（{@code asRenderer} 是裸 cast），
     * 所以要在基类动手之前把场景引用清掉，避免设置回调拿旧句柄去调用。
     */
    @Override
    public void destroyRenderer() {
        synchronized (mLock) {
            mPassScene = null;
        }
        super.destroyRenderer();
    }

    @Override
    protected void buildScene(VkPassScene scene) {
        mPassScene = scene;
        synchronized (mLock) {
            if (mPalette != null) {
                applyClearColorLocked();
            } else {
                scene.setClearColor(0f, 0f, 0f, 1f);
            }
        }

        for (int i = 0; i < MAX_RINGS; i++) {
            mArcBufferNames[i] = "arc" + i;
            mStartCapBufferNames[i] = "capStart" + i;
            mEndCapBufferNames[i] = "capEnd" + i;

            scene.createBuffer(mArcBufferNames[i], STRIDE_BYTES, MAX_ARC_VERTICES, true);
            scene.createBuffer(mStartCapBufferNames[i], STRIDE_BYTES, CAP_VERTICES, true);
            scene.createBuffer(mEndCapBufferNames[i], STRIDE_BYTES, CAP_VERTICES, true);

            mArcBuffers[i] = allocateBuffer(MAX_ARC_VERTICES);
            mStartCapBuffers[i] = allocateBuffer(CAP_VERTICES);
            mEndCapBuffers[i] = allocateBuffer(CAP_VERTICES);
            mArcVertexCounts[i] = 0;
            mGeometryValid[i] = false;

            // 声明顺序就是绘制顺序：条带 → 起点帽 → 终点帽，与 GLES 的 drawRingArc 一致。
            mArcPasses[i] = addPass(scene, mArcBufferNames[i], VkPass.TOPOLOGY_TRIANGLE_STRIP);
            mStartCapPasses[i] =
                    addPass(scene, mStartCapBufferNames[i], VkPass.TOPOLOGY_TRIANGLE_FAN);
            mEndCapPasses[i] = addPass(scene, mEndCapBufferNames[i], VkPass.TOPOLOGY_TRIANGLE_FAN);
        }
    }

    /**
     * 一个纯色 pass。几何都来自顶点缓冲，混合与 GLES 的
     * {@code glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)} 一致 —— 颜色和 alpha 两路
     * 都用 SRC_ALPHA，通用 BLEND_ALPHA 预设的 alpha 路给的是 ONE，所以要显式覆盖。
     */
    private static VkPass addPass(VkPassScene scene, String bufferName, int topology) {
        VkPass pass = new VkPass(
                        VERT_SHADER,
                        FRAG_SHADER,
                        new int[] {2},
                        STRIDE_BYTES,
                        topology,
                        VkPass.BLEND_ALPHA)
                .buffer(bufferName, 0)
                .pushConstants(PUSH_BYTES)
                .blendFactors(
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA,
                        VkPass.FACTOR_SRC_ALPHA,
                        VkPass.FACTOR_ONE_MINUS_SRC_ALPHA);
        scene.add(pass);
        return pass;
    }

    @Override
    protected void updateFrame(VkPassFrame frame, long timeMs) {
        synchronized (mLock) {
            PolarClockScene scene = mScene;
            if (scene == null || mPalette == null || mWidth <= 0 || mHeight <= 0) {
                zeroAllPasses(frame);
                return;
            }

            /*
             * 与 GLES 壁纸/预览同一条时间线：两边都用 System.currentTimeMillis()。
             * 通用 VK 基类传进来的 timeMs 是 uptimeMillis，混用会让秒环的相位偏掉。
             */
            long now = System.currentTimeMillis();
            if (mCalendar != null) {
                mCalendar.set(now);
                mCalendar.normalize(false);
            }
            if (mCalendar != null) fillDateFields(scene);
            scene.layout(mWidth, mHeight, now);

            buildMvp();
            System.arraycopy(mVkMvp, 0, mPush, 0, 16);
            applyClearColorLocked();

            int ringCount = scene.ringCount();
            for (int i = 0; i < MAX_RINGS; i++) {
                if (i < ringCount) {
                    drawRing(frame, i, scene.ringAt(i));
                } else {
                    zeroRing(frame, i);
                }
            }
        }
    }

    /** 日期字段由 GL 侧从 Time 取出后交给场景，与 PolarClockGL.drawFrame 的填法相同。 */
    private void fillDateFields(PolarClockScene scene) {
        PolarClockScene.DateFields d = scene.dateFields();
        d.second = mCalendar.second;
        d.minute = mCalendar.minute;
        d.hour = mCalendar.hour;
        d.monthDay = mCalendar.monthDay;
        d.maxMonthDay = mCalendar.getActualMaximum(Time.MONTH_DAY);
        d.month = mCalendar.month;
    }

    private void drawRing(VkPassFrame frame, int index, PolarClockScene.Ring ring) {
        // GLES 的入口判断是 angle <= 0 才跳过；NaN 会继续走（原版就这样），这里照抄。
        if (ring.angle <= 0.0f) {
            zeroRing(frame, index);
            return;
        }

        float sweep = Math.min(1.0f, ring.angle);
        if (geometryChanged(index, ring)) {
            rebuildGeometry(index, ring, sweep);
        }

        frame.upload(
                mArcBufferNames[index], mArcBuffers[index], mArcVertexCounts[index] * STRIDE_BYTES);
        frame.setVertexCount(mArcPasses[index], mArcVertexCounts[index]);

        // 未闭合（<0.999 圈）才画两端圆帽，与 PolarClockGL.drawRingArc 的条件一致。
        boolean caps = sweep < 0.999f;
        if (caps) {
            frame.upload(
                    mStartCapBufferNames[index],
                    mStartCapBuffers[index],
                    CAP_VERTICES * STRIDE_BYTES);
            frame.upload(
                    mEndCapBufferNames[index], mEndCapBuffers[index], CAP_VERTICES * STRIDE_BYTES);
        }
        frame.setVertexCount(mStartCapPasses[index], caps ? CAP_VERTICES : 0);
        frame.setVertexCount(mEndCapPasses[index], caps ? CAP_VERTICES : 0);

        // 颜色每个环每帧查询一次（循环调色板随角度变化），同一个颜色喂给条带与两个圆帽。
        colorToRgba(colorForRing(ring), mColor);
        System.arraycopy(mColor, 0, mPush, 16, 4);
        frame.pushConstants(mArcPasses[index], mPush);
        if (caps) {
            frame.pushConstants(mStartCapPasses[index], mPush);
            frame.pushConstants(mEndCapPasses[index], mPush);
        }
    }

    /** 环 → 颜色；与 PolarClockGL.colorForRing 逐项对应。 */
    private int colorForRing(PolarClockScene.Ring ring) {
        switch (ring.id) {
            case PolarClockScene.RING_MINUTES:
                return mPalette.getMinuteColor(ring.angle);
            case PolarClockScene.RING_HOURS:
                return mPalette.getHourColor(ring.angle);
            case PolarClockScene.RING_DAYS:
                return mPalette.getDayColor(ring.angle);
            case PolarClockScene.RING_MONTHS:
                return mPalette.getMonthColor(ring.angle);
            default:
                return mPalette.getSecondColor(ring.angle);
        }
    }

    /** 半径、厚度或角度变了才需要重算顶点；环身份的切换也算。 */
    private boolean geometryChanged(int index, PolarClockScene.Ring ring) {
        if (!mGeometryValid[index]) return true;
        return mLastRingId[index] != ring.id
                || mLastRadiusBits[index] != Float.floatToIntBits(ring.radius)
                || mLastThicknessBits[index] != Float.floatToIntBits(ring.thickness)
                || mLastAngleBits[index] != Float.floatToIntBits(ring.angle);
    }

    /**
     * 与 PolarClockGL.drawRingArc / drawCap 逐行对应：内外两圈的条带，
     * 加上两端各一个「中心点 + 一圈圆周」的三角扇。
     */
    private void rebuildGeometry(int index, PolarClockScene.Ring ring, float sweep) {
        int segments = Math.max(2, (int) (360.0f * sweep));
        float outer = ring.radius + (ring.thickness * 0.5f);
        float inner = ring.radius - (ring.thickness * 0.5f);
        float maxAngle = (float) (Math.PI * 2.0 * sweep);

        ByteBuffer arc = mArcBuffers[index];
        arc.clear();
        for (int i = 0; i <= segments; i++) {
            float t = (float) i / (float) segments;
            float theta = maxAngle * t;
            float cos = (float) Math.cos(theta);
            float sin = (float) Math.sin(theta);
            arc.putFloat(outer * cos).putFloat(outer * sin);
            arc.putFloat(inner * cos).putFloat(inner * sin);
        }
        mArcVertexCounts[index] = (segments + 1) * 2;

        writeCap(mStartCapBuffers[index], ring.radius, ring.thickness, 0.0f);
        writeCap(mEndCapBuffers[index], ring.radius, ring.thickness, maxAngle);

        mGeometryValid[index] = true;
        mLastRingId[index] = ring.id;
        mLastRadiusBits[index] = Float.floatToIntBits(ring.radius);
        mLastThicknessBits[index] = Float.floatToIntBits(ring.thickness);
        mLastAngleBits[index] = Float.floatToIntBits(ring.angle);
    }

    private static void writeCap(ByteBuffer out, float radius, float thickness, float angleRad) {
        out.clear();
        float capRadius = thickness * 0.5f;
        float cx = (float) Math.cos(angleRad) * radius;
        float cy = (float) Math.sin(angleRad) * radius;

        out.putFloat(cx).putFloat(cy);
        for (int i = 0; i <= CAP_SEGMENTS; i++) {
            float t = (float) i / (float) CAP_SEGMENTS;
            float theta = (float) (Math.PI * 2.0 * t);
            out.putFloat(cx + (float) Math.cos(theta) * capRadius);
            out.putFloat(cy + (float) Math.sin(theta) * capRadius);
        }
    }

    /**
     * 与 PolarClockGL.drawFrame 相同的矩阵链：
     * 正交投影 (0, w, h, 0) × 平移到表盘中心 × 旋转 -90° × 横屏 0.9 缩放。
     * 最后左乘 GL → Vulkan 的裁剪空间修正，矩阵原样交给顶点着色器。
     */
    private void buildMvp() {
        Mat4.orthoM(mProj, 0f, mWidth, mHeight, 0f, -1f, 1f);
        Mat4.setIdentityM(mModel);

        float centerX = mWidth * (1.0f - (mPreview ? 0.5f : mOffsetX));
        float centerY = mHeight * 0.5f;
        Mat4.translateM(mModel, centerX, centerY, 0f);
        Mat4.rotateM(mModel, -90.0f, 0f, 0f, 1f);
        if (mHeight < mWidth) {
            Mat4.scaleM(mModel, 0.9f, 0.9f, 1f);
        }

        Mat4.multiplyMM(mGlMvp, mProj, mModel);
        // GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
        Mat4.multiplyMM(mVkMvp, CLIP_CORRECTION, mGlMvp);
    }

    /** 清屏色就是调色板的背景色（含 alpha），与 GLES 每帧 glClearColor 的口径一致。 */
    private void applyClearColorLocked() {
        VkPassScene scene = mPassScene;
        ClockPalette palette = mPalette;
        if (scene == null || palette == null) return;
        colorToRgba(palette.getBackgroundColor(), mColor);
        scene.setClearColor(mColor[0], mColor[1], mColor[2], mColor[3]);
    }

    /**
     * 与 PolarClockGL.colorToRgba 相同的 ARGB → RGBA 换算。调用方按顺序使用输出数组，
     * 写完立刻下发，所以与 GLES 一样复用同一份 scratch。
     */
    private static void colorToRgba(int color, float[] out) {
        out[0] = ((color >> 16) & 0xFF) / 255.0f;
        out[1] = ((color >> 8) & 0xFF) / 255.0f;
        out[2] = (color & 0xFF) / 255.0f;
        out[3] = ((color >> 24) & 0xFF) / 255.0f;
    }

    private void zeroAllPasses(VkPassFrame frame) {
        for (int i = 0; i < MAX_RINGS; i++) {
            zeroRing(frame, i);
        }
    }

    private void zeroRing(VkPassFrame frame, int index) {
        frame.setVertexCount(mArcPasses[index], 0);
        frame.setVertexCount(mStartCapPasses[index], 0);
        frame.setVertexCount(mEndCapPasses[index], 0);
    }

    private static ByteBuffer allocateBuffer(int vertexCount) {
        return ByteBuffer.allocateDirect(vertexCount * STRIDE_BYTES).order(ByteOrder.nativeOrder());
    }

    /** 读取偏好设置并把调色板切到场景选中的那一份，与 PolarClockGL.reloadFromPrefs 一致。 */
    private void applyPrefsLocked() {
        if (mPalettes.isEmpty()) loadPalettes();
        mScene.setPluginPrefs(mPrefs);
        ClockPalette pal = mPalettes.get(mScene.paletteId);
        if (pal != null) {
            mPalette = pal;
        } else if (mPalette == null) {
            mPalette = CyclingClockPalette.getFallback();
        }
    }

    /** 与 PolarClockGL.loadPalettes 相同的 XML 解析，只是资源从 context 取。 */
    private void loadPalettes() {
        Resources resources = context.getResources();
        XmlResourceParser xrp = resources.getXml(R.xml.polar_clock_palettes);
        try {
            int what = xrp.getEventType();
            while (what != END_DOCUMENT) {
                if (what == START_TAG && "palette".equals(xrp.getName())) {
                    ClockPalette pal = ClockPalette.parseXmlPaletteTag(xrp);
                    if (pal != null && pal.getId() != null) {
                        mPalettes.put(pal.getId(), pal);
                    }
                }
                what = xrp.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "加载调色板失败", e);
        } finally {
            xrp.close();
        }
    }

    /**
     * GL clip → Vulkan clip：y 取反，z 从 [-w, w] 映射到 [0, w]。
     * 与其余 VK delegate 使用同一份矩阵。
     */
    private static final float[] CLIP_CORRECTION = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.5f, 1,
    };
}
