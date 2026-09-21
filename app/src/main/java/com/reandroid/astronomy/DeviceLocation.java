package com.reandroid.astronomy;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import androidx.core.content.ContextCompat;

import com.reandroid.gles.GLESWallpaper;
import com.reandroid.utils.LocationProvider;

/**
 * 设备位置来源：调试覆盖 → GPS / 网络 / 被动的最后已知位置。
 *
 * <p>本类只管"怎么问系统要位置"；两个来源之间怎么取舍在 {@link LocationArbiter}
 * 里，那一半是纯 Java，也能在 JVM 上单独测。
 *
 * <p>{@link #resolve()} 返回 null 表示当前没有任何位置可用（覆盖被清除、又没有真实定位）。
 * 这不是"没事发生"，调用方应回到按时区反推的兜底，而不是留着上一次的值。
 *
 * <p>真实定位只有 5 分钟节流，而且**每次都记时间戳**（成功失败都记）。原先只记成功那次，
 * 于是"权限给了、但系统还没有任何最后已知位置"这种状态会让每一帧都去查一次
 * LocationManager —— 那是跨进程调用，60fps 下每秒 180 次。
 *
 * <p>调试覆盖不受节流约束：它是个测试开关，设上和清除都该立刻生效。
 * 代价是权限刚授予时最多要等 5 分钟才用上新位置；这个窗口可以接受 ——
 * 拿不到位置时用的是按时区反推的经度，误差上限约 1 小时，不是"没有日夜"。
 */
public final class DeviceLocation {

    private static final long UPDATE_INTERVAL_MS = 300000L;

    private final LocationArbiter mArbiter = new LocationArbiter();
    private long mLastAttemptMs;

    /**
     * 返回当前有效的位置 {@code {纬度, 经度}}；没有任何位置可用时返回 null。
     *
     * <p>结果没变时返回<b>同一个数组实例</b>，所以调用方可以用引用比较判断
     * "位置有没有真的变过"，避免拿每帧的老值去做重建。
     */
    public float[] resolve() {
        mArbiter.setOverride(LocationProvider.getDebugLocation());
        if (!mArbiter.hasOverride()) {
            refreshDevice();
        }
        return mArbiter.resolved();
    }

    private void refreshDevice() {
        long now = System.currentTimeMillis();
        if (mLastAttemptMs != 0L && (now - mLastAttemptMs) < UPDATE_INTERVAL_MS) {
            return;
        }

        Context ctx = GLESWallpaper.getAppContext();
        if (ctx == null) {
            // 还没拿到 Context 是启动早期的事，不记时间戳，下一帧再试
            return;
        }
        mLastAttemptMs = now;
        mArbiter.setDevice(query(ctx));
    }

    private float[] query(Context ctx) {
        boolean fine = hasPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = hasPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!fine && !coarse) {
            return null;
        }

        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return null;
        }

        Location best = null;
        try {
            Location gps = fine ? lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location passive = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            best = pickBestLocation(gps, net);
            best = pickBestLocation(best, passive);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 权限在查询途中被撤销，或者设备压根没有该 provider
        }

        if (best == null) {
            return null;
        }
        return new float[]{(float) best.getLatitude(), (float) best.getLongitude()};
    }

    private static boolean hasPermission(Context ctx, String permission) {
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static Location pickBestLocation(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getTime() >= b.getTime() ? a : b;
    }
}
