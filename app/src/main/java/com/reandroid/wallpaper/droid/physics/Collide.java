package com.reandroid.wallpaper.droid.physics;

/**
 * 窄相碰撞检测:统一按世界坐标多边形做 SAT + 参考面裁剪。
 * 线段在 fill() 中已展开为退化四边形,因此线段↔多边形、线段↔线段
 * 与多边形↔多边形走同一条代码路径(与原始物理库一致)。
 */
final class Collide {

    private static final int MAX_VERTS = 16;
    private static final double EDGE_TOL = 0.001;

    private static final double[] VA = new double[MAX_VERTS * 2];
    private static final double[] VB = new double[MAX_VERTS * 2];
    private static final double[] NA = new double[MAX_VERTS * 2];
    private static final double[] NB = new double[MAX_VERTS * 2];

    private static final double[] CLIP1 = new double[4 * 2];
    private static final double[] CLIP2 = new double[4 * 2];

    private Collide() {
    }

    /**
     * 求交并填充 arbiter;返回是否有接触。
     * 接触法线方向由 a 指向 b。
     */
    static boolean collide(Shape a, Shape b, Arbiter arb) {
        int na = fill(a, VA, NA);
        int nb = fill(b, VB, NB);
        if (na < 2 || nb < 2) {
            return false;
        }

        double sepA = Double.NEGATIVE_INFINITY;
        int edgeA = -1;
        for (int i = 0; i < na; i++) {
            double nx = NA[i * 2];
            double ny = NA[i * 2 + 1];
            double s = minSeparation(VA, i, nx, ny, VB, nb);
            if (s > sepA) {
                sepA = s;
                edgeA = i;
            }
        }
        if (edgeA < 0 || sepA > 0.0) {
            return false;
        }

        double sepB = Double.NEGATIVE_INFINITY;
        int edgeB = -1;
        for (int i = 0; i < nb; i++) {
            double nx = NB[i * 2];
            double ny = NB[i * 2 + 1];
            double s = minSeparation(VB, i, nx, ny, VA, na);
            if (s > sepB) {
                sepB = s;
                edgeB = i;
            }
        }
        if (edgeB < 0 || sepB > 0.0) {
            return false;
        }

        // 以"穿透最浅"的那一侧作为参考面
        boolean flip = sepB > sepA + EDGE_TOL;
        double[] refVerts = flip ? VB : VA;
        int refCount = flip ? nb : na;
        int refEdge = flip ? edgeB : edgeA;
        double[] incVerts = flip ? VA : VB;
        int incCount = flip ? na : nb;

        return clipContacts(refVerts, refCount, refEdge, incVerts, incCount, flip, arb);
    }

