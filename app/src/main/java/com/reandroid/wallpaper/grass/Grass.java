package com.reandroid.wallpaper.grass;

import android.app.Activity;
import android.os.Bundle;

public class Grass extends Activity {
    private GrassView mView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mView = new GrassView(this);
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

        Runtime.getRuntime().exit(0);
    }
}