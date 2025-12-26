package org.example;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纯WASAPI Loopback 音频捕获（JNI）
 * - 不依赖FFmpeg/麦克风
 * - 直接捕获系统播放的音频并下采样为 16kHz 单声道 16-bit PCM
 */
public class PureWasapiAudioCapture {

    public interface AudioDataCallback {
        void onAudioData(byte[] audioData, int length);
    }

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int TARGET_CHANNELS = 1;

    static {
        // 优先从当前目录加载 DLL，失败再尝试按库名加载
        boolean loaded = false;
        try {
            File dll = new File("wasapi_loopback.dll");
            if (dll.exists()) {
                System.load(dll.getAbsolutePath());
                loaded = true;
            }
        } catch (Throwable ignored) {}
        if (!loaded) {
            try {
                System.loadLibrary("wasapi_loopback");
                loaded = true;
            } catch (Throwable e) {
                System.err.println("[WASAPI-JNI] Failed to load native library: " + e.getMessage());
            }
        }
    }

    // 原生句柄
    private long nativeHandle = 0L;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private AudioDataCallback audioCallback;

    // JNI 方法
    private native long nativeInit(int sampleRate, int channels);
    private native boolean nativeStart(long handle);
    private native void nativeStop(long handle);
    private native void nativeRelease(long handle);
    private native boolean nativeIsSupported();
    private native int nativeGetActualSampleRate(long handle);
    private native int nativeGetActualChannels(long handle);
    private native int nativeGetActualBitsPerSample(long handle);

    // 实际捕获的音频格式（在启动后由native代码填充）
    private volatile int actualSampleRate = 0;
    private volatile int actualChannels = 0;
    private volatile int actualBitsPerSample = 0;

    public int getTargetSampleRate() { return TARGET_SAMPLE_RATE; }
    public int getActualSampleRate() { return actualSampleRate; }
    public int getActualChannels() { return actualChannels; }
    public int getActualBitsPerSample() { return actualBitsPerSample; }
    public boolean isCapturing() { return isCapturing.get(); }

    public boolean isSupported() {
        try {
            return nativeIsSupported();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public synchronized boolean initialize() {
        if (nativeHandle != 0L) return true;
        try {
            nativeHandle = nativeInit(TARGET_SAMPLE_RATE, TARGET_CHANNELS);
            if (nativeHandle == 0L) {
                System.err.println("[WASAPI-JNI] Initialization failed (null handle returned)");
                return false;
            }
            return true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[WASAPI-JNI] Native method not found: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            System.err.println("[WASAPI-JNI] Initialization error: " + t.getMessage());
            return false;
        }
    }

    public synchronized boolean startCapture(AudioDataCallback callback) {
        if (isCapturing.get()) return true;
        if (nativeHandle == 0L && !initialize()) return false;
        this.audioCallback = callback;
        boolean ok = nativeStart(nativeHandle);
        if (ok) {
            // 获取实际的音频格式信息
            actualSampleRate = nativeGetActualSampleRate(nativeHandle);
            actualChannels = nativeGetActualChannels(nativeHandle);
            actualBitsPerSample = nativeGetActualBitsPerSample(nativeHandle);
            
            isCapturing.set(true);
            System.out.println("[WASAPI-JNI] System audio capture started");
            System.out.println("[WASAPI-JNI] Actual format: " + actualSampleRate + "Hz, " 
                + actualChannels + " channels, " + actualBitsPerSample + " bits");
            System.out.println("[WASAPI-JNI] Will resample to: " + TARGET_SAMPLE_RATE + "Hz, " 
                + TARGET_CHANNELS + " channel");
        } else {
            System.err.println("[WASAPI-JNI] Failed to start capture");
        }
        return ok;
    }

    public synchronized void stopCapture() {
        if (!isCapturing.get()) return;
        try {
            nativeStop(nativeHandle);
        } catch (Throwable ignored) {}
        isCapturing.set(false);
        System.out.println("[WASAPI-JNI] Audio capture stopped");
    }

    public synchronized void close() {
        stopCapture();
        if (nativeHandle != 0L) {
            try { nativeRelease(nativeHandle); } catch (Throwable ignored) {}
            nativeHandle = 0L;
        }
        System.out.println("[WASAPI-JNI] Resources released");
    }

    // 被JNI调用，将PCM数据回调到Java
    @SuppressWarnings("unused")
    private void onNativePcmData(byte[] data, int length) {
        AudioDataCallback callback = this.audioCallback;
        if (callback != null && length > 0) {
            callback.onAudioData(data, length);
        }
    }
}


