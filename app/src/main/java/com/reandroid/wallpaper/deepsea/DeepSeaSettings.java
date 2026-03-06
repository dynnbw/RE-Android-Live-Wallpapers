package com.reandroid.wallpaper.deepsea;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.File;

public final class DeepSeaSettings {
    private static boolean mImageIsSaved = false;
    private static float mWidthScale = 0.5f;
    private static float mHeightScale = 0.9f;

    private DeepSeaSettings() {
    }

    public static Bitmap loadBitmap(String str) {
        Bitmap bitmap = null;
        String str2 = str + "/deepseabackground.jpg";
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(str2, options);
            if (options.mCancel || options.outWidth == -1 || options.outHeight == -1) {
                Log.e("DeepSea", "Background read error");
                mImageIsSaved = false;
            } else {
                options.inSampleSize = computeSampleSize(options);
                options.inJustDecodeBounds = false;
                options.inDither = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bitmap = BitmapFactory.decodeFile(str2, options);
                mImageIsSaved = bitmap != null;
            }
        } catch (Exception e) {
            Log.e("DeepSea", "Background decoding error");
            mImageIsSaved = false;
        }
        return bitmap;
    }

    static int computeSampleSize(BitmapFactory.Options options) {
        int computeInitialSampleSize = computeInitialSampleSize(options, 1024, 1048576);
        if (computeInitialSampleSize <= 8) {
            int i = 1;
            while (i < computeInitialSampleSize) {
                i <<= 1;
            }
            return i;
        }
        return ((computeInitialSampleSize + 7) / 8) * 8;
    }

    static int computeInitialSampleSize(BitmapFactory.Options options, int i, int i2) {
        int i3 = options.outWidth;
        int i4 = options.outHeight;
        int ceil = (int) Math.ceil(Math.sqrt((i3 * i4) / i2));
        int min = Math.min(i3 / i, i4 / i);
        return min < ceil ? ceil : min;
    }

    public static boolean deleteBitmap(String str) {
        File file = new File(str, "deepseabackground.jpg");
        mImageIsSaved = false;
        return file.delete();
    }

    public static void setScale(int i, int i2) {
        if (i2 > i) {
            mWidthScale = ((float) i / (float) i2) * 0.9f;
            mHeightScale = 0.9f;
        } else if (i > i2) {
            mWidthScale = 0.9f;
            mHeightScale = (float) i2 / (float) i;
        } else {
            mHeightScale = 0.9f;
            mWidthScale = 0.9f;
        }
    }
}

