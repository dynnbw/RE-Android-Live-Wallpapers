package com.reandroid.wallpaper.bluesea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 三个设置（气泡数量 / 水母大小 / 水母密度）要真的落到模拟上。
 *
 * <p>背景：注入是按方法名反射找 {@code setPluginPrefs(SharedPreferences)}，而引擎与两处设置页
 * 预览注入的都是**宿主 GL 类**（info.json 的 previewClass 与 createScene 返回的都是 BlueSeaGL）。
 * 这个方法一度只写在内层 Scene 上、无人转发，注入静默跳过 —— 设置页能调能存，壁纸毫无反应。
 */
public class BlueSeaPrefsTest {

    private static final int WIDTH = 480;
    private static final int HEIGHT = 800;

    // ---- 注入契约 ----

    /**
     * 这里只能钉"方法存在且 public"——真的走一遍注入需要一个 GL 上下文，JVM 测试给不了。
     * 而这一条正是当初漏掉的一环：漏了它，注入只记一行 Log.i 就跳过，没有任何报错。
     */
    @Test
    public void theInjectionEntryPointIsOnTheGlClass() throws Exception {
        Method m = BlueSeaGL.class.getMethod("setPluginPrefs", SharedPreferences.class);
        assertTrue("setPluginPrefs must be public", Modifier.isPublic(m.getModifiers()));
    }

    // ---- 设置 → 模拟 ----

    /** 没注入过设置时，行为必须等于加设置之前（默认 100% 密度 / 40 个气泡 / 1.0 倍大小）。 */
    @Test
    public void withoutInjectionTheDefaultsAreTheOldBehaviour() {
        BlueSeaScene plain = new BlueSeaScene(WIDTH, HEIGHT);
        BlueSeaScene empty = new BlueSeaScene(WIDTH, HEIGHT);
        empty.setPluginPrefs(new FakePrefs());

        assertEquals("默认密度必须是 100%", plain.mJellies.length, empty.mJellies.length);
        assertEquals(40, plain.mParticles.length);
        assertEquals(40, empty.mParticles.length);
        assertEquals(1.0f, plain.mJellySizeScale, 0.0f);
    }

    /** 密度是等距抽取，所以每个档位的水母数可以精确算出来，不是"大概一半"。 */
    @Test
    public void densitySelectsAnEvenStrideOfTheFullTable() {
        int full = jelliesFor(100);
        assertEquals("50% 应为一整张表的一半", (full + 1) / 2, jelliesFor(50));
        assertEquals("25% 应为四分之一", (full + 3) / 4, jelliesFor(25));
        assertEquals("33% 应为三分之一", (full + 2) / 3, jelliesFor(33));
    }

    @Test
    public void densityIsMonotonicAndNeverEmpty() {
        assertTrue(jelliesFor(25) <= jelliesFor(50));
        assertTrue(jelliesFor(50) <= jelliesFor(100));
        assertEquals("超过滑杆上限按上限算", jelliesFor(100), jelliesFor(1000));
        assertTrue("1% 也至少要留一只", jelliesFor(1) >= 1);
    }

    @Test
    public void particleCountFollowsThePreference() {
        assertEquals(0, particlesFor(0));
        assertEquals(80, particlesFor(80));
    }

    @Test
    public void jellySizeIsAppliedAsAMultiplierOfEachJellyOwnSize() {
        assertEquals(1.6f, sizeScaleFor(160), 1e-5f);
        assertEquals(0.6f, sizeScaleFor(60), 1e-5f);
    }

    /**
     * 只改大小不该重建水母 —— 重建等于让它们重新出现。GL 层也据此决定要不要重装纹理，
     * 所以这里钉的是**数组identity**，不只是数量。
     */
    @Test
    public void aSizeOnlyChangeDoesNotRebuildTheJellies() {
        FakePrefs prefs = new FakePrefs().put(BlueSeaScene.PREFS_JELLY_SIZE, 160);
        BlueSeaScene scene = new BlueSeaScene(WIDTH, HEIGHT);
        scene.setPluginPrefs(prefs);
        Object before = scene.mJellies;

        scene.setPluginPrefs(prefs.put(BlueSeaScene.PREFS_JELLY_SIZE, 60));

        assertSame("大小不重建水母", before, scene.mJellies);
        assertEquals(0.6f, scene.mJellySizeScale, 1e-5f);
    }

    /** 密度变了要换数组 —— 这正是 GL 层重装纹理的判据。 */
    @Test
    public void aDensityChangeRebuildsTheJellies() {
        BlueSeaScene scene = new BlueSeaScene(WIDTH, HEIGHT);
        Object before = scene.mJellies;

        scene.setPluginPrefs(new FakePrefs().put(BlueSeaScene.PREFS_JELLY_DENSITY, 50));

        assertNotSame(before, scene.mJellies);
        assertEquals(jelliesFor(50), scene.mJellies.length);
    }

    /** 气泡数量为 0 是合法档位（滑杆下限），不能留 null 元素。 */
    @Test
    public void zeroParticlesIsAnEmptyArrayNotANullOne() {
        BlueSeaScene scene = new BlueSeaScene(WIDTH, HEIGHT);
        scene.setPluginPrefs(new FakePrefs().put(BlueSeaScene.PREFS_PARTICLE_COUNT, 0));
        assertEquals(0, scene.mParticles.length);
    }

    // ---- helpers ----

    private static int jelliesFor(int densityPercent) {
        BlueSeaScene scene = new BlueSeaScene(WIDTH, HEIGHT);
        scene.setPluginPrefs(new FakePrefs().put(BlueSeaScene.PREFS_JELLY_DENSITY, densityPercent));
        return scene.mJellies.length;
    }

    private static int particlesFor(int count) {
        BlueSeaScene scene = new BlueSeaScene(WIDTH, HEIGHT);
        scene.setPluginPrefs(new FakePrefs().put(BlueSeaScene.PREFS_PARTICLE_COUNT, count));
        return scene.mParticles.length;
    }

    private static float sizeScaleFor(int percent) {
        BlueSeaScene scene = new BlueSeaScene(WIDTH, HEIGHT);
        scene.setPluginPrefs(new FakePrefs().put(BlueSeaScene.PREFS_JELLY_SIZE, percent));
        return scene.mJellySizeScale;
    }

    /**
     * 只实现 Scene 真正会读的 {@code getInt}；其余方法一被调用就报错，好过悄悄返回假值。
     */
    private static final class FakePrefs implements SharedPreferences {

        private final Map<String, Integer> mInts = new HashMap<>();

        FakePrefs put(String key, int value) {
            mInts.put(key, value);
            return this;
        }

        @Override
        public int getInt(String key, int defValue) {
            Integer value = mInts.get(key);
            return value != null ? value : defValue;
        }

        @Override
        public Map<String, ?> getAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getString(String key, String defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLong(String key, long defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float getFloat(String key, float defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean contains(String key) {
            return mInts.containsKey(key);
        }

        @Override
        public Editor edit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
