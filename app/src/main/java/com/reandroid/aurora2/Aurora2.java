package com.reandroid.aurora2;

import android.app.Activity;
import android.os.Bundle;

public class Aurora2 extends Activity {
    private Aurora2View mView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mView = new Aurora2View(this);
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
