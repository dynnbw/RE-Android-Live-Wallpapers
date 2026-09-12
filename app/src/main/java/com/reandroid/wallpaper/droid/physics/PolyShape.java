package com.reandroid.wallpaper.droid.physics;

/**
 * 凸多边形形状(局部坐标顶点,逆时针或顺时针均可)。
 * 退化多边形(如零半径线段展开后的 4 顶点)同样支持:零长度边的法线记为零向量,
 * 在 SAT 中不产生分离轴,与原始物理库的处理一致。
 */
public final class PolyShape extends Shape {

    final int count;
    /** 局部坐标顶点:x0,y0,x1,y1,... */
    final double[] verts;
    /** 局部坐标边法线(单位向量,零长度边为 0)。 */
    final double[] normals;
    /** 世界坐标顶点缓存。 */
    final double[] worldVerts;
    final double radius;

    PolyShape(Body body, double[] localVerts, int count, double radius) {
        super(body);
        this.count = count;
        this.radius = radius;
        this.verts = new double[count * 2];
        System.arraycopy(localVerts, 0, verts, 0, count * 2);
        normalizeWinding();
        this.normals = new double[count * 2];
        this.worldVerts = new double[count * 2];
        computeNormals();
        cacheBounds();
    }

    public static PolyShape createBox(Body body, double width, double height) {
        double hw = width * 0.5;
        double hh = height * 0.5;
        return new PolyShape(body, new double[] {
                -hw, -hh,
                -hw, hh,
                hw, hh,
                hw, -hh
        }, 4, 0.0);
    }

    public static PolyShape create(Body body, double[] verts) {
        return new PolyShape(body, verts, verts.length / 2, 0.0);
    }

    /**
     * 多边形转动惯量(密度均匀、质心在原点),与原始物理库 cpMomentForPoly 同式:
     * m * Σ(cross(v_i+1,v_i) * (v_i·v_i + v_i·v_i+1 + v_i+1·v_i+1)) / (6 * Σ cross)
     */
    public static double momentForPoly(double mass, double[] verts, int count) {
        double sum1 = 0.0;
        double sum2 = 0.0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double x1 = verts[i * 2];
            double y1 = verts[i * 2 + 1];
            double x2 = verts[j * 2];
            double y2 = verts[j * 2 + 1];
            double a = x2 * y1 - y2 * x1;
            double b = x1 * x1 + y1 * y1 + x1 * x2 + y1 * y2 + x2 * x2 + y2 * y2;
            sum1 += a * b;
            sum2 += a;
        }
        if (sum2 == 0.0) {
            return 1e-9;
        }
        return Math.abs(mass * sum1 / (6.0 * sum2));
    }

    public static double momentForBox(double mass, double width, double height) {
        double hw = width * 0.5;
        double hh = height * 0.5;
        return momentForPoly(mass, new double[] {
                -hw, -hh, -hw, hh, hw, hh, hw, -hh
        }, 4);
    }

    /**
     * 统一为逆时针绕向,使边法线(公式见 {@link #computeNormals()})恒为外法线。
     * 退化多边形(零面积)保持原样:其两条有效边法线天然互为反向,不依赖绕向。
     */
    private void normalizeWinding() {
        double area2 = 0.0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            area2 += verts[i * 2] * verts[j * 2 + 1] - verts[j * 2] * verts[i * 2 + 1];
        }
        if (area2 >= 0.0) {
            return;
        }
        for (int i = 0, j = count - 1; i < j; i++, j--) {
            double tx = verts[i * 2];
            double ty = verts[i * 2 + 1];
            verts[i * 2] = verts[j * 2];
            verts[i * 2 + 1] = verts[j * 2 + 1];
            verts[j * 2] = tx;
            verts[j * 2 + 1] = ty;
        }
    }

    /** 逆时针绕向下的外法线:n = perp(e) 取 (e.y, -e.x) 方向。 */
    private void computeNormals() {
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double ex = verts[j * 2] - verts[i * 2];
            double ey = verts[j * 2 + 1] - verts[i * 2 + 1];
            double len = Math.sqrt(ex * ex + ey * ey);
            if (len > 1e-12) {
                normals[i * 2] = ey / len;
                normals[i * 2 + 1] = -ex / len;
            } else {
                normals[i * 2] = 0.0;
                normals[i * 2 + 1] = 0.0;
            }
        }
    }

    @Override
    public void cacheBounds() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double lx = verts[i * 2];
            double ly = verts[i * 2 + 1];
            double wx = body.worldX(lx, ly);
            double wy = body.worldY(lx, ly);
            worldVerts[i * 2] = wx;
            worldVerts[i * 2 + 1] = wy;
            if (wx < minX) minX = wx;
            if (wx > maxX) maxX = wx;
            if (wy < minY) minY = wy;
            if (wy > maxY) maxY = wy;
        }
        if (minX > maxX) {
            minX = maxX = body.px;
            minY = maxY = body.py;
        }
        bbL = minX - radius;
        bbR = maxX + radius;
        bbB = minY - radius;
        bbT = maxY + radius;
    }

    @Override
    double area() {
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            sum += verts[i * 2] * verts[j * 2 + 1] - verts[j * 2] * verts[i * 2 + 1];
        }
        return Math.abs(sum) * 0.5;
    }

    /** 顶点(世界坐标)是否有效:零长度边被跳过后的顶点一定有效。 */
    double worldVertX(int i) {
        return worldVerts[i * 2];
    }

    double worldVertY(int i) {
        return worldVerts[i * 2 + 1];
    }
}
