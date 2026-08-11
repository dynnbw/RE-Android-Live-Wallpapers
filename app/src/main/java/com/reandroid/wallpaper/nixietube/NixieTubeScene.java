package com.reandroid.wallpaper.nixietube;

import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

/**
 * State machine for the 8-digit nixie tube display.
 *
 * Modes:
 *   TIME  — HH:MM:SS clock, updates every second
 *   CLICK — random digit flicker for 0.5s after tap
 *   AUDIO — VU meter (instantaneous dBFS)
 *
 * Transition: TIME ↔ ACTIVE based on audio threshold or tap.
 */
final class NixieTubeScene {
    private static final int TUBE_COUNT = 8;

    enum Mode { TIME, CLICK, AUDIO }

    // ---- Display buffer ----
    private final int[] mDisplay = new int[TUBE_COUNT];
    private final Random mRandom = new Random();

    // ---- Time mode ----
    private long mLastTimeSec = -1;

    // ---- Click mode ----
    private long mClickStartMs;
    private long mClickNextMs;
    private boolean mClickResultReady;
    private final int[] mClickResult = new int[TUBE_COUNT];
    private static final long CLICK_FLICKER_MS = 500;
    private static final long CLICK_HOLD_MS    = 3000;
    private static final long CLICK_TICK_MS    = 50;

    /** Weighted random digit: values near 0 have higher probability. */
    private int weightedDigit() {
        return (int) (Math.pow(mRandom.nextFloat(), 3.0) * 10);
    }

    /** Generate a click result: ones digit weighted toward 0, decimals uniform random. */
    private void generateClickResult() {
        int sign = mRandom.nextBoolean() ? 1 : -1;
        mClickResult[0] = weightedDigit();
        mClickResult[1] = sign >= 0 ? NixieTubeGL.FRAME_RD : NixieTubeGL.FRAME_LD;
        for (int i = 2; i < TUBE_COUNT; i++) {
            mClickResult[i] = mRandom.nextInt(10);
        }
    }

    // ---- Audio mode ----
    private volatile float mLevelDb = -60f;      // instantaneous dBFS for threshold detection
    private volatile float mEnvelopeDb = -60f;   // attack-release smoothed for VU display
    private static final float ENVELOPE_RELEASE = 0.5f; // per-frame decay (30Hz → ~100ms)
    private float mAudioThresholdDb = -30f;
    private boolean mAudioEnabled = true;
    private int mAudioSource = 0; // 0=system, 1=mic
    private volatile long mAudioSilenceMs;
    private static final long AUDIO_HOLD_MS = 2000;

    // ---- State ----
    private volatile Mode mMode = Mode.TIME;

    // ---- Prefs ----
    private SharedPreferences mPrefs;
    private NixieTubeAudioSource mAudioSourceObj;

    void setPluginPrefs(SharedPreferences prefs) {
        mPrefs = prefs;
        readPrefs();
        startAudio(); // restart to pick up audio source/enabled changes
    }

    private void readPrefs() {
        if (mPrefs == null) return;
        mAudioThresholdDb = mPrefs.getInt("nixie_threshold_db", -50);
        mAudioSource = Integer.parseInt(mPrefs.getString("nixie_audio_source", "0"));
        mAudioEnabled = mPrefs.getBoolean("nixie_audio_enabled", true);
    }

    void startAudio() {
        if (!mAudioEnabled) return;
        stopAudio(); // restart to pick up source changes or permission grants
        mAudioSourceObj = new NixieTubeAudioSource(this::onAudioLevel, mAudioSource);
        mAudioSourceObj.start();
    }

    void stopAudio() {
        if (mAudioSourceObj != null) {
            mAudioSourceObj.stop();
            mAudioSourceObj = null;
        }
    }

    // ---- Called every frame by GL thread ----
    void update(long timeMs) {
        switch (mMode) {
            case TIME:  updateTimeMode(timeMs);  break;
            case CLICK: updateClickMode(timeMs); break;
            case AUDIO: updateAudioMode(timeMs); break;
        }
    }

    int[] getDisplayValues() { return mDisplay; }

    void onTap() {
        mMode = Mode.CLICK;
        mClickStartMs = System.currentTimeMillis();
        mClickNextMs = 0;
        mClickResultReady = false;
        generateClickResult();
    }

