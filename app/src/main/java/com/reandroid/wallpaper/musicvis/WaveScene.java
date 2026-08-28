package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import com.reandroid.utils.Mat4;
import android.content.SharedPreferences;

import java.util.Random;

/**
 * Pure-logic waveform scene for vis2 (PCM) and vis3 (FFT).
 * Contains ALL data processing, idle/fade, buffer building, and MVP computation.
 * Has ZERO GL imports.
 */
public final class WaveScene extends AudioVisBase {
    public enum Mode { PCM, FFT }

    static final int LINE_COUNT = 1024;
    private static final int FADEOUT_LENGTH = 100;
    private static final float FADEOUT_FACTOR = 0.95f;
    private static final int FADEIN_LENGTH = 15;

    final Mode mMode;

    // Wave data — GL renderer reads these arrays after update
    final float[] mPointData = new float[LINE_COUNT * 8];
    final float[] mPositions = new float[LINE_COUNT * 2 * 2];
    final float[] mTexCoords = new float[LINE_COUNT * 2 * 2];
    final float[] mAdjustData = new float[LINE_COUNT * 2 * 3];

    // Matrices
    final float[] mProj = new float[16];
    final float[] mModel = new float[16];
    final float[] mMvp = new float[16];

    // Idle / fade state
    int mIdle = 0;
    int mWaveCounter = 0;
    float mYRotation = 0f;

    int fadeoutcounter = 0;
    int fadeincounter = 0;
    int wave1pos = 0, wave1amp = 0;
    int wave2pos = 0, wave2amp = 0;
    int wave3pos = 0, wave3amp = 0;
    int wave4pos = 0, wave4amp = 0;
    final float[] idleWave = new float[8192];
    int lastWaveCounter = 0;

    // ---- idle mode ----
    private static final int IDLE_WAVE = 0;
    private static final int IDLE_SIMULATE = 1;
    private static final int IDLE_FLAT = 2;
    int mIdleMode = IDLE_WAVE;

    // Synthetic-data state (idle modes simulate/flat)
    private static final int VOICE_COUNT = 14;  // PCM voices
    private static final int NOTE_POOL = 12;    // FFT concurrent note bumps (wide bursts + narrow notes)
    private final Random mSynthRand = new Random();
    private int[] mSynthData;        // scene-owned scratch; aliased by mVizData while idle-simulating
    private float mPcmFrame = 0f;    // PCM: global frame counter
    private float mPcmEnergy = 0.9f; // PCM: global energy (slow random walk)
    private float mPcmEnergyTarget = 0.9f;
    private int mPcmEnergyTimer = 0;
    private float mPcmBeatEnv = 0f;  // PCM: beat envelope 0..1
    private int mPcmBeatTimer = 0;
    private float[] mVoicePhase;     // PCM: per-voice phase (radians)
    private float[] mVoiceAmp;       // PCM: per-voice amplitude
    private float[] mVoiceAmpTarget; // PCM: per-voice random-walk target
    private float[] mVoiceFreq;      // PCM: per-voice phase step per sample
    private float mFftFrame = 0f;    // FFT: global frame counter
    private float mFftEnergy = 0.8f; // FFT: global energy (slow random walk)
    private float mFftEnergyTarget = 0.8f;
    private int mFftEnergyTimer = 0;
    private float mFftBeatEnv = 0f;  // FFT: beat envelope 0..1
    private int mFftBeatTimer = 0;
    private float[] mBinDrift;       // FFT: per-bin independent drift (-1..1)
    private int mPcmRetuneTimer = 0; // PCM: frames until next voice retune
    private int[] mNoteBin;          // FFT: note pool — bin center
    private float[] mNoteAmp;        // FFT: note pool — amplitude (0 = free)
    private float[] mNoteWidth;      // FFT: note pool — half-width in bins
    private float[] mNoteDecay;      // FFT: note pool — decay per frame
    private float[] mSynthPhase;     // FFT: per-bin rotating phase (radians)

    // ---- constructors ----

    WaveScene(int width, int height, Mode mode, Context context) {
        super(width, height, context);
        mMode = mode;
        initPointData();
    }

    // ---- AudioVisBase ----

    @Override
    protected int getAudioType() {
        return (mMode == Mode.FFT) ? AudioCapture.TYPE_FFT : AudioCapture.TYPE_PCM;
    }

    @Override
    protected int getAudioCaptureSize() {
        return (mMode == Mode.FFT) ? mFftSize : 1024;
    }

