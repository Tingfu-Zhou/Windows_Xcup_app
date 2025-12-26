package org.example;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * AudioDecoder  (Windows / JavaFX 版)
 *
 *  1. 只做“解码 → PCM → PcmCircularBuffer”，不播放。
 *  2. 解码线程速度与 MediaPlayer.getCurrentTime() 同步，超前太多就限速。
 *  3. 支持 seekTo()，实现方式：stop() + 重启线程 + grabber.setTimestamp(...).
 *
 * ⚠️ 如果以后换蓝牙协议、换采样率，只需改 write() 逻辑即可。
 */
public class AudioDecoder {

    /* ------------------------ 常量配置 ------------------------ */
    private static final int TARGET_SAMPLE_RATE   = 16_000; // 推理模型要求
    private static final int TARGET_WINDOW_SIZE   = TARGET_SAMPLE_RATE * 2; // 2 s = 32 000 样本
    private static final int SEEK_AHEAD_TOLERANCE = 3_000;  // 解码可领先播放 3 秒
    private static final int SEEK_BOOST_TOLERANCE = 6_000;  // seek 后临时允许领先 6 秒
    private static final int SEEK_BOOST_DURATION  = 2_000;  // seek 后 2 秒内使用加速模式

    /* ------------------------ 成员 ------------------------ */
    private final Path videoPath;
    private final PcmCircularBuffer pcmBuffer;
    private final Supplier<Integer> videoPositionProvider; // lambda: mediaPlayer::getCurrentTime
    private Runnable onCompleteListener;

    private volatile boolean decoding      = false;
    private volatile boolean stopRequested = false;
    private volatile int     seekToMs      = -1;
    private volatile long    lastSeekTime  = 0;     // 记录最后一次 seek 的时间戳
    private Thread           decodeThread;

    /* ------------------------ 构造 ------------------------ */
    public AudioDecoder(Path videoPath,
                        PcmCircularBuffer pcmBuffer,
                        Supplier<Integer> videoPositionProvider) {
        this.videoPath             = videoPath;
        this.pcmBuffer             = pcmBuffer;
        this.videoPositionProvider = videoPositionProvider;
    }

    public void setOnCompleteListener(Runnable r) { this.onCompleteListener = r; }

    /* ------------------------ 外部控制接口 ------------------------ */

    /** 启动解码（若已在跑就忽略） */
    public synchronized void startDecoding() {
        if (decoding) return;
        stopRequested = false;
        decodeThread = new Thread(this::decodeLoop, "Audio-Decoder");
        decodeThread.start();
    }

    /** 请求停止解码线程 */
    public synchronized void stop() {
        stopRequested = true;
        if (decodeThread != null) decodeThread.interrupt();
    }

    /** 跳转到指定 ms（主线程调用） */
    public void seekTo(int ms) {
        seekToMs = Math.max(ms - 4_000, 0); // 提前 4 秒，保证缓冲
        lastSeekTime = System.currentTimeMillis(); // 记录 seek 时间，用于临时加速
        stop();
        startDecoding();
    }

    /* ======================================================================
                                   解码主循环
       ====================================================================== */
    private void decodeLoop() {
        decoding = true;
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile())) {
            grabber.setOption("nostdin", "true");     // 避免阻塞
            grabber.start();

            // 跳转到指定位置（μs）
            if (seekToMs >= 0) grabber.setTimestamp(seekToMs * 1_000L);

            final int srcSampleRate = grabber.getSampleRate();
            final int srcWindowSize = srcSampleRate * 2;     // 改为 2 s：首帧不会超限，不必频繁进入限速分支
            float[]   audioBuffer   = new float[srcWindowSize];
            int       bufferPos     = 0;
            long      segmentStartMs = 0;  // 记录音频段开始时间戳

            float[]   resampledBuf  = new float[TARGET_WINDOW_SIZE];

            while (!stopRequested) {
                Frame frame = grabber.grabSamples();
                if (frame == null) break;                   // EOF

                long ptsMs = grabber.getTimestamp() / 1_000; // 当前帧时间戳

                /* ------------- 智能限速：seek 后临时放宽限制 ------------- */
                int playPos = videoPositionProvider.get();
                
                // 计算当前使用的提前量：seek 后 2 秒内使用 6 秒，之后恢复 3 秒
                long currentTime = System.currentTimeMillis();
                boolean isInSeekBoostPeriod = (currentTime - lastSeekTime) <= SEEK_BOOST_DURATION;
                int currentTolerance = isInSeekBoostPeriod ? SEEK_BOOST_TOLERANCE : SEEK_AHEAD_TOLERANCE;
                
                // 限速时以等待代替丢帧：循环 sleep 直至播放时间追上，再处理同一帧
                while (ptsMs > playPos + currentTolerance && !stopRequested) {
                    Thread.sleep(100);
                    playPos = videoPositionProvider.get(); // 重新获取播放位置
                }

                /* ------------- 记录音频段开始时间戳 ------------- */
                if (bufferPos == 0) {
                    segmentStartMs = ptsMs; // 使用段首时间戳
                }
                
                /* ------------- 把 ShortBuffer → float[-1,1] ------------- */
                ShortBuffer sb = (ShortBuffer) frame.samples[0];
                sb.rewind();
                while (sb.remaining() > 0 && bufferPos < srcWindowSize) {
                    short s = sb.get();
                    audioBuffer[bufferPos++] = s / 32768f;
                }

                /* ------------- 窗口装满 → 写入环形缓冲 ------------- */
                if (bufferPos == srcWindowSize) {
                    if (srcSampleRate != TARGET_SAMPLE_RATE) {
                        // 简易线性插值到 16 kHz
                        for (int i = 0; i < TARGET_WINDOW_SIZE; i++) {
                            float srcPos = i * (srcSampleRate / (float) TARGET_SAMPLE_RATE);
                            int   idx    = (int) Math.floor(srcPos);
                            float frac   = srcPos - idx;
                            float s1 = audioBuffer[Math.min(idx,     srcWindowSize - 1)];
                            float s2 = audioBuffer[Math.min(idx + 1, srcWindowSize - 1)];
                            resampledBuf[i] = s1 + frac * (s2 - s1);
                        }
                        pcmBuffer.write(resampledBuf, segmentStartMs, TARGET_SAMPLE_RATE);
                    } else {
                        pcmBuffer.write(audioBuffer, segmentStartMs, srcSampleRate);
                    }
                    
                    // 调试日志：监控音频数据写入
                    System.out.printf("[AudioDecoder] Audio segment written: segmentTime=%dms, playPos=%dms, tolerance=%dms%n", 
                                    segmentStartMs, playPos, currentTolerance);
                    
                    bufferPos = 0;
                }
            }

        } catch (Exception e) {
            System.err.println("[AudioDecoder] Decoding exception: " + e);
        } finally {
            decoding = false;
            if (onCompleteListener != null) onCompleteListener.run();
        }
    }
}
