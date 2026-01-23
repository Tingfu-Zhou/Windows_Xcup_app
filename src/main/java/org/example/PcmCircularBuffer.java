package org.example;


public class PcmCircularBuffer {
    private static final String TAG = "PcmCircularBuffer";
    
    // 音频活动检测参数
    private static final float AUDIO_AMPLITUDE_THRESHOLD = 0.005f;
    private static final int MIN_SILENT_FRAMES = 10;
    private static final long SILENCE_THRESHOLD_MS = 5000;

    private final int capacity;      // 总采样点数量
    private final float[] buffer;    // 环形缓冲区
    private final long[] timestamps; // 每个采样点对应的时间戳（毫秒）
    private int writeIndex = 0;
    private boolean isFull = false;
    
    // 在线模式支持
    private final boolean onlineMode;
    private final int targetSampleRate = 16000; // 目标采样率固定为16kHz
    
    // 音频活动检测状态
    private int consecutiveSilentFrames = 0;
    private long silenceStartTime = 0;
    private boolean isCurrentlySilent = false;

    // 构造函数 - 离线模式（保持向后兼容）
    public PcmCircularBuffer(int sampleRate, int maxSeconds) {
        this.onlineMode = false;
        this.capacity = sampleRate * maxSeconds; // e.g., 16000 * 10 = 160000
        this.buffer = new float[capacity];
        this.timestamps = new long[capacity];
    }
    
    // 构造函数 - 支持在线模式
    public PcmCircularBuffer(int sampleRate, int maxSeconds, boolean onlineMode) {
        this.onlineMode = onlineMode;
        this.capacity = sampleRate * maxSeconds;
        this.buffer = new float[capacity];
        this.timestamps = new long[capacity];
    }

    // 写入一段 PCM 数据（浮点），附带起始时间戳
    // 在类内添加：
    private long lastWriteStart = -1;
    private long lastWriteEnd = -1;

    public synchronized void write(float[] data, long startTimestampMs, int sampleRate) {
        for (int i = 0; i < data.length; i++) {
            int index = (writeIndex + i) % capacity;
            buffer[index] = data[i];
            timestamps[index] = startTimestampMs + (i * 1000L / sampleRate);
        }
        writeIndex = (writeIndex + data.length) % capacity;
        if (data.length >= capacity) {
            isFull = true;          
        }
        // 记录本次写入的有效时间区间
        lastWriteStart = startTimestampMs;
        lastWriteEnd = startTimestampMs + (data.length * 1000L / sampleRate);
    }


    public synchronized float[] readWindowRelaxed(long currentTimeMs, int sampleCount) {
        float[] result = new float[sampleCount];
        int count = 0;

        for (int i = 0; i < capacity && count < sampleCount; i++) {
            int index = (writeIndex - 1 - i + capacity) % capacity;
            long ts = timestamps[index];

            if (ts > 0 && ts <= currentTimeMs) {
                result[sampleCount - count - 1] = buffer[index];
                count++;
            }
        }
        // 降低样本检查阈值，从90%降到60%，更宽松地处理seek后的情况 
        if (count < sampleCount * 0.6f) {
            System.out.println("🚫 readWindowRelaxed: : Too few samples, abandon analysis in this round");
            return null;
        }
        if (count < sampleCount) {
            System.out.println("🔁 readWindowRelaxed: Insufficient samples, only " + count + " were obtained," + sampleCount + "were expected, zeros will be added");
        } else {
            System.out.println("✅ readWindowRelaxed: Successfully obtained samples " + count + "item");
        }
        System.out.println("📊 currentTime = " + currentTimeMs + "，writeIndex = " + writeIndex);
        return result;
    }



    public synchronized void reset() {
        System.out.println("🧹 Flushing PCM buffer...");
        for (int i = 0; i < capacity; i++) {
            buffer[i] = 0f;
            timestamps[i] = 0L;
        }
        writeIndex = 0;
        isFull = false;
        lastWriteStart = -1;
        lastWriteEnd = -1;
        
        // 重置在线模式的音频活动检测状态
        if (onlineMode) {
            consecutiveSilentFrames = 0;
            silenceStartTime = 0;
            isCurrentlySilent = false;
        }
    }
    
