package com.reandroid.wallpaper.aurora1;

import android.app.Activity;
import android.os.Bundle;

public class Aurora1 extends Activity {
    private Aurora1View mView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mView = new Aurora1View(this);
        setContentView(mView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mView.pause();
        finish();
    }
}