    @Override
    public void start() {
        SharedPreferences p;
        if (mPluginPrefs != null) {
            p = mPluginPrefs;
        } else {
            String pn = (mMode == Mode.PCM) ? "musicvis2_prefs" : "musicvis3_prefs";
            p = mContext.getSharedPreferences(pn, Context.MODE_PRIVATE);
        }
        readPrefs(p);

        int type = getAudioType();
        int size = getAudioCaptureSize();
        if (mAudioCapture == null || mAudioCapture.getSize() != size) {
            if (mAudioCapture != null) mAudioCapture.release();
            mAudioCapture = new AudioCapture(type, size);
            int capSize = mAudioCapture.getSize();
            mVizData = new int[capSize];
            mAnalyzer = new int[capSize / 2];
            mPcmSmoothed = new float[capSize];
        }
        mAudioCapture.start();
    }

    @Override
    public void stop() {
        if (mAudioCapture != null) {
            mAudioCapture.stop();
        }
    }

    @Override
    public void setPluginPrefs(SharedPreferences prefs) {
        int oldFftSize = mFftSize;
        super.setPluginPrefs(prefs);
        if (mFftSize != oldFftSize) {
            mFftSizeChanged = true;
        }
    }

    @Override
    protected void readPrefs(SharedPreferences p) {
        super.readPrefs(p);
        String v = p.getString("musicvis_idle_mode", "wave");
        if ("simulate".equals(v)) mIdleMode = IDLE_SIMULATE;
        else if ("flat".equals(v)) mIdleMode = IDLE_FLAT;
        else mIdleMode = IDLE_WAVE;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mYRotation = (xOffset * 4f) * (mMode == Mode.FFT ? 360f : 180f);
    }

    // ---- point data init ----

    void initPointData() {
        int outlen = mPointData.length / 8;
        int half = outlen / 2;
        for (int i = 0; i < outlen; i++) {
            mPointData[i * 8] = i - half;          // start X
            mPointData[i * 8 + 2] = 0f;           // start S
            mPointData[i * 8 + 3] = 0f;           // start T
            mPointData[i * 8 + 4] = i - half;      // end X
            mPointData[i * 8 + 6] = 1.0f;          // end S
            mPointData[i * 8 + 7] = 0f;            // end T
        }
    }

    // ---- wave data update ----

    void updateWaveData() {
        int len = 0;
        if (mAudioCapture != null) {
            if (mMode == Mode.PCM) {
                mVizData = mAudioCapture.getFormattedData(1, 1);
                len = mVizData.length;
            } else {
                mVizData = mAudioCapture.getFormattedData(1, 1);
                len = mVizData.length / 2;
            }
        }

        if (len == 0) {
            if (mIdleMode != IDLE_WAVE) {
                // simulate/flat: synthesize data and fall through to the normal
                // PCM/FFT branches so smoothing, analyzer falloff, and log mapping
                // behave exactly as with real audio.
                mIdle = 0;
                len = synthesizeData();
                if (len == 0) return;
            } else {
                if (mIdle == 0) mIdle = 1;
                return;
            }
        }

        if (mMode == Mode.PCM) {
            int outlen = mPointData.length / 8;
            if (len > outlen) len = outlen;
            if (mIdle != 0) mIdle = 0;
            float alpha = 0.3f;
            for (int i = 0; i < len; i++) {
                float smoothed = alpha * mVizData[i] + (1f - alpha) * mPcmSmoothed[i];
                mPcmSmoothed[i] = smoothed;
                mPointData[i * 8 + 1] = smoothed;
                mPointData[i * 8 + 5] = -smoothed;
            }
        } else {
            // FFT mode
            len = len / 2; // bins are in pairs
            if (len > mAnalyzer.length) len = mAnalyzer.length;
            if (mIdle != 0) mIdle = 0;

            for (int i = 1; i < len - 1; i++) {
                int val1 = mVizData[i * 2];
                int val2 = mVizData[i * 2 + 1];
                int val = val1 * val1 + val2 * val2;
                int newval = val * (i / 16 + 1);
                int oldval = mAnalyzer[i];
                if (newval < oldval - 800) {
                    newval = oldval - 800;
                }
                mAnalyzer[i] = newval;
            }

            int outlen = mPointData.length / 8;
            int width = Math.min(mWidth, outlen);
            int skip = (outlen - width) / 2;

            // Logarithmic bin-to-bar mapping
            for (int i = 0; i < width; i++) {
                double frac = (double) i / width;
                int binIdx = (int) (Math.pow(frac, 1.5) * (len - 1));
                if (binIdx < 0) binIdx = 0;
                if (binIdx >= len) binIdx = len - 1;
                float val = mAnalyzer[binIdx] / 8f;
                if (val < 1f && val > -1f) val = 1f;
                int idx = (i + skip) * 8;
                mPointData[idx + 1] = val;
                mPointData[idx + 5] = -val;
            }
        }
        mWaveCounter++;
        if (mRecolorDynamic && mRecolorEnabled && len > 0) {
            updateDynamicHue();
        }
    }

