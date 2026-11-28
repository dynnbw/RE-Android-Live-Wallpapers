package com.reandroid.wallpaper.grass;

/**
 * 从这一帧**画出去的那批萤火虫**里取光源，并（限频）重算逐叶遮挡。
 *
 * <p>两条渲染路径共用这一份。光源必须与画面上的萤火虫同源 —— 另算一份就又多了
 * 一个可能对不上的地方（见 {@code GrassGL.prepareFireflyLights} 的原始说明）。
 *
 * <p>传统萤火虫按"是否正在闪光"把精灵劈成互斥的两批，所以两批都要收：
 * 只收本体的话，萤火虫最亮的那一刻恰好从批里消失，地上的光反而灭了。
 */
final class GrassFireflyLighting {

    /** 每只光源 3 个 float：x, y, 亮度。与着色器的 {@code uFirefly[]} 同布局。 */
    final float[] lights = new float[GrassConstants.FIREFLY_LIGHT_MAX * 3];

    int lightCount;

    /** 把被夜色压过的叶片颜色还原成本色的增益，见 {@link GrassRenderDataBuilder#fireflyAlbedoBoost}。 */
    float albedoBoost;

    private long mLastShadowMs;

    /**
     * @param body         本体精灵批
     * @param bodyFloats   本体批的 float 数（不是顶点数）
     * @param flare        闪光精灵批
     * @param flareFloats  闪光批的 float 数
     * @param nowMs        当前时刻（用于遮挡限频；调用方传 SystemClock.uptimeMillis）
     */
    void update(
            SceneData sd,
            float[] body,
            int bodyFloats,
            float[] flare,
            int flareFloats,
            long nowMs) {
        lightCount = 0;
        albedoBoost = 0.0f;
        if (sd == null || !sd.fireflyLightEnabled) {
            // 关着时着色器那一行是恒等（逐位不变）；数组清零，免得 VK 那边把上一帧的
            // 光源又传一遍（GLES 那边由 gain=0 直接挡住）
            java.util.Arrays.fill(lights, 0.0f);
            return;
        }

        int count = GrassFireflyLight.clear(lights);
        count = GrassFireflyLight.append(body, bodyFloats, lights, count);
        count = GrassFireflyLight.append(flare, flareFloats, lights, count);
        lightCount = count;
        albedoBoost = GrassRenderDataBuilder.fireflyAlbedoBoost(sd);

        updateShadow(sd, nowMs);
    }

    /**
     * 逐叶的萤火虫遮挡（近处的草挡住远处草的光）。
     *
     * <p>**限频**：遮挡是 O(叶数²) 的包围盒测试，而它会变的原因只有"萤火虫在动" ——
     * 它们每秒只走十几个像素，250 毫秒里挪不到 3 个像素，逐帧重算纯属浪费。
     */
    private void updateShadow(SceneData sd, long nowMs) {
        Blade[] blades = sd.blades;
        if (blades == null || blades.length == 0) {
            return;
        }
        if (nowMs - mLastShadowMs < GrassBladeLighting.OCCLUSION_INTERVAL_MS
                && sd.bladeFireflyShadow != null
                && sd.bladeFireflyShadow.length == blades.length) {
            return;
        }
        mLastShadowMs = nowMs;

        if (sd.bladeFireflyShadow == null || sd.bladeFireflyShadow.length != blades.length) {
            sd.bladeFireflyShadow = new float[blades.length];
        }
        GrassFireflyShadow.compute(
                blades,
                lights,
                lightCount,
                GrassConstants.FIREFLY_LIGHT_RADIUS,
                sd.bladeFireflyShadow);
        // 顶点数组是"外观没变就不重建"的，得让它知道阴影换了一版
        sd.fireflyShadowVersion++;
    }
}