    /* ------------------------------------------------------------------ */
    /*                           在线模式方法                             */
    /* ------------------------------------------------------------------ */
    
    /**
     * 在线模式：直接处理字节数组，自动转换和重采样到16kHz单声道
     * @param audioData PCM字节数据（16位小端序）
     * @param sourceSampleRate 原始采样率
     * @param sourceChannels 原始通道数（1=单声道，2=立体声）
     */
    /*
    public synchronized void addByteData(byte[] audioData, int sourceSampleRate, int sourceChannels) {
        if (!onlineMode) {
            throw new IllegalStateException("addByteData() only available in online mode");
        }
        
        // 转换字节数据到float数组（16位PCM）
        float[] samples = convertBytesToFloat(audioData);
        
        // 如果是立体声，先转换为单声道（混合左右声道）
        if (sourceChannels == 2) {
            samples = stereoToMono(samples);
        } else if (sourceChannels != 1) {
            System.err.println("[PCM] Unsupported channel count: " + sourceChannels);
            return;
        }
        
        // 重采样到16kHz（如果需要）
        if (sourceSampleRate != targetSampleRate) {
            samples = resample(samples, sourceSampleRate, targetSampleRate);
        }
        
        // 音频活动检测
        boolean hasActivity = detectAudioActivity(samples);
        
        // 写入缓冲区
        long currentTime = System.currentTimeMillis();
        for (int i = 0; i < samples.length; i++) {
            int index = (writeIndex + i) % capacity;
            buffer[index] = samples[i];
            timestamps[index] = currentTime + (i * 1000L / targetSampleRate);
        }
        writeIndex = (writeIndex + samples.length) % capacity;
        if (samples.length >= capacity) {
            isFull = true;
        }
        
        // Suppress verbose output - too noisy
        // System.out.println(String.format("[PCM-Online] Written %d samples, Audio activity: %s", 
        //                   samples.length, hasActivity ? "Active" : "Silent"));
    }
    */

    // [ADD] 新接口：带 bitsPerSample
    public synchronized void addByteData(byte[] audioData, int sourceSampleRate, int sourceChannels, int bitsPerSample) {
        if (!onlineMode) throw new IllegalStateException("addByteData() only available in online mode");

        if (sourceChannels <= 0) {
            System.err.println("[PCM] Invalid channel count: " + sourceChannels);
            return;
        }

        // 1) bytes -> float (interleaved)
        float[] interleaved = convertBytesToFloat(audioData, bitsPerSample);

        // 2) downmix -> mono (支持任意声道数)
        float[] mono = (sourceChannels == 1)
                ? interleaved
                : downmixInterleavedToMono(interleaved, sourceChannels);

        // 3) resample -> 16k
        if (sourceSampleRate != targetSampleRate) {
            mono = resample(mono, sourceSampleRate, targetSampleRate);
        }
        // [ADD] 音频活动检测（用于 hasAudioActivity / 静音逻辑）
        detectAudioActivity(mono);

        // [ADD] 在写入前
        if ((System.currentTimeMillis() / 1000) % 5 == 0) {
            // System.out.println("[PCM-Online] src=" + sourceSampleRate + "Hz, ch=" + sourceChannels
                    // + ", bits=" + bitsPerSample + ", afterDownmix(monoLen)=" + mono.length);
        }

        // 4) 写入环形缓冲
        long currentTime = System.currentTimeMillis();
        for (int i = 0; i < mono.length; i++) {
            int index = (writeIndex + i) % capacity;
            buffer[index] = mono[i];
            timestamps[index] = currentTime + (i * 1000L / targetSampleRate);
        }
        writeIndex = (writeIndex + mono.length) % capacity;
        if (mono.length >= capacity) isFull = true;
    }

    // [ADD] 旧接口保持不动，默认 bits=16，避免你其他地方还在用旧签名
    public synchronized void addByteData(byte[] audioData, int sourceSampleRate, int sourceChannels) {
        addByteData(audioData, sourceSampleRate, sourceChannels, 16);
    }