    /**
     * Fills mVizData with synthetic music-like data and returns len in the same
     * units as the getFormattedData() path: PCM = sample count (capSize),
     * FFT = half the array length (matching the halving done before the
     * {@code len == 0} check). Writes into scene-owned scratch (never the
     * capture thread's buffer) and aliases mVizData onto it.
     * <p>
     * The synthesis mimics real music rather than pure tones: PCM is a dense
     * noise + multi-voice wave with slow energy drift and beats; FFT is a pool
     * of independently decaying spectral notes over a jagged bass-heavy floor.
     */
    int synthesizeData() {
        int expected = (mAudioCapture != null) ? mAudioCapture.getSize() : getAudioCaptureSize();
        if (expected <= 0) return 0;

        if (mSynthData == null || mSynthData.length != expected) {
            mSynthData = new int[expected];
            if (mMode == Mode.FFT) {
                int bins = expected / 2;
                mSynthPhase = new float[bins];
                mNoteBin = new int[NOTE_POOL];
                mNoteAmp = new float[NOTE_POOL];
                mNoteWidth = new float[NOTE_POOL];
                mNoteDecay = new float[NOTE_POOL];
                mFftFrame = 0f;
            }
        }
        mVizData = mSynthData;

        if (mIdleMode == IDLE_FLAT) {
            java.util.Arrays.fill(mSynthData, 0);
            return (mMode == Mode.PCM) ? expected : expected / 2;
        }
        return (mMode == Mode.PCM) ? synthesizePcm(expected) : synthesizeFft(expected);
    }

    /** Music-like synthetic PCM: dense noise texture + drifting voices + beats. */
    private int synthesizePcm(int expected) {
        mPcmFrame += 1f;
        if (mVoicePhase == null) {
            mVoicePhase = new float[VOICE_COUNT];
            mVoiceAmp = new float[VOICE_COUNT];
            mVoiceAmpTarget = new float[VOICE_COUNT];
            mVoiceFreq = new float[VOICE_COUNT];
            for (int k = 0; k < VOICE_COUNT; k++) {
                // bass-heavy spread of phase steps (cycles per sample)
                mVoiceFreq[k] = 0.002f + mSynthRand.nextFloat() * mSynthRand.nextFloat() * 0.15f;
                mVoiceAmp[k] = mVoiceAmpTarget[k] = 10f + mSynthRand.nextFloat() * 20f;
            }
        }
        // retune some voices every ~5-10s — changes the wave character over time
        if (--mPcmRetuneTimer <= 0) {
            mPcmRetuneTimer = 300 + mSynthRand.nextInt(300);
            for (int k = 0; k < VOICE_COUNT; k++) {
                if (mSynthRand.nextFloat() < 0.6f) {
                    mVoiceFreq[k] = 0.002f + mSynthRand.nextFloat() * mSynthRand.nextFloat() * 0.15f;
                }
            }
        }
        // global energy random walk (phrase-level dynamics)
        if (--mPcmEnergyTimer <= 0) {
            mPcmEnergyTimer = 80 + mSynthRand.nextInt(120);
            mPcmEnergyTarget = 0.6f + mSynthRand.nextFloat() * 0.9f;
        }
        mPcmEnergy += (mPcmEnergyTarget - mPcmEnergy) * 0.05f;
        // beat pulse
        if (--mPcmBeatTimer <= 0) {
            mPcmBeatTimer = 55 + mSynthRand.nextInt(130);
            mPcmBeatEnv = 1f;
        }
        mPcmBeatEnv *= 0.9f;
        // voice amplitude drift
        for (int k = 0; k < VOICE_COUNT; k++) {
            if (mSynthRand.nextFloat() < 0.08f) {
                mVoiceAmpTarget[k] = 8f + mSynthRand.nextFloat() * 26f;
            }
            mVoiceAmp[k] += (mVoiceAmpTarget[k] - mVoiceAmp[k]) * 0.03f;
        }

        float env = (0.4f + 0.6f * mPcmEnergy) * (1f + 0.7f * mPcmBeatEnv);
        float frame = mPcmFrame;
        for (int i = 0; i < expected; i++) {
            // dense deterministic noise — the "audio texture" that reads as real
            float n = (float) Math.sin(i * 12.9898 + frame * 1.31) * 43758.5453f;
            n = n - (float) Math.floor(n);           // 0..1 hash
            float v = (n * 2f - 1f) * 24f * env;
            float vsum = 0f;
            for (int k = 0; k < VOICE_COUNT; k++) {
                mVoicePhase[k] += mVoiceFreq[k];
                vsum += mVoiceAmp[k] * (float) Math.sin(mVoicePhase[k]);
            }
            v += vsum * env;
            v += mPcmBeatEnv * 70f * (float) Math.sin(i * 0.024f + frame * 0.7f); // bass drum
            // traveling amplitude "loud spot" — breaks the uniform texture
            float spat = 0.62f + 0.38f * (0.6f * (float) Math.sin(i * 0.0031f + frame * 0.033f)
                    + 0.4f * (float) Math.sin(i * 0.0077f - frame * 0.021f));
            v *= spat;
            int s = (int) v;
            if (s > 127) s = 127;
            if (s < -127) s = -127;
            mSynthData[i] = s;
        }
        return expected;
    }

