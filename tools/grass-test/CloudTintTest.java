/*
 * GrassWeatherSystem.cloudTint 的约束测试 —— 纯 JVM:
 *
 *   javac -d /tmp/tinttest \
 *         app/src/main/java/com/reandroid/weather/WeatherCondition.java \
 *         app/src/main/java/com/reandroid/wallpaper/grass/GrassWeatherSystem.java \
 *         tools/grass-test/CloudTintTest.java
 *   java -cp /tmp/tinttest com.reandroid.wallpaper.grass.CloudTintTest
 *
 * 这里要钉住的是**两种做错的方式**:
 *   1. 夜里染色接近 1 —— 等于没染，云在夜里还是白的（原版就是这样）；
 *   2. 夜里染色到 0 —— 纯黑的云在天空上是个洞。
 *
 * 更早还有一个做错的方式：靠降透明度让云"消失"（夜里 cloudAlpha 乘 0.30，星空直接透出来）。
 * 那条在渲染器里，本类看不到 —— 它靠 GrassWeatherRenderer 始终以 alpha=1.0 画云来保证，
 * 不是本测试的范围。
 */
package com.reandroid.wallpaper.grass;

public final class CloudTintTest {

    private static int failures = 0;

    public static void main(String[] args) {
        float[] night = {
                GrassWeatherSystem.NIGHT_CLOUD_R,
                GrassWeatherSystem.NIGHT_CLOUD_G,
                GrassWeatherSystem.NIGHT_CLOUD_B,
        };
        String[] names = {"R", "G", "B"};

        // ---- 1. 夜里必须真的暗，但不能是纯黑 ----
        for (int c = 0; c < 3; c++) {
            float v = night[c];
            System.out.println("夜间 " + names[c] + " = " + v);
            if (v <= 0.0f) {
                failures++;
                System.out.println("失败: " + names[c] + " 为 0 —— 纯黑的云在天空上是个洞");
            }
            if (v >= 0.5f) {
                failures++;
                System.out.println("失败: " + names[c] + " = " + v + " 太亮，夜里云还是白的");
            }
        }

        // ---- 2. 白天必须恰好是纯白（云贴图原样输出） ----
        for (int c = 0; c < 3; c++) {
            float v = GrassWeatherSystem.cloudTint(night[c], 1.0f);
            if (Math.abs(v - 1.0f) > 1.0E-6f) {
                failures++;
                System.out.println("失败: 白天 " + names[c] + " 应为 1，实际 " + v);
            }
        }

        // ---- 3. 深夜取夜间值 ----
        for (int c = 0; c < 3; c++) {
            float v = GrassWeatherSystem.cloudTint(night[c], 0.0f);
            if (Math.abs(v - night[c]) > 1.0E-6f) {
                failures++;
                System.out.println("失败: 深夜 " + names[c] + " 应为 " + night[c] + "，实际 " + v);
            }
        }

        // ---- 4. 对白天权重单调不减，且始终落在 [夜间值, 1] ----
        for (int c = 0; c < 3; c++) {
            float prev = -1.0f;
            for (int i = 0; i <= 100; i++) {
                float t = i / 100.0f;
                float v = GrassWeatherSystem.cloudTint(night[c], t);
                if (v < prev) {
                    failures++;
                    System.out.println("失败: " + names[c] + " 在 dayWeight=" + t + " 处回落");
                    break;
                }
                if (v < night[c] - 1.0E-6f || v > 1.0f + 1.0E-6f) {
                    failures++;
                    System.out.println("失败: " + names[c] + " 越界 " + v + " @" + t);
                    break;
                }
                prev = v;
            }
        }

        // ---- 5. 权重越界要被夹住，不能外插 ----
        if (Math.abs(GrassWeatherSystem.cloudTint(0.1f, -5.0f) - 0.1f) > 1.0E-6f) {
            failures++;
            System.out.println("失败: dayWeight < 0 应夹到深夜值");
        }
        if (Math.abs(GrassWeatherSystem.cloudTint(0.1f, 5.0f) - 1.0f) > 1.0E-6f) {
            failures++;
            System.out.println("失败: dayWeight > 1 应夹到纯白");
        }

        System.out.println(failures == 0 ? "全部通过" : failures + " 个用例失败");
        if (failures != 0) System.exit(1);
    }
}