    // [ADD] 任意声道 interleaved downmix -> mono
    private float[] downmixInterleavedToMono(float[] interleaved, int channels) {
        int frames = interleaved.length / channels;
        float[] mono = new float[frames];
        int idx = 0;
        for (int f = 0; f < frames; f++) {
            float sum = 0f;
            for (int ch = 0; ch < channels; ch++) {
                sum += interleaved[idx++];
            }
            mono[f] = sum / channels;
        }
        return mono;
    }

    // [MOD] 支持 16/24/32-bit（32-bit 先按 float32 解，异常则退回 PCM32）
    private float[] convertBytesToFloat(byte[] audioData, int bitsPerSample) {
        switch (bitsPerSample) {
            case 16: {
                int n = audioData.length / 2;
                float[] samples = new float[n];
                for (int i = 0; i < n; i++) {
                    int lo = audioData[i * 2] & 0xFF;
                    int hi = audioData[i * 2 + 1]; // signed
                    short s = (short) ((hi << 8) | lo);
                    samples[i] = s / 32768.0f;
                }
                return samples;
            }

            case 24: { // PCM24 little-endian
                int n = audioData.length / 3;
                float[] samples = new float[n];
                for (int i = 0; i < n; i++) {
                    int b0 = audioData[i * 3] & 0xFF;
                    int b1 = audioData[i * 3 + 1] & 0xFF;
                    int b2 = audioData[i * 3 + 2]; // signed for sign-extend
                    int v = (b2 << 16) | (b1 << 8) | b0;
                    // sign extend 24->32
                    if ((v & 0x00800000) != 0) v |= 0xFF000000;
                    samples[i] = v / 8388608.0f; // 2^23
                }
                return samples;
            }

            case 32: {
                // 先按 float32 解析（WASAPI Mix Format 最常见）
                int n = audioData.length / 4;
                float[] asFloat = new float[n];
                boolean looksBad = false;

                for (int i = 0; i < n; i++) {
                    int b0 = audioData[i * 4] & 0xFF;
                    int b1 = audioData[i * 4 + 1] & 0xFF;
                    int b2 = audioData[i * 4 + 2] & 0xFF;
                    int b3 = audioData[i * 4 + 3] & 0xFF;
                    int bits = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
                    float v = Float.intBitsToFloat(bits);
                    asFloat[i] = v;

                    // 简单健壮性判断：出现 NaN/Inf 或绝对值过大，认为不是 float32 PCM
                    if (!Float.isFinite(v) || Math.abs(v) > 4.0f) {
                        looksBad = true;
                    }
                }

                if (!looksBad) {
                    // clamp 到 [-1,1]（防止偶发略超）
                    for (int i = 0; i < n; i++) {
                        float v = asFloat[i];
                        if (v > 1f) v = 1f;
                        else if (v < -1f) v = -1f;
                        asFloat[i] = v;
                    }
                    return asFloat;
                }

                // 退回按 PCM32（signed int32）解析
                float[] asPcm32 = new float[n];
                for (int i = 0; i < n; i++) {
                    int b0 = audioData[i * 4] & 0xFF;
                    int b1 = audioData[i * 4 + 1] & 0xFF;
                    int b2 = audioData[i * 4 + 2] & 0xFF;
                    int b3 = audioData[i * 4 + 3]; // signed
                    int v = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
                    asPcm32[i] = v / 2147483648.0f; // 2^31
                }
                return asPcm32;
            }

            default:
                System.err.println("[PCM] Unsupported bitsPerSample: " + bitsPerSample + " (expected 16/24/32)");
                return new float[0];
        }
    }


    /**
     * 在线模式：获取最新的N个样本
     * @param sampleCount 需要的样本数量
     * @return 最新的样本数据，如果数据不足返回null
     */
    public synchronized float[] getLatestData(int sampleCount) {
        if (!onlineMode) {
            throw new IllegalStateException("getLatestData() only available in online mode");
        }
        
        if (!isFull && writeIndex < sampleCount) {
            // Suppress verbose output - too noisy
            // System.out.println("getLatestData: Insufficient data, only " + writeIndex + " samples, need " + sampleCount);
            return null;
        }
        
        float[] result = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int index = (writeIndex - sampleCount + i + capacity) % capacity;
            result[i] = buffer[index];
        }
        
