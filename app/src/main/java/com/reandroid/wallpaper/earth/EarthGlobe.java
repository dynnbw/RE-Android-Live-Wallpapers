package com.reandroid.wallpaper.earth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 球体网格：解析 Wavefront OBJ、归一化成正球、产出交错顶点数组。
 * 纯 Java，无 GL/Android 依赖，可 JVM 测试。
 *
 * <p>素材是用户提供的高面数版本（4290 顶点 / 8576 三角，原版的 4.3 倍）。
 * 顶点与原版**逐点 UV 一致**，所以贴图可以直接沿用。
 *
 * <p><b>为什么要归一化</b>：该网格是细分产物，实测半径随纬度变化
 * （极点 1.50000、赤道 1.48800），是个长球而非正球，方向还与真实地球相反。
 * 归一化到 {@link #NORMALIZED_RADIUS} 后与原版完全一致，光晕半径等按包围盒算的量也不再偏。
 *
 * <p><b>法线必须一并重算</b>：文件自带的 4290 个 {@code vn} 是配那个扁球的；
 * 位置归一化后若继续用旧法线，光照会与几何对不上（高光位置偏移）。
 * 球面上法线恒等于归一化后的位置，直接取即可。
 */
final class EarthGlobe {

    /** 归一化目标半径，与原版网格一致。 */
    static final float NORMALIZED_RADIUS = 1.5f;
    /** 每个顶点的浮点数：x,y,z, nx,ny,nz, u,v。 */
    static final int FLOATS_PER_VERTEX = 8;

    /** 交错顶点数据。 */
    final float[] vertices;
    /** 三角形索引。 */
    final short[] indices;
    final int vertexCount;
    final int triangleCount;

    private EarthGlobe(float[] vertices, short[] indices, int vertexCount, int triangleCount) {
        this.vertices = vertices;
        this.indices = indices;
        this.vertexCount = vertexCount;
        this.triangleCount = triangleCount;
    }

    /** 网格归一化后的半径（供光晕等计算用）。 */
    float radius() {
        return NORMALIZED_RADIUS;
    }

    /**
     * 从 OBJ 流加载。
     *
     * <p>支持 {@code v} / {@code vt} / {@code vn} / {@code f}，面支持三边与多边（扇形三角化），
     * 索引支持 {@code v}、{@code v/vt}、{@code v//vn}、{@code v/vt/vn} 四种写法。
     *
     * @throws IOException 读取出错，或文件里没有任何三角面
     */
    static EarthGlobe load(InputStream in) throws IOException {
        List<float[]> positions = new ArrayList<>();
        List<float[]> texCoords = new ArrayList<>();
        // 面：每项是若干个 (v,vt,vn) 三元组（索引已转成 0 基）
        List<int[][]> faces = new ArrayList<>();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (line.startsWith("v ")) {
                    positions.add(parse3(line));
                } else if (line.startsWith("vt ")) {
                    texCoords.add(parse2(line));
                } else if (line.startsWith("vn ")) {
                    // 法线全部重算，文件里的值丢弃
                } else if (line.startsWith("f ")) {
                    int[][] face = parseFace(line, positions.size(), texCoords.size());
                    if (face != null && face.length >= 3) faces.add(face);
                }
            }
        } finally {
            reader.close();
        }

        if (positions.isEmpty() || faces.isEmpty()) {
            throw new IOException("OBJ 里没有可用的顶点或面");
        }

        // 展开成"每面角点独立索引"：同一个 (v,vt) 复用同一个顶点槽位
        Map<Long, Integer> dedup = new HashMap<>();
        List<float[]> out = new ArrayList<>();
        List<Short> tris = new ArrayList<>();

        for (int[][] face : faces) {
            // 扇形三角化：0-1-2, 0-2-3, ...
            int first = resolve(face[0], positions, texCoords, dedup, out);
            for (int i = 1; i + 1 < face.length; i++) {
                int b = resolve(face[i], positions, texCoords, dedup, out);
                int c = resolve(face[i + 1], positions, texCoords, dedup, out);
                tris.add((short) first);
                tris.add((short) b);
                tris.add((short) c);
            }
        }

        int vertexCount = out.size();
        float[] vertices = new float[vertexCount * FLOATS_PER_VERTEX];
        for (int i = 0; i < vertexCount; i++) {
            float[] src = out.get(i);
            System.arraycopy(src, 0, vertices, i * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
        }
        short[] indices = new short[tris.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = tris.get(i);
        }
        return new EarthGlobe(vertices, indices, vertexCount, indices.length / 3);
    }

    /** 把面角点解析成顶点槽位（必要时新建），返回槽位号。 */
    private static int resolve(int[] corner, List<float[]> positions, List<float[]> texCoords,
                               Map<Long, Integer> dedup, List<float[]> out) {
        int vi = corner[0];
        int ti = corner[1];
        long key = ((long) vi << 32) | (ti & 0xFFFFFFFFL);
        Integer cached = dedup.get(key);
        if (cached != null) return cached;

        float[] pos = positions.get(vi);
        // 归一化到正球；法线 = 归一化后的位置
        float len = (float) Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
        if (len < 1.0E-6f) len = 1.0f;
        float nx = pos[0] / len;
        float ny = pos[1] / len;
        float nz = pos[2] / len;

        float u = 0.0f;
        float v = 0.0f;
        if (ti >= 0 && ti < texCoords.size()) {
            float[] uv = texCoords.get(ti);
            u = uv[0];
            v = uv[1];
        }

        float[] vert = new float[] {
                nx * NORMALIZED_RADIUS, ny * NORMALIZED_RADIUS, nz * NORMALIZED_RADIUS,
                nx, ny, nz,
                u, v
        };
        int slot = out.size();
        out.add(vert);
        dedup.put(key, slot);
        return slot;
    }

    private static float[] parse3(String line) {
        String[] t = line.trim().split("\\s+");
        return new float[] { f(t, 1), f(t, 2), f(t, 3) };
    }

    private static float[] parse2(String line) {
        String[] t = line.trim().split("\\s+");
        return new float[] { f(t, 1), f(t, 2) };
    }

    private static float f(String[] tokens, int i) {
        if (i >= tokens.length) return 0.0f;
        try {
            return Float.parseFloat(tokens[i]);
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    /**
     * 解析一个面。返回每角点的 {v, vt}（0 基）；vn 不保留（法线全部重算）。
     * OBJ 的三种写法都支持，缺失的 vt 记为 -1。
     */
    private static int[][] parseFace(String line, int vertexCount, int texCoordCount) {
        String[] tokens = line.trim().split("\\s+");
        List<int[]> corners = new ArrayList<>(tokens.length - 1);
        for (int i = 1; i < tokens.length; i++) {
            String[] parts = tokens[i].split("/");
            int vi = objIndex(parts[0], vertexCount);
            if (vi < 0) return null;
            int ti = -1;
            if (parts.length > 1 && !parts[1].isEmpty()) {
                ti = objIndex(parts[1], texCoordCount);
            }
            corners.add(new int[] { vi, ti });
        }
        return corners.toArray(new int[0][]);
    }

    /** OBJ 索引 1 基；负数是"从末尾倒数"。越界返回 -1。 */
    private static int objIndex(String s, int count) {
        int raw;
        try {
            raw = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        int idx = raw > 0 ? raw - 1 : count + raw;
        return (idx >= 0 && idx < count) ? idx : -1;
    }
}
