package com.reandroid.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 头像加载：内存 → 磁盘 → 网络。后台取，主线程回调。
 *
 * <p>磁盘那一层不是可省的：名单本身能从 SharedPreferences 立刻拿到，但头像若只存在于内存，
 * 冷启动又连不上时那一行就只剩名字。磁盘缓存让"上次看过的头像"照样出来。
 *
 * <p>失败就不回调 —— 调用方只是保留没有图标的行，不该为此报错或重试。
 */
public final class AvatarLoader {

    private static final String TAG = "AvatarLoader";

    private static final int MEMORY_BYTES = 4 * 1024 * 1024;
    private static final int TIMEOUT_MS = 8000;
    private static final String DIR = "avatars";

    private static final LruCache<String, Bitmap> MEMORY =
            new LruCache<String, Bitmap>(MEMORY_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AvatarLoader() {}

    /** 回调在主线程。加载不出来就不回调。 */
    public static void load(Context context, String url, Consumer<Bitmap> onLoaded) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Bitmap memory = MEMORY.get(url);
        if (memory != null) {
            onLoaded.accept(memory);
            return;
        }

        final Context app = context.getApplicationContext();
        final File file = diskFile(app, url);
        POOL.execute(() -> {
            Bitmap bitmap = decodeFile(file);
            if (bitmap == null) {
                bitmap = download(url, file);
            }
            if (bitmap == null) {
                return;
            }
            final Bitmap loaded = bitmap;
            MEMORY.put(url, loaded);
            MAIN.post(() -> onLoaded.accept(loaded));
        });
    }

    private static File diskFile(Context context, String url) {
        File dir = new File(context.getCacheDir(), DIR);
        return new File(dir, sha1(url) + ".png");
    }

    private static Bitmap decodeFile(File file) {
        if (!file.isFile()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    /** @return 解出的位图，失败为 null（不写坏文件） */
    private static Bitmap download(String url, File target) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "RE-Android-Live-Wallpapers");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "avatar HTTP " + conn.getResponseCode());
                return null;
            }
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                bytes = readAll(in);
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                Log.w(TAG, "avatar not decodable");
                return null;
            }
            writeCache(target, bytes);
            return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "avatar fetch failed", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 写盘失败无所谓 —— 内存里已经有了，下次再取便是。 */
    private static void writeCache(File target, byte[] bytes) {
        try {
            File dir = target.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
            }
        } catch (Exception e) {
            Log.w(TAG, "avatar cache write failed", e);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** URL → 文件名。用 SHA-1 而不是 hashCode：后者会碰撞，撞了就是错的头像。 */
    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上到不了（SHA-1/UTF-8 都必有）；退化成一次性的键，宁可重下
            return "u" + Integer.toHexString(value.hashCode());
        }
    }
}
