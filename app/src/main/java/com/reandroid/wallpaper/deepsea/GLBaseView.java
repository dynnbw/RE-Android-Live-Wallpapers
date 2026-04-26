package com.reandroid.wallpaper.deepsea;

import android.content.Context;

class GLBaseView {
    private Context mContext;
    private boolean mIsInitShader = false;

    public GLBaseView() {
    }

    public GLBaseView(Context context) {
        this.mContext = context;
    }

    public void remove() {
        this.mContext = null;
    }

    public void initByteBuffer() {
    }

    public void initShader() {
        this.mIsInitShader = true;
    }

    public void setForDrawing() {
    }

    public void draw() {
    }

    public void update(float[] fArr) {
    }

    public boolean isInitShader() {
        return this.mIsInitShader;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public Context getContext() {
        return this.mContext;
    }
}
