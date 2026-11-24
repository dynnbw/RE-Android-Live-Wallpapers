package com.reandroid.wallpaper.plant3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scene graph, skin palettes and per-frame joint transforms.
 *
 * <p>Matrices are row-major with the translation in the last row, the same
 * convention as the model file: a point is transformed as {@code p' = p * M}
 * and the world transform of a child is {@code local * parentWorld}. When such
 * a matrix is uploaded to GL as-is and multiplied on the left of a vector, the
 * column-major interpretation cancels the transpose out.
 *
 * <p>A skinned solid gets one matrix per palette slot:
 * {@code bindShape * inverseBind * jointWorld}, i.e. the mesh is moved into
 * bind space, into bone space and back out through the joint.
 */
final class PlantScene {

    static final int MAX_PALETTE = 20;

    static final class Node {
        final String name;
        final String joint;
        final float[] local = new float[16];
        final float[] baseLocal = new float[16];
        final float[] world = new float[16];
        Node parent;
        final List<Node> children = new ArrayList<>();
        String geometry;
        String material;
        String skin;
        float[] poseTranslation;
        float[] poseScale;
        float[] poseRotation;
        boolean posed;

        Node(String name, String joint, float[] matrix) {
            this.name = name;
            this.joint = joint;
            System.arraycopy(matrix, 0, local, 0, 16);
            System.arraycopy(matrix, 0, baseLocal, 0, 16);
        }
    }

    static final class Solid {
        final Node node;
        final GloFile.Geometry geometry;
        final float[] bindShape; // null for unskinned geometry
        final Node[] joints;
        final float[][] inverseBindPoses;
        final float[] palette;
        int paletteSize;
        String material;

        Solid(Node node, GloFile.Geometry geometry, float[] bindShape, int paletteCapacity) {
            this.node = node;
            this.geometry = geometry;
            this.bindShape = bindShape;
            this.joints = new Node[paletteCapacity];
            this.inverseBindPoses = new float[paletteCapacity][];
            this.palette = new float[paletteCapacity * 16];
        }
    }

    private final GloFile mGlo;
    private final List<Node> mNodes = new ArrayList<>();
    private final Map<String, Node> mByJoint = new HashMap<>();
    private final Map<String, Node> mByName = new HashMap<>();
    private final List<Solid> mSolids = new ArrayList<>();
    private Node mRootJoint;
    private final List<PlantJoints.Joint> mJoints = new ArrayList<>();
    private final Map<String, PlantJoints.Joint> mJointByName = new HashMap<>();
    private final PlantJoints.Global mGlobal = new PlantJoints.Global();
    private double mAccumulator;
    private float[] mScrollRotation;
    private float mScrollAngle;

    PlantScene(GloFile glo, String poseText) {
        mGlo = glo;
        flatten();
        applyPose(poseText);
        applyUpAxis();
        buildWorlds();
        buildSolids();
        update();
    }

    /**
     * 原版运行时用 money_plant.txt 重新设置每个关节的位移/旋转/缩放（GLO 里那套节点
     * 矩阵不是最终姿态）。每行 12 个字段：名字, 父节点, 位移 xyz, 四元数 wxyz, 缩放 xyz。
     * 局部位姿按行向量约定组合成 {@code S * R * T}。
     */
    private void applyPose(String text) {
        if (text == null) {
            return;
        }
        String[] lines = text.split("\\n");
        for (int i = 3; i < lines.length; i++) {
            String[] fields = lines[i].split(",");
            if (fields.length != 12) {
                continue;
            }
            Node node = mByName.get(fields[0]);
            if (node == null) {
                continue;
            }
            if (mRootJoint == null) {
                mRootJoint = node;
                mScrollRotation = new float[16];
                identity(mScrollRotation);
            }
            try {
                float[] translation = {
                    Float.parseFloat(fields[2]),
                    Float.parseFloat(fields[3]),
                    Float.parseFloat(fields[4])
                };
                // 文件里的四元数是节点旋转的共轭（x/y/z 取反），与 GLO 矩阵
                // 逐节点比对过：共轭后 466/467 完全吻合。
                float[] rotation = {
                    Float.parseFloat(fields[5]),
                    -Float.parseFloat(fields[6]),
                    -Float.parseFloat(fields[7]),
                    -Float.parseFloat(fields[8])
                };
                float[] scale = {
                    Float.parseFloat(fields[9]),
                    Float.parseFloat(fields[10]),
                    Float.parseFloat(fields[11])
                };
                float[] local = new float[16];
                multiply(
                        local,
                        0,
                        scaleMatrix(scale),
                        rotationMatrix(rotation),
                        translationMatrix(translation));
                System.arraycopy(local, 0, node.local, 0, 16);
                System.arraycopy(local, 0, node.baseLocal, 0, 16);
                node.poseTranslation = translation;
                node.poseScale = scale;
                node.poseRotation = rotation;
                node.posed = true;
                PlantJoints.Physics physics = physicsFor(fields[0]);
                PlantJoints.Joint parent = mJointByName.get(fields[1]);
                PlantJoints.Joint joint = new PlantJoints.Joint(
                        node,
                        parent,
                        Float.parseFloat(fields[2]),
                        Float.parseFloat(fields[3]),
                        Float.parseFloat(fields[4]),
                        Float.parseFloat(fields[5]),
                        Float.parseFloat(fields[6]),
                        Float.parseFloat(fields[7]),
                        Float.parseFloat(fields[8]),
                        physics,
                        mGlobal);
                mJoints.add(joint);
                mJointByName.put(fields[0], joint);
            } catch (NumberFormatException e) {
                // 跳过格式不对的行
            }
        }
    }

    /** 原版按关节名分三档：叶片、枝条、主干。 */
    private static PlantJoints.Physics physicsFor(String name) {
        if (name.contains("leaf")) {
            return new PlantJoints.Physics(0.1f, 0.54f, damping(0.933f));
        }
        if (name.contains("branch")) {
            return new PlantJoints.Physics(0.075f, 0.54f, damping(0.642f));
        }
        return new PlantJoints.Physics(0.075f, 0.4f, damping(0.5f));
    }

    private static float damping(float value) {
        return (float) (1.0 - Math.pow(10.0, value - 2.0));
    }

    /**
     * 推进弹簧物理（原版主线程固定 10ms 一步；这里按累积时间在渲染线程里走）。
     */
    void step(float deltaSeconds) {
        if (mJoints.isEmpty()) {
            return;
        }
        mAccumulator += Math.min(deltaSeconds, 0.1f);
        while (mAccumulator >= 0.01) {
            mAccumulator -= 0.01;
            for (PlantJoints.Joint joint : mJoints) {
                joint.updateSpringPosition();
            }
            for (PlantJoints.Joint joint : mJoints) {
                joint.updateNodePosition();
            }
            for (PlantJoints.Joint joint : mJoints) {
                joint.updateNodeRotation();
            }
        }
        for (PlantJoints.Joint joint : mJoints) {
            Node node = joint.node;
            if (node.poseTranslation == null || node == mRootJoint) {
                continue;
            }
            PlantJoints.Quat rotation = joint.rotation();
            // 节点矩阵用的是共轭约定（和姿态应用保持一致）
            float[] quaternion = {rotation.w, -rotation.x, -rotation.y, -rotation.z};
            multiply(
                    node.local,
                    0,
                    scaleMatrix(node.poseScale),
                    rotationMatrix(quaternion),
                    translationMatrix(node.poseTranslation));
        }
    }

    private static float[] rotationMatrix(float[] q) {
        float w = q[0], x = q[1], y = q[2], z = q[3];
        float length = (float) Math.sqrt(w * w + x * x + y * y + z * z);
        if (length == 0f) {
            return identityMatrix();
        }
        w /= length;
        x /= length;
        y /= length;
        z /= length;
        return new float[] {
            1 - 2 * (y * y + z * z),
            2 * (x * y - z * w),
            2 * (x * z + y * w),
            0f,
            2 * (x * y + z * w),
            1 - 2 * (x * x + z * z),
            2 * (y * z - x * w),
            0f,
            2 * (x * z - y * w),
            2 * (y * z + x * w),
            1 - 2 * (x * x + y * y),
            0f,
            0f,
            0f,
            0f,
            1f
        };
    }

    private static float[] translationMatrix(float[] t) {
        return new float[] {1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, t[0], t[1], t[2], 1f};
    }

    private static float[] scaleMatrix(float[] s) {
        return new float[] {s[0], 0f, 0f, 0f, 0f, s[1], 0f, 0f, 0f, 0f, s[2], 0f, 0f, 0f, 0f, 1f};
    }

    private static float[] identityMatrix() {
        return new float[] {1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};
    }

    /**
     * 模型是 Z 轴朝上导出的（地面在 z=-3.5），引擎世界是 Y 朝上
     * （阴影平面的法线就是 (0,1,0)）。根节点是单位矩阵，所以把
     * -90° 绕 X 的旋转挂在根上即可带动所有子节点和蒙皮。
     */
    private void applyUpAxis() {
        float[] upAxis = {
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f
        };
        for (Node node : mNodes) {
            if (node.parent == null) {
                float[] local = new float[16];
                multiply(local, node.local, upAxis);
                System.arraycopy(local, 0, node.local, 0, 16);
            }
        }
    }

    /**
     * 全部顶点在世界空间的包围盒中心（每 4 个顶点取一个，够用来定相机看向点）。
     */
    float[] centre() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Solid solid : mSolids) {
            GloFile.Attribute position = solid.geometry.attribute("a_position");
            if (position == null) {
                continue;
            }
            int stride = solid.geometry.stride();
            byte[] bytes = solid.geometry.vertexBytes;
            float[] matrix = solid.bindShape != null && solid.paletteSize > 0
                    ? solid.palette
                    : solid.node.world;
            for (int i = 0; i < solid.geometry.vertexCount; i += 4) {
                int base = i * stride + position.offset;
                float px = Float.intBitsToFloat(readInt(bytes, base));
                float py = Float.intBitsToFloat(readInt(bytes, base + 4));
                float pz = Float.intBitsToFloat(readInt(bytes, base + 8));
                float wx = matrix[0] * px + matrix[4] * py + matrix[8] * pz + matrix[12];
                float wy = matrix[1] * px + matrix[5] * py + matrix[9] * pz + matrix[13];
                float wz = matrix[2] * px + matrix[6] * py + matrix[10] * pz + matrix[14];
                minX = Math.min(minX, wx);
                maxX = Math.max(maxX, wx);
                minY = Math.min(minY, wy);
                maxY = Math.max(maxY, wy);
                minZ = Math.min(minZ, wz);
                maxZ = Math.max(maxZ, wz);
            }
        }
        if (minX > maxX) {
            return new float[] {0f, 0f, 0f};
        }
        return new float[] {(minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f};
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16
                | (bytes[offset + 3] & 0xff) << 24;
    }

    GloFile getGlo() {
        return mGlo;
    }

    List<Solid> getSolids() {
        return mSolids;
    }

    Node node(String name) {
        return mByName.get(name);
    }

    private void flatten() {
        for (GloFile.Node root : mGlo.roots) {
            flatten(root, null);
        }
    }

    private void flatten(GloFile.Node source, Node parent) {
        Node node = new Node(source.name, source.joint, source.matrix);
        node.geometry = source.geometry;
        node.material = source.material;
        node.skin = source.skin;
        node.parent = parent;
        if (parent != null) {
            parent.children.add(node);
        }
        mNodes.add(node);
        if (!node.name.isEmpty()) {
            mByName.put(node.name, node);
        }
        if (!node.joint.isEmpty()) {
            mByJoint.put(node.joint, node);
        }
        for (GloFile.Node child : source.children) {
            flatten(child, node);
        }
    }

    private void buildWorlds() {
        // Depth first order keeps every parent before its children.
        for (Node node : mNodes) {
            if (node.parent == null) {
                System.arraycopy(node.local, 0, node.world, 0, 16);
            } else {
                multiply(node.world, node.local, node.parent.world);
            }
        }
    }

    private void buildSolids() {
        for (Node node : mNodes) {
            if (node.geometry != null) {
                GloFile.Geometry geometry = mGlo.geometry(node.geometry);
                if (geometry != null) {
                    Solid solid = new Solid(node, geometry, null, 0);
                    solid.material = node.material;
                    mSolids.add(solid);
                }
            } else if (node.skin != null) {
                GloFile.Skin skin = mGlo.skin(node.skin);
                if (skin == null) {
                    continue;
                }
                GloFile.Geometry geometry = mGlo.geometry(skin.geometry);
                if (geometry == null) {
                    continue;
                }
                Solid solid = new Solid(
                        node,
                        geometry,
                        skin.bindShapeTransform,
                        Math.min(skin.attachments.size(), MAX_PALETTE));
                solid.material = node.material;
                for (GloFile.Attachment attachment : skin.attachments) {
                    Node joint = mByJoint.get(attachment.joint);
                    if (joint == null || solid.paletteSize >= solid.joints.length) {
                        continue;
                    }
                    solid.joints[solid.paletteSize] = joint;
                    solid.inverseBindPoses[solid.paletteSize] = attachment.inverseBindPose;
                    solid.paletteSize++;
                }
                mSolids.add(solid);
            }
        }
    }

    /** Recomputes every world transform and the skin palettes. */
    /**
     * 原版把屏幕偏移换算成绕模型竖直轴的旋转，加在根关节上（滑动桌面时整棵树转身）。
     * 角度单位是度：{@code xPixelOffset * 0.5}。
     */
    void setScrollAngle(float degrees) {
        if (mScrollAngle == degrees) {
            return;
        }
        mScrollAngle = degrees;
        if (mRootJoint == null) {
            return;
        }
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float[] rotation = {cos, sin, 0f, 0f, -sin, cos, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};
        multiply(mRootJoint.local, mRootJoint.baseLocal, rotation);
    }

    void update() {
        for (Node node : mNodes) {
            if (node.parent == null) {
                System.arraycopy(node.local, 0, node.world, 0, 16);
            } else {
                multiply(node.world, node.local, node.parent.world);
            }
        }
        for (Solid solid : mSolids) {
            if (solid.bindShape == null) {
                continue;
            }
            for (int i = 0; i < solid.paletteSize; i++) {
                multiply(
                        solid.palette,
                        i * 16,
                        solid.bindShape,
                        solid.inverseBindPoses[i],
                        solid.joints[i].world);
            }
        }
    }

    // -- maths -----------------------------------------------------------

    static void multiply(float[] out, float[] a, float[] b) {
        multiply(out, 0, a, b, 0);
    }

    static void multiply(float[] out, int outOffset, float[] a, float[] b, int bOffset) {
        float[] result = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[row * 4 + k] * b[bOffset + k * 4 + column];
                }
                result[row * 4 + column] = sum;
            }
        }
        System.arraycopy(result, 0, out, outOffset, 16);
    }

    /** Composes {@code a * b * c} (row-major, row vector convention). */
    static void multiply(float[] out, int outOffset, float[] a, float[] b, float[] c) {
        float[] ab = new float[16];
        multiply(ab, 0, a, b, 0);
        multiply(out, outOffset, ab, c, 0);
    }

    static void identity(float[] matrix) {
        Arrays.fill(matrix, 0f);
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1f;
    }
}
