package com.reandroid.astronomy;

import com.reandroid.utils.LocationProvider;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

/**
 * {@link DeviceLocation} 的外壳接线。
 *
 * <p>取舍规则在 {@link LocationArbiterTest} 里测，这里只钉住"覆盖确实被接线传下去了"。
 *
 * <p><b>为什么只测到这几条</b>：一旦走到查真实定位那一步就会碰
 * {@code GLESWallpaper.getAppContext()}，而那个类的静态初始化（{@code Log.isLoggable}）
 * 在 JVM 单测里会抛 —— 所以本类只能测"覆盖生效"这一侧。真实定位那一侧靠设备验证。
 */
public class DeviceLocationTest {

    private static final float[] BEIJING = {39.9f, 116.4f};

    @After
    public void clearOverride() {
        LocationProvider.setDebugLocation(0.0f, 0.0f);
    }

    @Test
    public void overrideAppliesImmediately() {
        LocationProvider.setDebugLocation(BEIJING[0], BEIJING[1]);

        assertArrayEquals("首次解析就该给出手填的经纬度",
                BEIJING, new DeviceLocation().resolve(), 0.0f);
    }

    @Test
    public void changingOverrideAppliesImmediately() {
        LocationProvider.setDebugLocation(BEIJING[0], BEIJING[1]);
        DeviceLocation location = new DeviceLocation();
        location.resolve();

        LocationProvider.setDebugLocation(-33.9f, 151.2f);
        assertArrayEquals("改完经纬度应立刻生效，不等 5 分钟节流",
                new float[]{-33.9f, 151.2f}, location.resolve(), 0.0f);
    }

    /** 位置没变时给同一个实例 —— 调用方的引用比较全靠这条，否则每帧都会重建太阳计算器。 */
    @Test
    public void unchangedResultKeepsTheSameInstance() {
        LocationProvider.setDebugLocation(BEIJING[0], BEIJING[1]);
        DeviceLocation location = new DeviceLocation();

        float[] first = location.resolve();
        assertSame("同一组经纬度应复用同一个实例", first, location.resolve());
    }
}
