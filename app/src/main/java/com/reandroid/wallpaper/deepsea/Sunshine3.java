package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import com.reandroid.wallpaper.R;
class Sunshine3 extends Sunshine {
    public Sunshine3() {
    }

    public Sunshine3(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Sunshine
    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.light_animation_1_s512x512_opt);
    }
}