    /** 把形状顶点与边法线填入世界坐标数组,返回顶点数。 */
    private static int fill(Shape shape, double[] verts, double[] normals) {
        int count;
        double[] src;
        if (shape instanceof PolyShape) {
            PolyShape poly = (PolyShape) shape;
            count = poly.count;
            src = poly.worldVerts;
        } else {
            SegmentShape segment = (SegmentShape) shape;
            count = SegmentShape.POLY_VERTS;
            src = segment.worldVerts;
        }
        if (count > MAX_VERTS) {
            count = MAX_VERTS;
        }
        System.arraycopy(src, 0, verts, 0, count * 2);

        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double ex = verts[j * 2] - verts[i * 2];
            double ey = verts[j * 2 + 1] - verts[i * 2 + 1];
            double len = Math.sqrt(ex * ex + ey * ey);
            if (len > 1e-12) {
                // 逆时针绕向下的外法线:(e.y, -e.x)
                normals[i * 2] = ey / len;
                normals[i * 2 + 1] = -ex / len;
            } else {
                // 退化边:零法线,不参与 SAT
                normals[i * 2] = 0.0;
                normals[i * 2 + 1] = 0.0;
            }
        }
        return count;
    }

    /** 另一多边形在第 face 条边法线方向上的最小间距。 */
    private static double minSeparation(double[] verts, int face, double nx, double ny,
            double[] other, int otherCount) {
        double px = verts[face * 2];
        double py = verts[face * 2 + 1];
        double best = Double.MAX_VALUE;
        for (int i = 0; i < otherCount; i++) {
            double dx = other[i * 2] - px;
            double dy = other[i * 2 + 1] - py;
            double s = dx * nx + dy * ny;
            if (s < best) {
                best = s;
            }
        }
        return best;
    }

    /**
     * 参考面裁剪:找出入射边,依次用参考面两侧平面裁剪,
     * 保留仍穿透的点作为接触点。
     */
    private static boolean clipContacts(double[] refVerts, int refCount, int refEdge,
            double[] incVerts, int incCount, boolean flip, Arbiter arb) {
        int next = (refEdge + 1) % refCount;
        double rx0 = refVerts[refEdge * 2];
        double ry0 = refVerts[refEdge * 2 + 1];
        double rx1 = refVerts[next * 2];
        double ry1 = refVerts[next * 2 + 1];

        double tx = rx1 - rx0;
        double ty = ry1 - ry0;
        double tLen = Math.sqrt(tx * tx + ty * ty);
        if (tLen <= 1e-12) {
            return false;
        }
        tx /= tLen;
        ty /= tLen;

        // 参考面外法线(与 fill() 同式:(e.y, -e.x))
        double nx = ty;
        double ny = -tx;
        double faceOffset = rx0 * nx + ry0 * ny;

        // 入射边:法线最反向的那条
        int incident = 0;
        double minDot = Double.MAX_VALUE;
        for (int i = 0; i < incCount; i++) {
            int j = (i + 1) % incCount;
            double ex = incVerts[j * 2] - incVerts[i * 2];
            double ey = incVerts[j * 2 + 1] - incVerts[i * 2 + 1];
            double len = Math.sqrt(ex * ex + ey * ey);
            if (len <= 1e-12) {
                continue;
            }
            double d = (ey / len) * nx + (-ex / len) * ny;
            if (d < minDot) {
                minDot = d;
                incident = i;
            }
        }
        int incidentNext = (incident + 1) % incCount;

        CLIP1[0] = incVerts[incident * 2];
        CLIP1[1] = incVerts[incident * 2 + 1];
        CLIP1[2] = incVerts[incidentNext * 2];
        CLIP1[3] = incVerts[incidentNext * 2 + 1];
        int clipCount = 2;

        // 裁剪到参考面区间 [rv0, rv1] 内
        clipCount = clip(CLIP1, clipCount, -tx, -ty, -(rx0 * tx + ry0 * ty), CLIP2);
        if (clipCount == 0) {
            return false;
        }
        clipCount = clip(CLIP2, clipCount, tx, ty, rx1 * tx + ry1 * ty, CLIP1);
        if (clipCount == 0) {
            return false;
        }

        arb.count = 0;
        for (int i = 0; i < clipCount && arb.count < Arbiter.MAX_CONTACTS; i++) {
            double px = CLIP1[i * 2];
            double py = CLIP1[i * 2 + 1];
            double separation = px * nx + py * ny - faceOffset;
            if (separation > 0.0) {
                continue;
            }
            int index = arb.count++;
            arb.px[index] = px;
            arb.py[index] = py;
            arb.depth[index] = -separation;
        }

        if (arb.count == 0) {
            return false;
        }

        // 法线必须由 a 指向 b:参考面属于 B 时需翻转
        if (flip) {
            arb.nx = -nx;
            arb.ny = -ny;
        } else {
            arb.nx = nx;
            arb.ny = ny;
        }
        return true;
    }

    /**
     * 保留满足 dot(n, p) - offset <= 0 的点,写入 out。
     */
    private static int clip(double[] in, int inCount, double nx, double ny, double offset, double[] out) {
        if (inCount == 0) {
            return 0;
        }
        double d0 = in[0] * nx + in[1] * ny - offset;
        double d1 = in[2] * nx + in[3] * ny - offset;

        int count = 0;
        if (d0 <= 0.0) {
            out[count * 2] = in[0];
            out[count * 2 + 1] = in[1];
            count++;
        }
        if (d1 <= 0.0) {
            out[count * 2] = in[2];
            out[count * 2 + 1] = in[3];
            count++;
        }
        if (d0 * d1 < 0.0) {
            double t = d0 / (d0 - d1);
            if (count < 2) {
                out[count * 2] = in[0] + t * (in[2] - in[0]);
                out[count * 2 + 1] = in[1] + t * (in[3] - in[1]);
                count++;
            }
        }
        return count;
    }
}
