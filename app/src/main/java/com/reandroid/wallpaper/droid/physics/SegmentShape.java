package com.reandroid.wallpaper.droid.physics;

/**
 * 线段形状(局部坐标两端点 + 半径),用于机器人四肢。
 * 碰撞时按原始物理库的做法展开成退化四边形 (A, B, B, A) 参与多边形 SAT,
 * 因此零半径线段与多边形 / 线段之间都能正常求交。
 */
public final class SegmentShape extends Shape {

    /** 作为多边形参与碰撞时的顶点数。 */
    static final int POLY_VERTS = 4;

    final double ax;
    final double ay;
    final double bx;
    final double by;
    final double radius;

    /** 展开后的世界坐标顶点(A-, B-, B+, A+)。 */
    final double[] worldVerts = new double[POLY_VERTS * 2];

    SegmentShape(Body body, double ax, double ay, double bx, double by, double radius) {
        super(body);
        this.ax = ax;
        this.ay = ay;
        this.bx = bx;
        this.by = by;
        this.radius = radius;
        cacheBounds();
    }

    public static SegmentShape create(Body body, double ax, double ay, double bx, double by) {
        return new SegmentShape(body, ax, ay, bx, by, 0.0);
    }

    /** 单位法线方向(垂直于线段),退化线段返回 (0,1)。 */
    private double normalX() {
        double ex = bx - ax;
        double ey = by - ay;
        double len = Math.sqrt(ex * ex + ey * ey);
        if (len <= 1e-12) {
            return 0.0;
        }
        return -ey / len;
    }

    private double normalY() {
        double ex = bx - ax;
        double ey = by - ay;
        double len = Math.sqrt(ex * ex + ey * ey);
        if (len <= 1e-12) {
            return 1.0;
        }
        return ex / len;
    }

    @Override
    public void cacheBounds() {
        double nx = normalX();
        double ny = normalY();
        double ox = nx * radius;
        double oy = ny * radius;

        // A-, B-, B+, A+
        setWorldVert(0, ax - ox, ay - oy);
        setWorldVert(1, bx - ox, by - oy);
        setWorldVert(2, bx + ox, by + oy);
        setWorldVert(3, ax + ox, ay + oy);

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < POLY_VERTS; i++) {
            double wx = worldVerts[i * 2];
            double wy = worldVerts[i * 2 + 1];
            if (wx < minX) minX = wx;
            if (wx > maxX) maxX = wx;
            if (wy < minY) minY = wy;
            if (wy > maxY) maxY = wy;
        }
        bbL = minX;
        bbR = maxX;
        bbB = minY;
        bbT = maxY;
    }

    private void setWorldVert(int index, double lx, double ly) {
        worldVerts[index * 2] = body.worldX(lx, ly);
        worldVerts[index * 2 + 1] = body.worldY(lx, ly);
    }

    @Override
    double area() {
        return 0.0;
    }
}
