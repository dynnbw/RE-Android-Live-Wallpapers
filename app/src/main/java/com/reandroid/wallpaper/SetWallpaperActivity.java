package com.reandroid.wallpaper;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.reandroid.wallpaper.fall.FallWallpaper;
import com.reandroid.wallpaper.grass.GrassWallpaper;
import com.reandroid.wallpaper.galaxy.GalaxyWallpaper;
import com.reandroid.wallpaper.nexus.NexusWallpaper;
import com.reandroid.wallpaper.wildworld.WildWorldWallpaper;
import com.reandroid.wallpaper.fireworks.FireworksWallpaper;
import com.reandroid.wallpaper.nightsky.NightSkyWallpaper;
import com.reandroid.wallpaper.microbes.MicrobesWallpaper;
import com.reandroid.wallpaper.polarclock.PolarClockWallpaper;

/**
 * 设置动态壁纸的核心Activity
 * 负责接收壁纸类型参数，匹配对应的壁纸类，并发起设置动态壁纸的系统请求
 */
public class SetWallpaperActivity extends Activity {
    // 日志标签，用于Logcat中筛选当前类的日志信息
    private static final String TAG = "SetWallpaperActivity";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Activity创建时执行的生命周期方法
     * 整个壁纸设置的核心逻辑都在该方法中实现
     * @param savedInstanceState 保存的Activity状态，此处未使用
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 调用父类的onCreate方法，完成Activity初始化
        super.onCreate(savedInstanceState);
        // 打印控制台日志，标记onCreate方法开始执行
        logD("onCreate开始");
        
        // 从Intent中获取传递的壁纸类型参数（key为"wallpaper"）
        // 如果参数为空，默认设置为"fall"（秋季壁纸）
        String wallpaper = getIntent().getStringExtra("wallpaper");
        if (wallpaper == null) wallpaper = "fall";
        
        // 打印当前要设置的壁纸类型
        logD("设置壁纸类型: " + wallpaper);
        
        // 声明壁纸对应的Class对象，用于后续创建组件信息
        Class<?> wallpaperClass;
        
        // 根据壁纸类型字符串（统一转为小写）匹配对应的壁纸实现类
        switch (wallpaper.toLowerCase()) {
            case "grass":
                // 草地壁纸
                wallpaperClass = GrassWallpaper.class;
                break;
            case "galaxy":
                // 银河壁纸
                wallpaperClass = GalaxyWallpaper.class;
                break;
            case "nexus":
                // Nexus壁纸
                wallpaperClass = NexusWallpaper.class;
                break;
            case "wildworld":
                // 野生世界壁纸
                wallpaperClass = WildWorldWallpaper.class;
                break;
            case "fireworks":
                // 烟花壁纸
                wallpaperClass = FireworksWallpaper.class;
                break;
            case "nightsky":
                // 夜空壁纸
                wallpaperClass = NightSkyWallpaper.class;
                break;
            case "microbes":
                // 微生物壁纸
                wallpaperClass = MicrobesWallpaper.class;
                break;
            case "clock":
                // 极地时钟壁纸
                wallpaperClass = PolarClockWallpaper.class;
                break;
            default:
                // 默认使用秋季壁纸
                wallpaperClass = FallWallpaper.class;
                break;
        }
        
        try {
            // 打印即将设置的壁纸类全名
            logD("准备设置壁纸: " + wallpaperClass.getName());
            
            // 创建组件名（当前上下文 + 目标壁纸类），用于标识要设置的动态壁纸
            ComponentName component = new ComponentName(this, wallpaperClass);
            logD("组件: " + component);
            
            // 创建设置动态壁纸的系统Intent，使用系统指定的action
            Intent intent = new Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER");
            // 向Intent中添加动态壁纸组件的额外参数（系统规定的key）
            intent.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT", component);
            logD("发送CHANGE_LIVE_WALLPAPER");
            // 启动系统的壁纸设置流程
            startActivity(intent);
            
            // 打印壁纸设置成功的日志
            logD("壁纸设置完成");
        } catch (Exception e) {
            // 捕获设置壁纸过程中出现的所有异常
            // 打印错误日志（包含异常堆栈信息，便于调试）
            Log.e(TAG, "ERROR setting wallpaper: " + e.getMessage(), e);
        }
        
        // 打印日志标记onCreate方法执行完毕，准备结束Activity
        logD("onCreate结束");
        // 结束当前Activity，释放资源
        finish();
    }

    private static void logD(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}