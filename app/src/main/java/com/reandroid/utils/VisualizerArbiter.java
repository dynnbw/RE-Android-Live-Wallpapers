package com.reandroid.utils;

import android.media.audiofx.Visualizer;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 进程内的 Visualizer 仲裁。
 *
 * <p>系统限制同一个 audio session 只能有一个活跃（enabled）的 {@link Visualizer}。
 * 我们的应用内预览和壁纸引擎跑在同一个进程里，两边都会建 {@code Visualizer(0)}：
 * 后来者把先来的顶掉，但被顶掉的那个 {@code getEnabled()} 仍然返回 true，自己并不知道
 * 已经收不到数据 —— 表现就是"预览过系统选择器之后，应用内预览一直显示空闲"。
 *
 * <p>这里把进程内所有实例登记起来，谁要用谁先 {@link #acquire}：其余实例被显式关闭并
 * 通过 {@link Owner#onSuperseded()} 告知。被顶掉的采集器不会自动抢回来，等它自己的
 * 宿主下次 start 时再重新仲裁即可。
 */
public final class VisualizerArbiter {

    private static final String TAG = "VisualizerArbiter";

    public interface Owner {
        Visualizer visualizer();

        /** 自己持有的 Visualizer 被别人关掉了。 */
        void onSuperseded();
    }

    private static final List<WeakReference<Owner>> OWNERS = new ArrayList<>();

    private VisualizerArbiter() {}

    public static synchronized void register(Owner owner) {
        prune();
        OWNERS.add(new WeakReference<>(owner));
    }

    public static synchronized void unregister(Owner owner) {
        Iterator<WeakReference<Owner>> iterator = OWNERS.iterator();
        while (iterator.hasNext()) {
            Owner current = iterator.next().get();
            if (current == null || current == owner) {
                iterator.remove();
            }
        }
    }

    /**
     * 让 {@code owner} 的实例成为唯一的活跃实例：其他实例先关闭并告知其宿主，
     * 然后确保自己的实例处于 enabled。
     */
    public static void acquire(Owner owner) {
        List<Owner> superseded = new ArrayList<>();
        synchronized (VisualizerArbiter.class) {
            prune();
            for (WeakReference<Owner> reference : OWNERS) {
                Owner current = reference.get();
                if (current == null || current == owner) {
                    continue;
                }
                Visualizer visualizer = current.visualizer();
                if (visualizer == null) {
                    continue;
                }
                try {
                    if (visualizer.getEnabled()) {
                        visualizer.setEnabled(false);
                        superseded.add(current);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to disable a competing visualizer", e);
                }
            }
        }
        for (Owner current : superseded) {
            current.onSuperseded();
        }
        Visualizer visualizer = owner.visualizer();
        if (visualizer != null) {
            try {
                if (!visualizer.getEnabled()) {
                    visualizer.setEnabled(true);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable the visualizer", e);
            }
        }
    }

    /** 进程内是否还有别的采集器活着且 enabled（即当前真正在采集的是谁）。 */
    public static synchronized boolean isAnyOtherEnabled(Owner owner) {
        prune();
        for (WeakReference<Owner> reference : OWNERS) {
            Owner current = reference.get();
            if (current == null || current == owner) {
                continue;
            }
            Visualizer visualizer = current.visualizer();
            if (visualizer == null) {
                continue;
            }
            try {
                if (visualizer.getEnabled()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static void prune() {
        Iterator<WeakReference<Owner>> iterator = OWNERS.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) {
                iterator.remove();
            }
        }
    }
}