    void onAudioLevel(float dbFS) {
        if (!mAudioEnabled) return;

        // Instantaneous level for threshold: fast switching
        mLevelDb = dbFS;

        // Attack-release envelope for display: fast attack, slow release
        if (dbFS > mEnvelopeDb) {
            mEnvelopeDb = dbFS;                      // instant attack
        } else {
            mEnvelopeDb = mEnvelopeDb * ENVELOPE_RELEASE
                        + dbFS * (1.0f - ENVELOPE_RELEASE); // slow release
        }

        if (dbFS > mAudioThresholdDb) {
            mAudioSilenceMs = 0;
            if (mMode != Mode.CLICK) {
                mMode = Mode.AUDIO;
            }
        }
    }

    void stop() {
        mMode = Mode.TIME;
    }

    // ---- Mode implementations ----

    private final Calendar mCalendar = Calendar.getInstance();

    private void updateTimeMode(long timeMs) {
        long sec = (timeMs / 1000) % 86400;
        // Adjust for local timezone offset from UTC
        int tzOffsetSec = TimeZone.getDefault().getOffset(timeMs) / 1000;
        sec = ((sec + tzOffsetSec) % 86400 + 86400) % 86400;
        if (sec == mLastTimeSec) return;
        mLastTimeSec = sec;

        int h = (int) (sec / 3600);
        int m = (int) ((sec % 3600) / 60);
        int s = (int) (sec % 60);

        mDisplay[0] = h / 10;
        mDisplay[1] = h % 10;
        mDisplay[2] = NixieTubeGL.FRAME_COLON;
        mDisplay[3] = m / 10;
        mDisplay[4] = m % 10;
        mDisplay[5] = NixieTubeGL.FRAME_COLON;
        mDisplay[6] = s / 10;
        mDisplay[7] = s % 10;
    }

    private void updateClickMode(long timeMs) {
        long elapsed = timeMs - mClickStartMs;

        if (elapsed < CLICK_FLICKER_MS) {
            if (timeMs < mClickNextMs) return;
            mClickNextMs = timeMs + CLICK_TICK_MS;
            for (int i = 0; i < TUBE_COUNT; i++) mDisplay[i] = mRandom.nextInt(10);
            return;
        }

        // Hold phase: show the pre-generated result
        if (!mClickResultReady) {
            System.arraycopy(mClickResult, 0, mDisplay, 0, TUBE_COUNT);
            mClickResultReady = true;
        }
        // When negative, ones digit flickers digit↔empty during hold
        if (mClickResult[1] == NixieTubeGL.FRAME_LD) {
            if (timeMs < mClickNextMs) return;
            mClickNextMs = timeMs + CLICK_TICK_MS;
            mDisplay[0] = (mDisplay[0] == NixieTubeGL.FRAME_EMPTY)
                    ? mClickResult[0] : NixieTubeGL.FRAME_EMPTY;
        }

        if (elapsed >= CLICK_FLICKER_MS + CLICK_HOLD_MS) {
            mMode = Mode.TIME;
            mLastTimeSec = -1;
        }
    }

    private void updateAudioMode(long timeMs) {
        // When the instantaneous level drops below threshold, start a silence timer.
        // Once silence persists for AUDIO_HOLD_MS, go back to clock display.
        if (mLevelDb <= mAudioThresholdDb) {
            if (mAudioSilenceMs == 0) {
                mAudioSilenceMs = timeMs;
            } else if (timeMs - mAudioSilenceMs > AUDIO_HOLD_MS) {
                mMode = Mode.TIME;
                mLastTimeSec = -1;
                return;
            }
        } else {
            mAudioSilenceMs = 0;
        }

        // Display as 60 - |dB|: 0 = silence, 60 = full scale. Always positive → use RD.
        float displayVal = 60.0f + mEnvelopeDb;  // mEnvelopeDb is negative, so this reduces
        if (displayVal < 0f) displayVal = 0f;
        if (displayVal > 99.99999f) displayVal = 99.99999f;
        int intPart = (int) displayVal;
        mDisplay[0] = (intPart / 10) % 10;
        mDisplay[1] = intPart % 10;
        mDisplay[2] = NixieTubeGL.FRAME_RD;
        float frac = displayVal - (float) intPart;
        for (int i = 3; i < TUBE_COUNT; i++) {
            frac *= 10;
            int d = (int) frac;
            mDisplay[i] = d % 10;
            frac -= d;
        }
    }
}
