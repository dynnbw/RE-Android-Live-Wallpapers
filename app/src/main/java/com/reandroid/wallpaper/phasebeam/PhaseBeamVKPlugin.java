package com.reandroid.wallpaper.phasebeam;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

public class PhaseBeamVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "phasebeam_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        final PhaseBeamVkDelegate delegate =
                new PhaseBeamVkDelegate(ctx, host.getSharedPreferences());
        /*
         * 通用 VK 引擎只转发 xOffset，把 xOffsetStep 丢了；phasebeam 靠步长区分
         * "桌面不滚动"（xOffset 恒为 0）与"停在首页"。在引擎层补这一路。
         */
        return new VkRendererEngine(ctx, host, delegate) {
            @Override
            public void onOffsetsChanged(float xo, float yo, float xs, float ys, int xp, int yp) {
                delegate.setScrollStep(xs);
                super.onOffsetsChanged(xo, yo, xs, ys, xp, yp);
            }
        };
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        return new VKSurfaceView(context, new PhaseBeamVkDelegate(context, prefs));
    }

    @Override
    public void release() {}
}
