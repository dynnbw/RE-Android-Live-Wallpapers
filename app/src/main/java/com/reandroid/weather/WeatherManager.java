package com.reandroid.weather;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.reandroid.wallpaper.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WeatherManager {
    public interface Listener {
        void onWeatherUpdated(WeatherState state);
    }

    private static final String TAG = "WeatherManager";
    private static final String KEY_LAST_CONDITION = "last_condition";
    private static final String KEY_LAST_IS_NIGHT = "last_is_night";
    private static final String KEY_LAST_TEMP_MIN = "last_temp_min";
    private static final String KEY_LAST_TEMP_MAX = "last_temp_max";
    private static final String KEY_LAST_SUNRISE = "last_sunrise";
    private static final String KEY_LAST_SUNSET = "last_sunset";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final String KEY_LAST_LAT = "last_lat";
    private static final String KEY_LAST_LON = "last_lon";
    private static final String KEY_UPDATE_MINUTES = "weather_update_minutes";
    private static final String KEY_OVERRIDE_ACTIVE = "weather_override_active";
    private static final String KEY_OVERRIDE_CONDITION = "weather_override_condition";
    private static final String KEY_OVERRIDE_IS_NIGHT = "weather_override_is_night";
    private static final String KEY_OVERRIDE_UPDATE = "weather_override_update";

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final AtomicBoolean mRunning;
    private final Listener mListener;
    private final Handler mMainHandler;

    private volatile WeatherState mLastState;
    private volatile WeatherState mOverrideState;
    private ScheduledExecutorService mExecutor;
    private ScheduledFuture<?> mFuture;

    public WeatherManager(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mExecutor = createExecutor();
        mRunning = new AtomicBoolean(false);
        mListener = listener;
        mMainHandler = new Handler(Looper.getMainLooper());
        mOverrideState = loadManualOverrideFromPrefs();
    }

    private void dispatchWeatherUpdated(WeatherState state, Listener listener) {
        if (listener == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onWeatherUpdated(state);
        } else {
            mMainHandler.post(() -> listener.onWeatherUpdated(state));
        }
    }

    private ScheduledExecutorService createExecutor() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    public WeatherState getLastState() {
        return mOverrideState != null ? mOverrideState : mLastState;
    }

    public synchronized void setManualOverride(WeatherState overrideState) {
        if (overrideState == null) {
            clearManualOverride();
            return;
        }
        mOverrideState = overrideState;
        mPrefs.edit()
                .putBoolean(KEY_OVERRIDE_ACTIVE, true)
                .putInt(KEY_OVERRIDE_CONDITION, overrideState.condition.ordinal())
                .putBoolean(KEY_OVERRIDE_IS_NIGHT, overrideState.isNight)
                .putLong(KEY_OVERRIDE_UPDATE, overrideState.updateUtc)
                .apply();
        dispatchWeatherUpdated(getLastState(), mListener);
    }

    public synchronized void clearManualOverride() {
        mOverrideState = null;
        mPrefs.edit()
                .putBoolean(KEY_OVERRIDE_ACTIVE, false)
                .remove(KEY_OVERRIDE_CONDITION)
                .remove(KEY_OVERRIDE_IS_NIGHT)
                .remove(KEY_OVERRIDE_UPDATE)
                .apply();
        dispatchWeatherUpdated(getLastState(), mListener);
    }

    private WeatherState loadManualOverrideFromPrefs() {
        if (!mPrefs.getBoolean(KEY_OVERRIDE_ACTIVE, false)) {
            return null;
        }
        int ordinal = mPrefs.getInt(KEY_OVERRIDE_CONDITION, -1);
        if (ordinal < 0 || ordinal >= WeatherCondition.values().length) {
            return null;
        }
        boolean isNight = mPrefs.getBoolean(KEY_OVERRIDE_IS_NIGHT, false);
        long update = mPrefs.getLong(KEY_OVERRIDE_UPDATE, System.currentTimeMillis() / 1000L);
        return new WeatherState(WeatherCondition.values()[ordinal], isNight,
                0.0f, 0.0f, 0L, 0L, update);
    }

    public synchronized void start() {
        if (mRunning.getAndSet(true)) return;
        if (mExecutor == null || mExecutor.isShutdown() || mExecutor.isTerminated()) {
            mExecutor = createExecutor();
        }
        mLastState = loadStateFromPrefs();
        if (mLastState != null) {
            dispatchWeatherUpdated(getLastState(), mListener);
        }
        scheduleNext(0L);
    }

    public synchronized void stop() {
        mRunning.set(false);
        if (mFuture != null) {
            mFuture.cancel(true);
            mFuture = null;
        }
    }

    public synchronized void release() {
        stop();
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
    }

    public synchronized void refreshNow(Listener oneShotListener) {
        ScheduledExecutorService executor = mExecutor;
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            mExecutor = createExecutor();
            executor = mExecutor;
        }
        final ScheduledExecutorService finalExecutor = executor;
        try {
            finalExecutor.execute(() -> {
                WeatherState state = null;
                try {
                    state = fetchWeather();
                    if (state != null) {
                        mLastState = state;
                        saveStateToPrefs(state);
                        dispatchWeatherUpdated(getLastState(), mListener);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Manual weather refresh failed", e);
                } finally {
                    dispatchWeatherUpdated(getLastState(), oneShotListener);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Manual weather refresh rejected", e);
            if (oneShotListener != null) {
                dispatchWeatherUpdated(getLastState(), oneShotListener);
            }
        }
    }

    private void scheduleNext(long delayMs) {
        if (!mRunning.get()) return;
        ScheduledExecutorService executor = mExecutor;
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            Log.w(TAG, "scheduleNext skipped: executor unavailable");
            mRunning.set(false);
            return;
        }
        try {
            mFuture = executor.schedule(this::fetchAndSchedule, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "scheduleNext rejected", e);
            mRunning.set(false);
        }
    }

    private void fetchAndSchedule() {
        if (!mRunning.get()) return;
        long nextDelay = TimeUnit.MINUTES.toMillis(getUpdateMinutes());
        try {
            WeatherState state = fetchWeather();
            if (state != null) {
                mLastState = state;
                saveStateToPrefs(state);
                dispatchWeatherUpdated(getLastState(), mListener);
            }
        } catch (Exception e) {
            Log.w(TAG, "Weather fetch failed", e);
        } finally {
            scheduleNext(nextDelay);
        }
    }

    private int getUpdateMinutes() {
        try {
            String value = mPrefs.getString(KEY_UPDATE_MINUTES, "30");
            int minutes = Integer.parseInt(value);
            return Math.max(10, minutes);
        } catch (Exception e) {
            return 60;
        }
    }

    private WeatherState fetchWeather() throws IOException, JSONException {
        if (!isNetworkAvailable()) {
            return mLastState;
        }
        String apiKey = mPrefs.getString("openweather_api_key", "");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = mContext.getString(R.string.openweather_api_key);
        }
        if (apiKey == null || apiKey.trim().isEmpty()
            || apiKey.contains("YOUR_API_KEY")
            || apiKey.contains("YOUR_OPENWEATHER_API_KEY")) {
            Log.w(TAG, "Missing OpenWeather API key");
            return mLastState;
        }

        Location location = getBestLastKnownLocation();
        double lat;
        double lon;
        if (location != null) {
            lat = location.getLatitude();
            lon = location.getLongitude();
        } else {
            double[] stored = getStoredLatLon();
            if (stored == null) {
                return mLastState;
            }
            lat = stored[0];
            lon = stored[1];
        }
        mPrefs.edit().putString(KEY_LAST_LAT, String.format(Locale.US, "%.6f", lat))
                .putString(KEY_LAST_LON, String.format(Locale.US, "%.6f", lon))
                .apply();

        String lang = buildOpenWeatherLang();
        String urlStr = String.format(Locale.US,
                "https://api.openweathermap.org/data/2.5/weather?lat=%.6f&lon=%.6f&appid=%s&units=metric&lang=%s&mode=json",
                lat, lon, apiKey, lang);

        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (input == null) return mLastState;
            String json = readAll(input);
            if (json == null || json.isEmpty()) return mLastState;
            WeatherState state = parseWeather(json);
            return state != null ? state : mLastState;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private WeatherState parseWeather(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!isSuccessResponse(root)) {
            Log.w(TAG, "OpenWeather API error: " + root.optString("message", "unknown"));
            return null;
        }
        JSONArray weatherArr = root.optJSONArray("weather");
        int weatherId = 800;
        if (weatherArr != null && weatherArr.length() > 0) {
            weatherId = weatherArr.getJSONObject(0).optInt("id", 800);
        }

        JSONObject main = root.optJSONObject("main");
        float tempMin = main != null ? (float) main.optDouble("temp_min", 0.0) : 0.0f;
        float tempMax = main != null ? (float) main.optDouble("temp_max", 0.0) : 0.0f;

        JSONObject sys = root.optJSONObject("sys");
        long sunrise = sys != null ? sys.optLong("sunrise", 0L) : 0L;
        long sunset = sys != null ? sys.optLong("sunset", 0L) : 0L;
        long dt = root.optLong("dt", System.currentTimeMillis() / 1000L);
        int timezone = root.optInt("timezone", 0);

        boolean isNight = resolveIsNight(dt, sunrise, sunset, timezone);

        WeatherCondition condition = mapCondition(weatherId, tempMax, tempMin);
        return new WeatherState(condition, isNight, tempMin, tempMax, sunrise, sunset, dt);
    }

    private boolean isSuccessResponse(JSONObject root) {
        if (!root.has("cod")) {
            return true;
        }
        try {
            int code = root.optInt("cod", 200);
            if (code != 200) {
                return false;
            }
            return true;
        } catch (Exception ignored) {
            String code = root.optString("cod", "200");
            return "200".equals(code);
        }
    }

    private boolean resolveIsNight(long dtUtc, long sunriseUtc, long sunsetUtc, int timezoneSeconds) {
        long buffer = 30 * 60L;
        if (sunriseUtc > 0 && sunsetUtc > 0) {
            return dtUtc < (sunriseUtc - buffer) || dtUtc >= (sunsetUtc + buffer);
        }

        long localSeconds = dtUtc + timezoneSeconds;
        if (localSeconds <= 0) {
            localSeconds = System.currentTimeMillis() / 1000L;
        }
        int localMinutes = (int) ((localSeconds % 86400L) / 60L);
        int sunriseMinutes = 6 * 60;
        int sunsetMinutes = 18 * 60;
        int bufferMinutes = 30;
        return localMinutes < (sunriseMinutes - bufferMinutes)
                || localMinutes >= (sunsetMinutes + bufferMinutes);
    }

    private WeatherCondition mapCondition(int weatherId, float tempMax, float tempMin) {
        if (weatherId >= 200 && weatherId < 300) {
            return WeatherCondition.D6_THUNDERSTORMS;
        }
        if (weatherId == 511 || (weatherId >= 611 && weatherId <= 616)) {
            return WeatherCondition.D9_SLEET;
        }
        if (weatherId >= 300 && weatherId < 600) {
            return WeatherCondition.D5_RAIN_SHOWERS;
        }
        if (weatherId >= 600 && weatherId < 700) {
            return WeatherCondition.D7_FLURRIES_SNOW;
        }
        if (weatherId >= 700 && weatherId < 800) {
            return WeatherCondition.D4_FOG;
        }
        if (weatherId == 800) {
            if (isFreezing(tempMax, tempMin)) {
                return WeatherCondition.D8_ICE_COLD;
            }
            return WeatherCondition.D1_CLEAR;
        }
        if (weatherId == 801 || weatherId == 802) {
            if (isFreezing(tempMax, tempMin)) {
                return WeatherCondition.D8_ICE_COLD;
            }
            return WeatherCondition.D2_CLOUDY;
        }
        if (weatherId == 803 || weatherId == 804) {
            if (isFreezing(tempMax, tempMin)) {
                return WeatherCondition.D8_ICE_COLD;
            }
            return WeatherCondition.D3_DREARY;
        }
        return WeatherCondition.D2_CLOUDY;
    }

    private boolean isFreezing(float tempMax, float tempMin) {
        return tempMax <= 0.0f || tempMin <= 0.0f;
    }

    private String buildOpenWeatherLang() {
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        String country = locale.getCountry();
        if ("zh".equalsIgnoreCase(language)) {
            if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
                return "zh_tw";
            }
            return "zh_cn";
        }
        return language.toLowerCase(Locale.US);
    }

    private WeatherState loadStateFromPrefs() {
        int ordinal = mPrefs.getInt(KEY_LAST_CONDITION, WeatherCondition.D1_CLEAR.ordinal());
        WeatherCondition condition = WeatherCondition.D1_CLEAR;
        if (ordinal >= 0 && ordinal < WeatherCondition.values().length) {
            condition = WeatherCondition.values()[ordinal];
        }
        boolean isNight = mPrefs.getBoolean(KEY_LAST_IS_NIGHT, false);
        float tempMin = mPrefs.getFloat(KEY_LAST_TEMP_MIN, 0.0f);
        float tempMax = mPrefs.getFloat(KEY_LAST_TEMP_MAX, 0.0f);
        long sunrise = mPrefs.getLong(KEY_LAST_SUNRISE, 0L);
        long sunset = mPrefs.getLong(KEY_LAST_SUNSET, 0L);
        long update = mPrefs.getLong(KEY_LAST_UPDATE, 0L);
        return new WeatherState(condition, isNight, tempMin, tempMax, sunrise, sunset, update);
    }

    private void saveStateToPrefs(WeatherState state) {
        mPrefs.edit()
                .putInt(KEY_LAST_CONDITION, state.condition.ordinal())
                .putBoolean(KEY_LAST_IS_NIGHT, state.isNight)
                .putFloat(KEY_LAST_TEMP_MIN, state.tempMinC)
                .putFloat(KEY_LAST_TEMP_MAX, state.tempMaxC)
                .putLong(KEY_LAST_SUNRISE, state.sunriseUtc)
                .putLong(KEY_LAST_SUNSET, state.sunsetUtc)
                .putLong(KEY_LAST_UPDATE, state.updateUtc)
                .apply();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private Location getBestLastKnownLocation() {
        int fine = ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarse = ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (fine != android.content.pm.PackageManager.PERMISSION_GRANTED
                && coarse != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            return null;
        }
        LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            if (coarse == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                best = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (fine == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (gps != null && (best == null || gps.getAccuracy() <= best.getAccuracy())) {
                    best = gps;
                }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Location provider failed", e);
        }
        return best;
    }

    private double[] getStoredLatLon() {
        try {
            String latStr = mPrefs.getString(KEY_LAST_LAT, "");
            String lonStr = mPrefs.getString(KEY_LAST_LON, "");
            if (latStr == null || latStr.isEmpty() || lonStr == null || lonStr.isEmpty()) {
                return null;
            }
            double lat = Double.parseDouble(latStr);
            double lon = Double.parseDouble(lonStr);
            return new double[] { lat, lon };
        } catch (Exception e) {
            return null;
        }
    }

    private String readAll(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