    /**
     * Music-like synthetic FFT: wide-band beat bursts (12-32% of the spectrum,
     * long sustain) + narrow notes, over a calm floor where each bar drifts
     * independently — no lockstep motion.
     */
    private int synthesizeFft(int expected) {
        int bins = expected / 2;
        mFftFrame += 1f;
        if (mBinDrift == null || mBinDrift.length != bins) {
            mBinDrift = new float[bins];
            for (int i = 0; i < bins; i++) mBinDrift[i] = mSynthRand.nextFloat() * 2f - 1f;
        }
        // global energy random walk (weak — the floor stays calm between bursts)
        if (--mFftEnergyTimer <= 0) {
            mFftEnergyTimer = 120 + mSynthRand.nextInt(160);
            mFftEnergyTarget = 0.8f + mSynthRand.nextFloat() * 0.5f;
        }
        mFftEnergy += (mFftEnergyTarget - mFftEnergy) * 0.02f;
        // beat pulse — long sustain so bursts read as music, not blips
        if (--mFftBeatTimer <= 0) {
            mFftBeatTimer = 55 + mSynthRand.nextInt(160);
            mFftBeatEnv = 1f;
        }
        mFftBeatEnv *= 0.935f;

        // on the beat's first frames: 2-3 WIDE-band bursts
        if (mFftBeatEnv > 0.92f) {
            int bursts = 2 + (mSynthRand.nextFloat() < 0.5f ? 1 : 0);
            for (int s = 0; s < bursts; s++) {
                int slot = freeNoteSlot();
                if (slot < 0) break;
                int bin = 1 + (int) (Math.pow(mSynthRand.nextFloat(), 1.4f) * bins * 0.6f);
                if (bin >= bins) bin = bins - 1;
                mNoteBin[slot] = bin;
                mNoteAmp[slot] = 40f + mSynthRand.nextFloat() * 70f;
                mNoteWidth[slot] = bins * (0.12f + mSynthRand.nextFloat() * 0.2f);
                mNoteDecay[slot] = 0.93f + mSynthRand.nextFloat() * 0.03f;
            }
        }
        // narrow notes in between — the individual moving peaks
        if (mSynthRand.nextFloat() < 0.8f) spawnNarrowNote(bins);
        if (mSynthRand.nextFloat() < 0.3f) spawnNarrowNote(bins);

        // decay pool
        for (int k = 0; k < NOTE_POOL; k++) {
            if (mNoteAmp[k] >= 1f) {
                mNoteAmp[k] *= mNoteDecay[k];
                if (mNoteAmp[k] < 1f) mNoteAmp[k] = 0f;
            }
        }
        // per-bin synthesis; indices 0/1 (DC/Nyquist) are never read by the analyzer
        for (int i = 1; i < bins; i++) {
            // each bar drifts independently — no lockstep floor
            mBinDrift[i] += (mSynthRand.nextFloat() * 2f - 1f) * 0.14f - mBinDrift[i] * 0.03f;
            float f = (float) i / bins;
            // quadratic floor so the (i/16+1) gain doesn't inflate high bins into
            // a visible resting bar line; rests at ~1-4 units (near-invisible)
            float mag = (4.2f * (1f - f) * (1f - f) + 0.8f) * (1f + 0.25f * mBinDrift[i]) * mFftEnergy;
            if (f < 0.3f) {
                mag += mFftBeatEnv * 20f * (1f - f / 0.3f);  // kick region pulses with the beat
            }
            for (int k = 0; k < NOTE_POOL; k++) {
                if (mNoteAmp[k] < 1f) continue;
                float d = Math.abs(i - mNoteBin[k]);
                if (d < mNoteWidth[k]) {
                    mag += mNoteAmp[k] * (1f - d / mNoteWidth[k]);
                }
            }
            if (mag > 127f) mag = 127f;
            mSynthPhase[i] += 0.02f + 0.05f * f + 0.03f * (float) Math.sin(mFftFrame * 0.001f + i);
            int re = (int) (mag * Math.cos(mSynthPhase[i]));
            int im = (int) (mag * Math.sin(mSynthPhase[i]));
            if (re > 127) re = 127; else if (re < -127) re = -127;
            if (im > 127) im = 127; else if (im < -127) im = -127;
            mSynthData[i * 2] = re;
            mSynthData[i * 2 + 1] = im;
        }
        return expected / 2;
    }

