package com.reandroid.wallpaper.plant3d;

import android.content.Context;
import android.opengl.GLES30;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.GLTextureUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * 金钱树的 GLES 渲染：一份静态几何加 79 套蒙皮几何，一个方向光。
 *
 * <p>顶点布局直接沿用模型文件（位置/法线/贴图坐标，蒙皮几何多 8 个字节的
 * 四组关节索引和权重），所以 VBO 不需要重新打包，属性指针按偏移量绑定即可。
 */
public class Plant3DGL extends GLESScene {

    private static final String MODEL = "plant3d/plant3_pot_sm2_nl.glo";
    private static final String POSE = "plant3d/money_plant.txt";

    /** 原版相机常数（引擎自己的单位制）。 */
    private static final float[] CAMERA_POSITION = {19.367f, 27.567f, -49.415f};

    private static final float CAMERA_FOV = 23.9f;
    private static final float CAMERA_NEAR = 0.1f;
    private static final float CAMERA_FAR = 100.0f;

    /** 太阳光的位置，用来算方向。 */
    private static final float[] LIGHT_POSITION = {15.45f, 101.0f, 0.77f};

    private final Context mContext;
    private PlantScene mScene;
    private int mProgram;
    private int mViewProjection;
    private int mModel;
    private int mJointsPerVertex;
    private int mJointWorld;
    private int mDiffuse;
    private int mLightDirection;
    private int mAmbientColour;
    private int mDiffuseColour;
    private int mOpacity;

    private final Map<GloFile.Geometry, Gpu> mGpu = new HashMap<>();
    private final Map<String, Integer> mTextures = new HashMap<>();
    private int mWhiteTexture;
    private float[] mViewProjectionMatrix;
    private float[] mTarget;
    private long mLastFrame;

    /** 关节弹簧物理暂时关闭：稳定性还没调好，先只画静态姿态。 */
    private static final boolean PHYSICS_ENABLED = false;

