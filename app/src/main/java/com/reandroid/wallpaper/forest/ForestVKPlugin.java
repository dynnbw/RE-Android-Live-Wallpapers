package com.reandroid.wallpaper.forest;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import com.reandroid.plugin.VkPassNative;
import com.reandroid.plugin.VkRendererEngine;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.vulkan.VKSurfaceView;

/**
 * Forest 的 Vulkan 插件入口。
 *
 * 壁纸与预览共用同一个 delegate；预览额外转发触摸，因为戳中草会生成粒子，
 * 与 GLES 版的交互一致。
 */
public class ForestVKPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "forest_vk";
    }

    @Override
    public WallpaperEngine createEngine(Context ctx, WallpaperPluginHost host) {
        final ForestVkDelegate delegate = new ForestVkDelegate(ctx);
        /*
         * 系统壁纸预览会走 setPreview(true)；GLES 版据此把滚动偏移固定为 0。
         * VkRendererDelegate 没有这个钩子，所以在引擎这层转给 delegate。
         */
        return new VkRendererEngine(ctx, host, delegate) {
            @Override
            public void setPreview(boolean isPreview) {
                delegate.setPreview(isPreview);
                super.setPreview(isPreview);
            }
        };
    }

    @Override
    public View createVulkanPreview(Context context, SharedPreferences prefs) {
        if (!VkPassNative.nIsSupported()) return null;
        final ForestVkDelegate delegate = new ForestVkDelegate(context);
        delegate.setPreview(true);
        VKSurfaceView view = new VKSurfaceView(context, delegate);
        view.setOnTouchListener((v, event) -> {
            delegate.onTouchEvent(event);
            return true;
        });
        return view;
    }

    @Override
    public void release() {}
}