    /** @return a free note-pool slot, or -1 if the pool is full */
    private int freeNoteSlot() {
        for (int k = 0; k < NOTE_POOL; k++) {
            if (mNoteAmp[k] < 1f) return k;
        }
        return -1;
    }

    /** Spawn a narrow (2-6 bin) note, bass-weighted half the time. */
    private void spawnNarrowNote(int bins) {
        int slot = freeNoteSlot();
        if (slot < 0) return;
        int bin;
        if (mSynthRand.nextFloat() < 0.5f) {
            bin = 1 + (int) (Math.pow(mSynthRand.nextFloat(), 1.6f) * bins * 0.5f);
        } else {
            bin = 1 + mSynthRand.nextInt(Math.max(1, bins - 2));
        }
        if (bin >= bins) bin = bins - 1;
        mNoteBin[slot] = bin;
        mNoteAmp[slot] = 25f + mSynthRand.nextFloat() * 55f;
        mNoteWidth[slot] = 2f + mSynthRand.nextFloat() * 4f;
        mNoteDecay[slot] = 0.90f + mSynthRand.nextFloat() * 0.05f;
    }

    /**
     * Process externally-supplied PCM data directly (used by ManyScene).
     * @param data  raw PCM samples
     * @param len   number of samples to process
     */
    void updateWaveDataFromArray(int[] data, int len) {
        if (len == 0) {
            if (mIdle == 0) mIdle = 1;
            return;
        }
        if (mIdle != 0) mIdle = 0;

        int outlen = mPointData.length / 8;
        if (len > outlen) len = outlen;
        for (int i = 0; i < len; i++) {
            float val = data[i];
            mPointData[i * 8 + 1] = val;
            mPointData[i * 8 + 5] = -val;
        }
        mWaveCounter++;
        if (mRecolorDynamic && mRecolorEnabled && len > 0) {
            updateDynamicHue();
        }
    }

    private void updateDynamicHue() {
        float sum = 0f;
        int count = 0;
        int outlen = mPointData.length / 8;
        int width = Math.min(mWidth, outlen);
        if (width <= 0) width = outlen;
        int skip = (outlen - width) / 2;
        int end = outlen - skip;
        if (skip < 0) skip = 0;
        if (end > outlen) end = outlen;
        for (int i = skip; i < end; i++) {
            sum += Math.abs(mPointData[i * 8 + 1]);
            count++;
        }
        if (count > 0) {
            float avg = sum / count;
            float norm = Math.min(1f, avg / 800f);
            mHue = (mHue + norm * 0.03f) % 1.0f;
        }
    }

    // ---- idle / fade ----