    public Plant3DGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
    }

    @Override
    protected void onCreate() {
        String vertexSource = "#version 300 es\n"
                + "in vec3 aPosition;\n"
                + "in vec3 aNormal;\n"
                + "in vec2 aTexCoord;\n"
                + "in float aIndex0;\n"
                + "in float aWeight0;\n"
                + "in float aIndex1;\n"
                + "in float aWeight1;\n"
                + "in float aIndex2;\n"
                + "in float aWeight2;\n"
                + "in float aIndex3;\n"
                + "in float aWeight3;\n"
                + "uniform mat4 uViewProjection;\n"
                + "uniform mat4 uModel;\n"
                + "uniform int uJointsPerVertex;\n"
                + "uniform mat4 uJointWorld[20];\n"
                + "out vec2 vTexCoord;\n"
                + "out vec3 vNormal;\n"
                + "out vec3 vWorldPosition;\n"
                + "void main() {\n"
                + "  vec4 position;\n"
                + "  vec3 normal;\n"
                + "  if (uJointsPerVertex > 0) {\n"
                + "    mat4 skin = aWeight0 * uJointWorld[int(aIndex0 + 0.5)];\n"
                + "    if (uJointsPerVertex > 1 && aWeight1 > 0.0) {\n"
                + "      skin += aWeight1 * uJointWorld[int(aIndex1 + 0.5)];\n"
                + "    }\n"
                + "    if (uJointsPerVertex > 2 && aWeight2 > 0.0) {\n"
                + "      skin += aWeight2 * uJointWorld[int(aIndex2 + 0.5)];\n"
                + "    }\n"
                + "    if (uJointsPerVertex > 3 && aWeight3 > 0.0) {\n"
                + "      skin += aWeight3 * uJointWorld[int(aIndex3 + 0.5)];\n"
                + "    }\n"
                + "    position = skin * vec4(aPosition, 1.0);\n"
                + "    normal = normalize((skin * vec4(aNormal, 0.0)).xyz);\n"
                + "  } else {\n"
                + "    position = uModel * vec4(aPosition, 1.0);\n"
                + "    normal = normalize(mat3(uModel) * aNormal);\n"
                + "  }\n"
                + "  vWorldPosition = position.xyz;\n"
                + "  vNormal = normal;\n"
                + "  vTexCoord = aTexCoord;\n"
                + "  gl_Position = uViewProjection * position;\n"
                + "}";

        String fragmentSource = "#version 300 es\n"
                + "precision mediump float;\n"
                + "in vec2 vTexCoord;\n"
                + "in vec3 vNormal;\n"
                + "in vec3 vWorldPosition;\n"
                + "uniform sampler2D uDiffuse;\n"
                + "uniform vec3 uLightDirection;\n"
                + "uniform vec3 uAmbientColour;\n"
                + "uniform vec3 uDiffuseColour;\n"
                + "uniform float uOpacity;\n"
                + "out vec4 fragColour;\n"
                + "void main() {\n"
                + "  vec4 texel = texture(uDiffuse, vTexCoord);\n"
                + "  if (texel.a < 0.5) {\n"
                + "    discard;\n"
                + "  }\n"
                + "  float ndl = max(dot(normalize(vNormal), uLightDirection), 0.0);\n"
                + "  vec3 colour = texel.rgb * uDiffuseColour\n"
                + "      * (uAmbientColour + vec3(ndl));\n"
                + "  fragColour = vec4(colour, texel.a * uOpacity);\n"
                + "}";

        mProgram = createProgram(vertexSource, fragmentSource);
        if (mProgram == 0) {
            return;
        }
        mViewProjection = GLES30.glGetUniformLocation(mProgram, "uViewProjection");
        mModel = GLES30.glGetUniformLocation(mProgram, "uModel");
        mJointsPerVertex = GLES30.glGetUniformLocation(mProgram, "uJointsPerVertex");
        mJointWorld = GLES30.glGetUniformLocation(mProgram, "uJointWorld[0]");
        mDiffuse = GLES30.glGetUniformLocation(mProgram, "uDiffuse");
        mLightDirection = GLES30.glGetUniformLocation(mProgram, "uLightDirection");
        mAmbientColour = GLES30.glGetUniformLocation(mProgram, "uAmbientColour");
        mDiffuseColour = GLES30.glGetUniformLocation(mProgram, "uDiffuseColour");
        mOpacity = GLES30.glGetUniformLocation(mProgram, "uOpacity");

        byte[] data = AssetLoader.readBytes(mContext, MODEL);
        String pose = AssetLoader.readText(mContext, POSE);
        mScene = new PlantScene(new GloFile(data), pose);

        // 看向点是模型自身的包围盒中心：原版相机常数只给出机位（模型空间，
        // 随模型一起做 Z->Y 旋转），视线方向按舞台实际内容对准。
        mTarget = mScene.centre();
        float[] view = lookAt(CAMERA_POSITION, mTarget, new float[] {0f, 1f, 0f});
        float[] projection =
                perspective(CAMERA_FOV, (float) mWidth / mHeight, CAMERA_NEAR, CAMERA_FAR);
        mViewProjectionMatrix = new float[16];
        PlantScene.multiply(mViewProjectionMatrix, view, projection);

        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
    }

    @Override
    public void drawFrame(long timeMs) {
        GLES30.glViewport(0, 0, mWidth, mHeight);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        if (mProgram == 0 || mScene == null) {
            return;
        }
        long now = System.nanoTime();
        if (mLastFrame == 0L) {
            mLastFrame = now;
        }
        if (PHYSICS_ENABLED) {
            mScene.step((now - mLastFrame) / 1.0E9f);
        }
        mLastFrame = now;
        mScene.update();

        GLES30.glUseProgram(mProgram);
        GLES30.glUniformMatrix4fv(mViewProjection, 1, false, mViewProjectionMatrix, 0);
        float[] lightDirection = direction(LIGHT_POSITION, mTarget);
        GLES30.glUniform3f(
                mLightDirection, lightDirection[0], lightDirection[1], lightDirection[2]);
        GLES30.glUniform3f(mAmbientColour, 0.45f, 0.45f, 0.45f);
        GLES30.glUniform1f(mOpacity, 1f);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glUniform1i(mDiffuse, 0);

        for (PlantScene.Solid solid : mScene.getSolids()) {
            if (isShadow(solid)) {
                continue;
            }
            Gpu gpu = gpu(solid.geometry);
            if (gpu == null) {
                continue;
            }
            boolean skinned = solid.bindShape != null && solid.paletteSize > 0;
            GLES30.glUniform1i(mJointsPerVertex, skinned ? skinCount(solid.geometry) : 0);
            if (skinned) {
                GLES30.glUniformMatrix4fv(mJointWorld, solid.paletteSize, false, solid.palette, 0);
            } else {
                GLES30.glUniformMatrix4fv(mModel, 1, false, solid.node.world, 0);
            }
            float[] colour = diffuseColour(solid.material);
            GLES30.glUniform3f(mDiffuseColour, colour[0], colour[1], colour[2]);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture(solid.material));
            GLES30.glBindVertexArray(gpu.vao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, gpu.count, GLES30.GL_UNSIGNED_SHORT, 0);
            GLES30.glBindVertexArray(0);
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        if (mScene != null) {
            // 原版：屏幕像素偏移的一半，作为绕竖直轴的旋转角（度）。
            mScene.setScrollAngle(xPixels * 0.5f);
        }
    }

    @Override
    public void release() {
        for (Gpu gpu : mGpu.values()) {
            GLES30.glDeleteVertexArrays(1, new int[] {gpu.vao}, 0);
            GLES30.glDeleteBuffers(2, new int[] {gpu.vbo, gpu.ibo}, 0);
        }
        mGpu.clear();
        for (Integer texture : mTextures.values()) {
            GLES30.glDeleteTextures(1, new int[] {texture}, 0);
        }
        mTextures.clear();
        if (mProgram != 0) {
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }

    /** 阴影是贴在竖直平面上的投影面，需要单独的着色器，先不画。 */
    private static boolean isShadow(PlantScene.Solid solid) {
        return solid.material != null && solid.material.startsWith("_");
    }

    private static final class Gpu {
        int vao;
        int vbo;
        int ibo;
        int count;
    }

    private Gpu gpu(GloFile.Geometry geometry) {
        Gpu existing = mGpu.get(geometry);
        if (existing != null) {
            return existing;
        }
        Gpu gpu = new Gpu();
        int stride = geometry.stride();
        GloFile.Attribute position = geometry.attribute("a_position");
        GloFile.Attribute normal = geometry.attribute("a_normal");
        GloFile.Attribute uv = geometry.attribute("a_uv0");
        if (position == null || normal == null || uv == null || geometry.indices.length == 0) {
            return null;
        }
        int[] buffers = new int[2];
        GLES30.glGenBuffers(2, buffers, 0);
        gpu.vbo = buffers[0];
        gpu.ibo = buffers[1];
        ByteBuffer vertices = ByteBuffer.allocateDirect(geometry.vertexBytes.length)
                .order(ByteOrder.nativeOrder());
        vertices.put(geometry.vertexBytes).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gpu.vbo);
        GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                geometry.vertexBytes.length,
                vertices,
                GLES30.GL_STATIC_DRAW);

        ByteBuffer indices = ByteBuffer.allocateDirect(geometry.indices.length * 2)
                .order(ByteOrder.nativeOrder());
        for (short index : geometry.indices) {
            indices.putShort(index);
        }
        indices.position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gpu.ibo);
        GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER,
                geometry.indices.length * 2,
                indices,
                GLES30.GL_STATIC_DRAW);
        gpu.count = geometry.indices.length;

        int[] vao = new int[1];
        GLES30.glGenVertexArrays(1, vao, 0);
        gpu.vao = vao[0];
        GLES30.glBindVertexArray(gpu.vao);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gpu.vbo);
        // 元素缓冲的绑定属于 VAO 状态：必须在 VAO 绑定之后再绑一次，
        // 否则 glDrawElements 读到的是未定义内容（每帧都不一样）。
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gpu.ibo);
        bindAttribute(mProgram, "aPosition", 3, GLES30.GL_FLOAT, false, stride, position.offset);
        bindAttribute(mProgram, "aNormal", 3, GLES30.GL_FLOAT, false, stride, normal.offset);
        bindAttribute(mProgram, "aTexCoord", 2, GLES30.GL_FLOAT, false, stride, uv.offset);
        for (int i = 0; i < 4; i++) {
            GloFile.Attribute index = geometry.attribute("a_jointIndex" + i);
            GloFile.Attribute weight = geometry.attribute("a_jointWeight" + i);
            if (index == null || weight == null) {
                continue;
            }
            bindAttribute(
                    mProgram,
                    "aIndex" + i,
                    1,
                    GLES30.GL_UNSIGNED_BYTE,
                    false,
                    stride,
                    index.offset);
            bindAttribute(
                    mProgram,
                    "aWeight" + i,
                    1,
                    GLES30.GL_UNSIGNED_BYTE,
                    true,
                    stride,
                    weight.offset);
        }
        GLES30.glBindVertexArray(0);
        mGpu.put(geometry, gpu);
        return gpu;
    }

    private void bindAttribute(
            int program,
            String name,
            int size,
            int type,
            boolean normalized,
            int stride,
            int offset) {
        int location = GLES30.glGetAttribLocation(program, name);
        if (location < 0) {
            return;
        }
        GLES30.glEnableVertexAttribArray(location);
        GLES30.glVertexAttribPointer(location, size, type, normalized, stride, offset);
    }

    private static int skinCount(GloFile.Geometry geometry) {
        int count = 0;
        for (int i = 0; i < 4; i++) {
            if (geometry.attribute("a_jointIndex" + i) != null) {
                count++;
            }
        }
        return count;
    }

    private int texture(String materialName) {
        GloFile.Material material = mScene.getGlo().material(materialName);
        String file = material != null ? material.texture : null;
        if (file == null) {
            return whiteTexture();
        }
        Integer texture = mTextures.get(file);
        if (texture == null) {
            texture = GLTextureUtils.loadTextureFromAsset(mContext, "plant3d/" + file);
            mTextures.put(file, texture);
        }
        return texture;
    }

    /** 没有贴图的材质（花盆）用 1x1 白色贴图，颜色完全由材质色决定。 */
    private int whiteTexture() {
        if (mWhiteTexture != 0) {
            return mWhiteTexture;
        }
        int[] textures = new int[1];
        GLES30.glGenTextures(1, textures, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0]);
        ByteBuffer pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        pixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).position(0);
        GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA,
                1,
                1,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                pixel);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        mWhiteTexture = textures[0];
        return mWhiteTexture;
    }

    private float[] diffuseColour(String materialName) {
        GloFile.Material material = mScene.getGlo().material(materialName);
        if (material != null && material.colours.length >= 3) {
            return new float[] {material.colours[0], material.colours[1], material.colours[2]};
        }
        return new float[] {1f, 1f, 1f};
    }

    private static float[] direction(float[] from, float[] to) {
        float x = from[0] - to[0];
        float y = from[1] - to[1];
        float z = from[2] - to[2];
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length == 0f) {
            return new float[] {0f, 1f, 0f};
        }
        return new float[] {x / length, y / length, z / length};
    }

    private static float[] lookAt(float[] eye, float[] target, float[] up) {
        float[] back =
                normalise(new float[] {eye[0] - target[0], eye[1] - target[1], eye[2] - target[2]});
        float[] right = normalise(cross(up, back));
        float[] cameraUp = cross(back, right);
        return new float[] {
            right[0],
            right[1],
            right[2],
            0f,
            cameraUp[0],
            cameraUp[1],
            cameraUp[2],
            0f,
            back[0],
            back[1],
            back[2],
            0f,
            -dot(right, eye),
            -dot(cameraUp, eye),
            -dot(back, eye),
            1f
        };
    }

    /**
     * 竖屏时引擎把视场角用在<b>水平</b>方向（camera.cpp：{@code x = 1/tan(fov/2)}，
     * {@code y = x * aspect}），横屏才反过来。相机常数 23.9° 就是按这个口径写的。
     */
    private static float[] perspective(float fovDegrees, float aspect, float near, float far) {
        float f = 1f / (float) Math.tan(Math.toRadians(fovDegrees) / 2f);
        return new float[] {
            f,
            0f,
            0f,
            0f,
            0f,
            aspect * f,
            0f,
            0f,
            0f,
            0f,
            (far + near) / (near - far),
            -1f,
            0f,
            0f,
            2f * far * near / (near - far),
            0f
        };
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        };
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] normalise(float[] v) {
        float length = (float) Math.sqrt(dot(v, v));
        if (length == 0f) {
            return new float[] {0f, 0f, 1f};
        }
        return new float[] {v[0] / length, v[1] / length, v[2] / length};
    }
}
