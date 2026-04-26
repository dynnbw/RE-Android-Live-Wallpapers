package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import com.reandroid.wallpaper.R;
class Jellyfish2 extends Jellyfish {
    public Jellyfish2() {
    }

    public Jellyfish2(Context context) {
        super(context);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_b_s256x256_e_mip_0);
    }

    @Override // com.reandroid.wallpaper.deepsea.Jellyfish
    protected int getAlphaTextureId() {
        return GLHelper.getTexture(getContext(), R.drawable.unit_b_s256x256_e_mip_0_alpha);
    }
}
