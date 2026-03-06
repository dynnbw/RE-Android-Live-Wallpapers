package com.reandroid.wallpaper.fall;

import android.app.Activity;
import android.os.Bundle;

/**
 * 落叶壁纸主Activity
 * 作为FallView的容器，管理视图的生命周期
 */
public class Fall extends Activity {
    // 自定义的落叶视图实例
    private FallView mView;

    /**
     * Activity创建时调用
     * 初始化FallView并设置为ContentView
     * @param icicle 保存的实例状态
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // 创建FallView实例
        mView = new FallView(this);
        // 设置当前Activity的布局为FallView
        setContentView(mView);
    }

    /**
     * Activity恢复时调用
     * 通知FallView恢复动画/渲染
     */
    @Override
    protected void onResume() {
        super.onResume();
        mView.resume();
    }

    /**
     * Activity暂停时调用
     * 通知FallView暂停动画/渲染，并退出进程
     */
    @Override
    protected void onPause() {
        super.onPause();
        mView.pause();

        // 退出当前进程
        Runtime.getRuntime().exit(0);
    }
}