        // Suppress verbose output - too noisy
        // System.out.println("getLatestData: Successfully retrieved " + sampleCount + " latest samples");
        return result;
    }
    
    /**
     * 音频活动检测：检测是否有声音
     * @param samples 音频样本
     * @return true表示有声音活动，false表示静音
     */
    private boolean detectAudioActivity(float[] samples) {
        // 计算平均振幅
        float totalAmplitude = 0f;
        for (float sample : samples) {
            totalAmplitude += Math.abs(sample);
        }
        float avgAmplitude = totalAmplitude / samples.length;
        
        boolean currentFrameHasSound = avgAmplitude > AUDIO_AMPLITUDE_THRESHOLD;
        long currentTime = System.currentTimeMillis();
        
        if (!currentFrameHasSound) {
            consecutiveSilentFrames++;
            if (consecutiveSilentFrames == MIN_SILENT_FRAMES) {
                // 开始静音计时
                silenceStartTime = currentTime;
                // Suppress - too verbose
                // System.out.println("[Audio] Starting silence timing, consecutive silent frames: " + consecutiveSilentFrames);
            } else if (consecutiveSilentFrames >= MIN_SILENT_FRAMES && 
                       !isCurrentlySilent && 
                       (currentTime - silenceStartTime) >= SILENCE_THRESHOLD_MS) {
                // 静音时间超过阈值，确认进入静音状态
                isCurrentlySilent = true;
                // Suppress - too verbose
                // System.out.println("[Audio] Confirmed silent state (duration: " + (currentTime - silenceStartTime) + "ms)");
            }
        } else {
            // 检测到声音，立即重置
            if (consecutiveSilentFrames > 0 || isCurrentlySilent) {
                // Suppress - too verbose
                // System.out.println("[Audio] Sound detected, reset silent state (amplitude: " + String.format("%.6f", avgAmplitude) + ")");
            }
            consecutiveSilentFrames = 0;
            silenceStartTime = 0;
            isCurrentlySilent = false;
        }
        
        return currentFrameHasSound;
    }
    
    /**
     * 获取当前音频活动状态
     * @return true表示当前有声音活动，false表示静音
     */
    public synchronized boolean hasAudioActivity() {
        return !isCurrentlySilent;
    }
    
    /**
     * 将字节数组转换为float数组（16位小端序PCM）
     */
    private float[] convertBytesToFloat(byte[] audioData) {
        float[] samples = new float[audioData.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int sample = (short) ((audioData[i * 2 + 1] << 8) | (audioData[i * 2] & 0xFF));
            samples[i] = sample / 32768.0f; // 归一化到[-1, 1]
        }
        return samples;
    }
    
    /**
     * 立体声转单声道（混合左右声道）
     * @param stereoSamples 交错的立体声样本 [L, R, L, R, ...]
     * @return 单声道样本
     */
    private float[] stereoToMono(float[] stereoSamples) {
        int monoLength = stereoSamples.length / 2;
        float[] mono = new float[monoLength];
        
        for (int i = 0; i < monoLength; i++) {
            // 平均左右声道
            mono[i] = (stereoSamples[i * 2] + stereoSamples[i * 2 + 1]) / 2.0f;
        }
        
        return mono;
    }
    
    /**
     * 简单线性重采样
     */
    private float[] resample(float[] input, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return input;
        }
        
        double ratio = (double) fromRate / toRate;
        int outputLength = (int) Math.round(input.length / ratio);
        float[] output = new float[outputLength];
        
        for (int i = 0; i < outputLength; i++) {
            double srcIndex = i * ratio;
            int index0 = (int) Math.floor(srcIndex);
            int index1 = Math.min(index0 + 1, input.length - 1);
            double fraction = srcIndex - index0;
            
            // 线性插值
            output[i] = (float) (input[index0] * (1 - fraction) + input[index1] * fraction);
        }
        
        // Suppress verbose output - too noisy
        // System.out.println(String.format("[Resample] %d samples@%dHz -> %d samples@%dHz", 
        //                   input.length, fromRate, output.length, toRate));
        return output;
    }

}
