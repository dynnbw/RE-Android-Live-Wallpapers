package com.reandroid.wallpaper.nixietube;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

public class NixieTubeVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "nixietube_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        final NixieTubeVkDelegate delegate =
                new NixieTubeVkDelegate(ctx, host.getSharedPreferences(), false);
        /*
         * 通用 VK 引擎没有把可见性转给 delegate 的钩子。nixietube 的音频采集必须在
         * 不可见时停掉（与 GLES 的 NixieTubeEngine 一致），所以在引擎这层接一下。
         */
        return new VkRendererEngine(ctx, host, delegate) {
            @Override
            public void onVisibilityChanged(boolean visible) {
                if (visible) {
                    delegate.startAudio();
                } else {
                    delegate.stopAudio();
                    delegate.resetModeToTime();
                }
                super.onVisibilityChanged(visible);
            }
        };
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        final NixieTubeVkDelegate delegate = new NixieTubeVkDelegate(context, prefs, true);
        VKSurfaceView view = new VKSurfaceView(context, delegate);
        // 预览里点一下触发随机数字，对应 GLES 版的 onTouchEvent。
        view.setOnTouchListener((v, event) -> {
            delegate.onTouchEvent(event);
            return true;
        });
        return view;
    }

    @Override
    public void release() {}
}
