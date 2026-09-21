package com.reandroid.astronomy;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link LocationArbiter} 的取舍规则。
 *
 * <p>重点是<b>清除覆盖</b>那一路 —— 线上出过的 bug 就是它：设上覆盖会生效，
 * 清除之后却继续返回手填的经纬度。规则原来和系统查询搅在 {@code DeviceLocation} 里，
 * 而那半边在 JVM 上跑不了（{@code GLESWallpaper} 的静态初始化会抛），所以拆出来。
 */
public class LocationArbiterTest {

    private static final float[] BEIJING = {39.9f, 116.4f};
    private static final float[] SYDNEY = {-33.9f, 151.2f};
    private static final float[] GPS_FIX = {31.2f, 121.5f};

    @Test
    public void overrideWins() {
        LocationArbiter arbiter = new LocationArbiter();
        arbiter.setDevice(GPS_FIX);
        arbiter.setOverride(BEIJING);

        assertTrue("有覆盖时就别去问系统了", arbiter.hasOverride());
        assertArrayEquals("覆盖应压过真实定位", BEIJING, arbiter.resolved(), 0.0f);
    }

    @Test
    public void deviceIsUsedWhenThereIsNoOverride() {
        LocationArbiter arbiter = new LocationArbiter();
        arbiter.setOverride(BEIJING);
        arbiter.setOverride(null);
        arbiter.setDevice(GPS_FIX);

        assertFalse(arbiter.hasOverride());
        assertArrayEquals("没有覆盖时用真实定位", GPS_FIX, arbiter.resolved(), 0.0f);
    }

    /** 清除覆盖之后不能再返回那份手填的经纬度 —— 回归用例。 */
    @Test
    public void clearingOverrideFallsBackToNothing() {
        LocationArbiter arbiter = new LocationArbiter();
        arbiter.setOverride(BEIJING);
        assertArrayEquals("设上覆盖", BEIJING, arbiter.resolved(), 0.0f);

        arbiter.setOverride(null);
        assertNull("清除后既没有覆盖也没有真实定位，就该是 null", arbiter.resolved());
    }

    /** 清除是"一次变化"：引用必须换掉，否则调用方会以为什么都没发生。 */
    @Test
    public void clearingOverrideChangesTheIdentity() {
        LocationArbiter arbiter = new LocationArbiter();
        arbiter.setOverride(BEIJING);
        float[] before = arbiter.resolved();

        arbiter.setOverride(null);
        float[] after = arbiter.resolved();

        assertNotSame("清除后引用应改变", before, after);
        assertNull(after);
    }

    /** 清除覆盖但手上有真实定位时，要立刻回到它 —— 不用等 5 分钟节流。 */
    @Test
    public void clearingOverrideFallsBackToTheLastDeviceFix() {
        LocationArbiter arbiter = new LocationArbiter();
        arbiter.setDevice(GPS_FIX);
        arbiter.setOverride(BEIJING);
        float[] withOverride = arbiter.resolved();

        arbiter.setOverride(null);
        float[] afterClear = arbiter.resolved();

        assertArrayEquals("应回到真实定位", GPS_FIX, afterClear, 0.0f);
        assertNotSame("引用应改变", withOverride, afterClear);
    }

    /** 值没变时给同一个实例 —— 调用方的引用比较全靠这条。 */
    @Test
    public void unchangedResultKeepsTheSameInstance() {
        LocationArbiter arbiter = new LocationArbiter();
        arbiter.setOverride(BEIJING);

        float[] first = arbiter.resolved();
        assertSame("同一组经纬度应复用同一个实例", first, arbiter.resolved());
    }

    /** 重复设同一组值不算变化；改了值才算。 */
    @Test
    public void setOverrideReportsWhetherItChanged() {
        LocationArbiter arbiter = new LocationArbiter();

        assertTrue("第一次设上算变化", arbiter.setOverride(BEIJING));
        assertFalse("重复设同一组值不算变化", arbiter.setOverride(BEIJING));
        assertTrue("改值算变化", arbiter.setOverride(SYDNEY));
        assertTrue("清除算变化", arbiter.setOverride(null));
        assertFalse("已经清空了再清不算变化", arbiter.setOverride(null));
    }

    /**
     * 调用方传进来的数组之后被改掉，不能影响这里 ——
     * {@code LocationProvider} 交出来的是它自己的静态数组，谁都能改。
     */
    @Test
    public void overrideIsCopiedOnTheWayIn() {
        LocationArbiter arbiter = new LocationArbiter();
        float[] callerOwned = {39.9f, 116.4f};
        arbiter.setOverride(callerOwned);

        callerOwned[0] = -33.9f;
        callerOwned[1] = 151.2f;

        assertArrayEquals("存下来的应是当时那份", BEIJING, arbiter.resolved(), 0.0f);
    }
}
