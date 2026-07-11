package com.reandroid.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.reandroid.wallpaper.BuildConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Collects logcat output, device info, and app version into a text file,
 * then triggers a share intent so the user can email or save it.
 */
public final class DebugExporter {
    private static final String TAG = "DebugExporter";
    private static final int LOGCAT_MAX_LINES = 2000;

    private DebugExporter() {}

    /** Clear the logcat buffer so the next export only contains recent logs. */
    public static void clearLogcat(Context context) {
        try {
            Runtime.getRuntime().exec(new String[]{"logcat", "-c"});
            Toast.makeText(context, "Log buffer cleared", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Failed to clear logcat", e);
            Toast.makeText(context, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Collect debug logs and share via Intent.
     *
     * @return true if the share intent was launched successfully
     */
    public static boolean exportAndShare(Context context) {
        File file = collect(context);
        if (file == null) return false;

        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file);

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT,
                "RE Wallpapers Debug Log - " + BuildConfig.VERSION_NAME);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            context.startActivity(Intent.createChooser(intent, "Share debug log"));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch share intent", e);
            return false;
        }
    }

    private static File collect(Context context) {
        File dir = new File(context.getCacheDir(), "debug");
        if (!dir.exists() && !dir.mkdirs()) return null;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "re_wallpapers_debug_" + timestamp + ".txt");

        try (FileWriter writer = new FileWriter(file)) {
            writeHeader(writer, context);
            writer.write("\n--- LOGCAT (last " + LOGCAT_MAX_LINES + " lines) ---\n\n");
            writeLogcat(writer);
        } catch (Exception e) {
            Log.e(TAG, "Failed to collect debug logs", e);
            return null;
        }

        Log.i(TAG, "Debug log written to " + file.getAbsolutePath());
        return file;
    }

    private static void writeHeader(FileWriter writer, Context context) throws IOException {
        writer.write("=== RE Android Live Wallpapers - Debug Log ===\n");
        writer.write("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n");
        writer.write("App Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n");
        writer.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        writer.write("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n");
        writer.write("ABIs: " + ArraysToString(Build.SUPPORTED_ABIS) + "\n");

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        writer.write("Memory: " + (usedMemory / 1024 / 1024) + "MB used / "
                + (runtime.maxMemory() / 1024 / 1024) + "MB max\n");
    }

    private static void writeLogcat(FileWriter writer) throws IOException {
        Process process = null;
        BufferedReader reader = null;
        try {
            // -d = dump and exit; -v threadtime = include timestamp + thread info
            process = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-v", "threadtime"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < LOGCAT_MAX_LINES) {
                writer.write(line);
                writer.write('\n');
                count++;
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String ArraysToString(String[] arr) {
        if (arr == null || arr.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