    void applyIdleAndFade() {
        int width = mWidth;
        if (width > 1024) width = 1024;
        int skip = (1024 - width) / 2;
        int end = 1024 - skip;

        if (mIdle != 0) {
            if (fadeoutcounter > 0) {
                for (int i = skip; i < end; i++) {
                    float val = Math.abs(mPointData[i * 8 + 1]);
                    val = val * FADEOUT_FACTOR;
                    if (val < 2f) val = 2f;
                    mPointData[i * 8 + 1] = val;
                    mPointData[i * 8 + 5] = -val;
                }
                fadeoutcounter--;
                if (fadeoutcounter == 0) {
                    wave1amp = 0; wave2amp = 0; wave3amp = 0; wave4amp = 0;
                }
            } else {
                makeIdleWave(mPointData);
            }
            fadeincounter = FADEIN_LENGTH;
        } else {
            if (fadeincounter > 0 && fadeoutcounter == 0) {
                makeIdleWave(idleWave);
                if (lastWaveCounter != mWaveCounter) {
                    lastWaveCounter = mWaveCounter;
                    for (int i = skip; i < end; i++) {
                        float val = Math.abs(mPointData[i * 8 + 1]);
                        mPointData[i * 8 + 1] = (val * (FADEIN_LENGTH - fadeincounter)
                                + idleWave[i * 8 + 1] * fadeincounter) / FADEIN_LENGTH;
                        mPointData[i * 8 + 5] = (-val * (FADEIN_LENGTH - fadeincounter)
                                + idleWave[i * 8 + 5] * fadeincounter) / FADEIN_LENGTH;
                    }
                }
                fadeincounter--;
                if (fadeincounter == 0) {
                    fadeoutcounter = FADEOUT_LENGTH;
                }
            } else {
                fadeoutcounter = FADEOUT_LENGTH;
            }
        }
    }

    void makeIdleWave(float[] points) {
        float amp1 = (float) Math.sin(0.007f * wave1amp) * 120f;
        float amp2 = (float) Math.sin(0.023f * wave2amp) * 80f;
        float amp3 = (float) Math.sin(0.011f * wave3amp) * 40f;
        float amp4 = (float) Math.sin(0.031f * wave4amp) * 20f;
        int skip = (1024 - mWidth) / 2;
        if (skip < 0) skip = 0;
        int end = 1024 - skip;
        for (int i = skip; i < end; i++) {
            float val = (float) (Math.sin(0.013f * (wave1pos + i)) * amp1
                    + Math.sin(0.029f * (wave2pos + i)) * amp2);
            float off = (float) (Math.sin(0.005f * (wave3pos + i)) * amp3
                    + Math.sin(0.017f * (wave4pos + i)) * amp4);
            if (val < 2f && val > -2f) val = 2f;
            points[i * 8 + 1] = val + off;
            points[i * 8 + 5] = -val + off;
        }
        wave1pos++; wave1amp++;
        wave2pos--; wave2amp++;
        wave3pos++; wave3amp++;
        wave4pos++; wave4amp++;
    }

    // ---- buffer building ----

    /** Build mPositions[] and mTexCoords[] from mPointData (no GL calls). */
    void updateBuffers() {
        for (int i = 0; i < LINE_COUNT; i++) {
            int base = i * 8;
            int out = i * 4;
            mPositions[out]     = mPointData[base];
            mPositions[out + 1] = mPointData[base + 1];
            mPositions[out + 2] = mPointData[base + 4];
            mPositions[out + 3] = mPointData[base + 5];

            mTexCoords[out]     = mPointData[base + 2];
            mTexCoords[out + 1] = mPointData[base + 3];
            mTexCoords[out + 2] = mPointData[base + 6];
            mTexCoords[out + 3] = mPointData[base + 7];
        }
    }

    /** Build mAdjustData[] from HSL state. */
    void updateAdjustBuffer() {
        float h = mRecolorEnabled ? mHue : -1f;
        float s = mRecolorEnabled ? mSaturation : 1f;
        float v = mRecolorEnabled ? mBrightness : 1f;
        for (int i = 0; i < LINE_COUNT * 2; i++) {
            int b = i * 3;
            mAdjustData[b]     = h;
            mAdjustData[b + 1] = s;
            mAdjustData[b + 2] = v;
        }
    }

    // ---- MVP ----

    void updateMvp() {
        float left, right, bottom, top;
        if (mWidth > mHeight) {
            float aspect = (float) mWidth / (float) mHeight;
            left = -aspect;
            right = aspect;
            bottom = -1f;
            top = 1f;
        } else {
            float aspect = (float) mHeight / (float) mWidth;
            left = -1f;
            right = 1f;
            bottom = -aspect;
            top = aspect;
        }
        Mat4.orthoM(mProj, left, right, bottom, top, -1f, 1f);

        float scale = 0.004165f * (1.0f + 2f * Math.abs((float) Math.sin(Math.toRadians(mYRotation))));
        Mat4.setIdentityM(mModel);
        Mat4.rotateM(mModel, mYRotation, 0f, 0f, 1f);
        Mat4.scaleM(mModel, scale, scale, scale);

        Mat4.multiplyMM(mMvp, mProj, mModel);
    }
}
