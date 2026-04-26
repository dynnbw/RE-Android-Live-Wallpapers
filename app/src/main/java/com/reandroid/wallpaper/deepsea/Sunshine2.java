package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import com.reandroid.wallpaper.R;
class Sunshine2 extends Sunshine {
    public Sunshine2() {
    }

    public Sunshine2(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Sunshine
    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.light_animation_0_s512x512_opt);
    }
}
