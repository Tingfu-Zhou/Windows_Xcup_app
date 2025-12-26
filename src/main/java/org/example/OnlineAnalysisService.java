package org.example;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;    
import java.util.concurrent.TimeUnit;   

/**
 * 在线分析服务
 * 负责协调屏幕捕获、音频捕获和动作分析
 * 相当于Android版本的OnlineAnalysisService
 */
public class OnlineAnalysisService {
    private static final String TAG = "OnlineAnalysisService";
    
    // 分析参数
    private static final int WINDOW_SIZE = 32;                           // ST-GCN 窗口大小
    private static final int AUDIO_REQUIRED_POINTS = 32_000;             // 2s * 16kHz
    private static final long VIDEO_ANALYSIS_INTERVAL_MS = 100;          // 视频分析间隔
    private static final long AUDIO_ANALYSIS_INTERVAL_MS = 100;          // 音频分析间隔
    private static final long FUSION_INTERVAL_MS = 800;                  // 融合结果间隔
    private static final long AUDIO_STEP_MS = 1000;                      // 音频推理步长
    
    // 静音检测参数
    private static final long SILENCE_RESET_THRESHOLD_MS = 5000;         // 静音超过5秒则重置（类似seek）
    
    // 决策窗口配置（对齐离线模式）
    private static final int DECISION_WINDOW_SIZE = 10;                  // 10帧窗口
    private static final long MAX_AGE_MS = 2000;                         // 2秒时效
    private static final int MIN_OCCURRENCE_THRESHOLD = 3;               // 最小出现次数
    private static final float AUDIO_WEIGHT_FACTOR = 1.3f;               // 音频权重系数
    private static final float VIDEO_WEIGHT_FACTOR = 0.8f;               // 视频权重系数
    
    // 蓝牙发送状态管理器相关变量（对齐离线模式）
    private static final long STABILITY_CONFIRM_TIME_MS = 1600;          // 稳定确认时间
    private static final long MIN_DURATION_TIME_MS = 2000;               // 最小持续时间
    private static final long SEND_INTERVAL_MS = 1600;                   // 发送间隔
    
    
    // 核心组件
    private ScreenCaptureHelper screenCapture;
    private PureWasapiAudioCapture audioCapture;
    private PcmCircularBuffer pcmBuffer;
    private InferenceHelper inferenceHelper;
    private AudioInferenceHelper audioHelper;
    
    // 线程调度
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> videoAnalysisTask;
    private ScheduledFuture<?> audioAnalysisTask;
    private ScheduledFuture<?> fusionTask;
    
    // 分析状态
    private volatile boolean isAnalyzing = false;
    private volatile boolean isPaused = false;
    private final ArrayDeque<float[][]> poseWindow = new ArrayDeque<>();
    
    // 动作分类（快慢由节律估计器负责，不再由分类模型区分）
    private final String[] audioClasses = {"do", "oral", "Noise"};
    
    // 节律估计器（对齐离线模式）
    private final AudioRhythmEstimator audioRhythmEstimator = new AudioRhythmEstimator(16_000);
    private volatile float latestAudioRhythmHz = Float.NaN;
    private volatile float latestAudioRhythmConf = 0f;
    private volatile long  latestAudioRhythmTsMs = 0L;
    private volatile boolean latestAudioRhythmValid = false;
    
    private final VideoRhythmEstimator videoRhythmEstimator = new VideoRhythmEstimator();
    private volatile float latestVideoFreqHz = Float.NaN;
    private volatile float latestVideoFreqConf = 0f;
    private volatile long  latestVideoFreqTsMs = 0L;
    
    // 分析结果
    private volatile String latestVideoAction = "";
    private volatile String latestAudioAction = "";
    private volatile String latestFusedAction = "";
    private volatile float latestVideoConfidence = 0f;
    private volatile float latestAudioConfidence = 0f;
    private volatile long latestVideoTimestamp = 0;
    private volatile long latestAudioTimestamp = 0;
    
    // 静音检测
    private volatile long lastAudioActivityTime = 0;                     // 最后一次检测到音频活动的时间
    private volatile boolean hasDetectedInitialAudio = false;            // 是否检测到过音频（避免启动时立即重置）
    
    // 音频推理节流
    private volatile long lastAudioInferMs = 0;
    
    // 决策窗口历史记录（对齐离线模式）
    private static class ActionRecord {
        final long timestamp;
        final String videoAction;
        final String audioAction;
        final float videoConfidence;
        final float audioConfidence;
        
        ActionRecord(long timestamp, String videoAction, String audioAction, 
                    float videoConfidence, float audioConfidence) {
            this.timestamp = timestamp;
            this.videoAction = videoAction;
            this.audioAction = audioAction;
            this.videoConfidence = videoConfidence;
            this.audioConfidence = audioConfidence;
        }
    }
    
    private final LinkedList<ActionRecord> actionHistory = new LinkedList<>();
    private final Object historyLock = new Object();
    
    // 蓝牙状态管理（对齐离线模式）
    private volatile String pendingBluetoothState = "";
    private volatile String currentBluetoothState = "";
    private volatile long pendingStateStartTime = 0;
    private volatile long currentStateStartTime = 0;
    private volatile long lastBluetoothSendTime = 0;
    
    // 档位状态管理（直接透传 finalFreq）
    private volatile int lastSentLevel = 0;
    
    // 结果回调接口
    public interface AnalysisResultCallback {
        void onVideoActionUpdated(String action, float confidence);
        void onAudioActionUpdated(String action, float confidence);
        void onFusedActionUpdated(String fusedAction);
        void onAnalysisPausedChanged(boolean isPaused);
    }
    
    private AnalysisResultCallback resultCallback;
    
    public OnlineAnalysisService() {
        // 创建线程池
        scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "OnlineAnalysis-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        
        System.out.println("[OnlineAnalysis] Service initialized");
    }
    
    /**
     * 初始化服务
     * WASAPI Loopback会自动捕获系统默认输出设备的音频，无需手动选择设备
     */
    public boolean initialize() {
        try {
            System.out.println("[OnlineAnalysis] Initializing components...");
            
            // 初始化屏幕捕获
            screenCapture = new ScreenCaptureHelper();
            System.out.println("[OnlineAnalysis] Screen capture initialized");
            
            // 初始化PCM缓冲区（在线模式，目标16kHz单声道）
            pcmBuffer = new PcmCircularBuffer(16000, 10, true); // 16kHz, 10秒缓冲，在线模式
            System.out.println("[OnlineAnalysis] PCM buffer initialized (target: 16kHz mono)");
            
            // 初始化 Pure WASAPI 音频捕获（JNI原生实现）
            // WASAPI Loopback自动捕获系统默认输出设备，无需手动选择
            audioCapture = new PureWasapiAudioCapture();
            if (!audioCapture.initialize()) {
                throw new RuntimeException("Audio capture initialization failed");
            }
            System.out.println("[OnlineAnalysis] Pure WASAPI audio capture initialized");
            System.out.println("[OnlineAnalysis] Using WASAPI Loopback (captures system audio output)");
            
            // 初始化推理引擎
            inferenceHelper = new InferenceHelper();
            audioHelper = new AudioInferenceHelper("models/yamnet.onnx", "models/yamnet_finetuned.onnx");
            System.out.println("[OnlineAnalysis] Inference engines initialized");
            
            System.out.println("[OnlineAnalysis] [OK] All components initialized successfully");
            return true;
            
        } catch (Exception e) {
            System.err.println("[OnlineAnalysis] [FAIL] Initialization failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 开始在线分析
     */
    public synchronized boolean startAnalysis(AnalysisResultCallback callback) {
        if (isAnalyzing) {
            System.out.println("[OnlineAnalysis] Already running");
            return true;
        }
        
        if (screenCapture == null || pcmBuffer == null) {
            System.err.println("[OnlineAnalysis] Components not initialized");
            return false;
        }
        
        this.resultCallback = callback;
        
        // 开始屏幕捕获
        try {
            screenCapture.startCapture(this::onScreenFrameCaptured);
        } catch (Exception e) {
            System.err.println("[OnlineAnalysis] Failed to start screen capture: " + e.getMessage());
            return false;
        }
        
        // 启动音频捕获
        if (audioCapture != null) {
            if (!audioCapture.startCapture(this::onAudioDataCaptured)) {
                System.err.println("[OnlineAnalysis] Failed to start audio capture");
                return false;
            }
        }
        
        isAnalyzing = true;
        isPaused = false;
        
        // 启动分析循环
        startAnalysisLoops();
        
        System.out.println("[OnlineAnalysis] [OK] Online analysis started");
        return true;
    }
    
    /**
     * 停止在线分析
     */
    public synchronized void stopAnalysis() {
        if (!isAnalyzing) {
            return;
        }
        
        isAnalyzing = false;
        
        // 停止捕获
        if (screenCapture != null) {
            screenCapture.stopCapture();
        }
        // 停止音频捕获
        if (audioCapture != null) {
            audioCapture.stopCapture();
        }
        
        // 停止分析循环
        stopAnalysisLoops();
        
        // 清空缓存
        poseWindow.clear();
        if (pcmBuffer != null) {
            pcmBuffer.reset();
        }
        
        // 重置状态
        latestVideoAction = "";
        latestAudioAction = "";
        latestFusedAction = "";
        latestVideoConfidence = 0f;
        latestAudioConfidence = 0f;
        lastAudioInferMs = 0;
        
        System.out.println("[OnlineAnalysis] Analysis stopped");
    }
    
    /**
     * 暂停/恢复分析
     */
    public synchronized void pauseAnalysis(boolean pause) {
        if (!isAnalyzing) {
            return;
        }
        
        isPaused = pause;
        System.out.println("[OnlineAnalysis] Analysis " + (pause ? "paused" : "resumed"));
        
        if (resultCallback != null) {
            resultCallback.onAnalysisPausedChanged(isPaused);
        }
    }
    
    /**
     * 启动分析循环
     */
    private void startAnalysisLoops() {
        // 视频分析循环
        videoAnalysisTask = scheduler.scheduleAtFixedRate(this::videoAnalysisCycle, 0,
                VIDEO_ANALYSIS_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // 音频分析循环
        audioAnalysisTask = scheduler.scheduleAtFixedRate(this::audioAnalysisCycle, 0,
                AUDIO_ANALYSIS_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // 融合循环
        fusionTask = scheduler.scheduleAtFixedRate(this::fusionCycle, 0,
                FUSION_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 停止分析循环
     */
    private void stopAnalysisLoops() {
        if (videoAnalysisTask != null) {
            videoAnalysisTask.cancel(false);
            videoAnalysisTask = null;
        }
        if (audioAnalysisTask != null) {
            audioAnalysisTask.cancel(false);
            audioAnalysisTask = null;
        }
        if (fusionTask != null) {
            fusionTask.cancel(false);
            fusionTask = null;
        }
    }
    
    /**
     * 屏幕帧回调
     */
    private void onScreenFrameCaptured(BufferedImage frame) {
        if (!isAnalyzing || isPaused) {
            return;
        }
        
        // 将帧添加到队列中等待处理
        // 这里可以使用队列缓冲，避免直接在回调中处理
        // 为了简化，直接处理
    }
    
    /**
     * 音频数据回调
     */
    private void onAudioDataCaptured(byte[] audioData, int length) {
        if (!isAnalyzing || pcmBuffer == null) {
            return;
        }
        
        try {
            // 只处理有效长度的数据
            if (length > 0) {
                byte[] validData = new byte[length];
                System.arraycopy(audioData, 0, validData, 0, length);
                
                // 添加到PCM缓冲区，传入实际的音频格式信息
                // PcmCircularBuffer会自动进行立体声->单声道转换和重采样到16kHz
                int actualSampleRate = audioCapture.getActualSampleRate();
                int actualChannels = audioCapture.getActualChannels();
                
                if (actualSampleRate > 0 && actualChannels > 0) {
                    pcmBuffer.addByteData(validData, actualSampleRate, actualChannels);
                } else {
                    // 如果格式信息未就绪，使用默认值（通常是48kHz立体声）
                    pcmBuffer.addByteData(validData, 48000, 2);
                }
                
                // 检查音频活动状态
                boolean hasActivity = pcmBuffer.hasAudioActivity();
                long currentTime = System.currentTimeMillis();
                
                if (hasActivity) {
                    // 检测到音频活动
                    lastAudioActivityTime = currentTime;
                    if (!hasDetectedInitialAudio) {
                        hasDetectedInitialAudio = true;
                        log("检测到音频活动，开始分析");
                    }
                    
                    // 恢复分析
                    if (isPaused) {
                        pauseAnalysis(false);
                    }
                } else {
                    // 无音频活动
                    if (!isPaused) {
                        pauseAnalysis(true);
                    }
                    
                    // 静音检测：超过5秒无音频 → 重置所有状态（类似seek）
                    if (hasDetectedInitialAudio && lastAudioActivityTime > 0) {
                        long silenceDuration = currentTime - lastAudioActivityTime;
                        if (silenceDuration >= SILENCE_RESET_THRESHOLD_MS) {
                            log(String.format("检测到静音超过 %.1f 秒，执行状态重置", silenceDuration / 1000.0));
                            performSilenceReset();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[OnlineAnalysis] Failed to process audio data: " + e.getMessage());
        }
    }
    
    /**
     * 静音重置：类似离线模式的 seek 操作
     * 清空所有历史记录和缓存
     */
    private synchronized void performSilenceReset() {
        log("执行静音重置...");
        
        // 清空 pose 缓冲
        synchronized (poseWindow) {
            poseWindow.clear();
        }
        
        // 清空 pcm 缓冲
        if (pcmBuffer != null) {
            pcmBuffer.reset();
        }
        
        // 清空动作历史记录
        synchronized (historyLock) {
            actionHistory.clear();
        }
        
        // 清空蓝牙控制动作缓存
        resetBluetoothStateManager();
        
        // 重置节律估计器（音频 + 视频）
        audioRhythmEstimator.reset();
        latestAudioRhythmHz = Float.NaN;
        latestAudioRhythmConf = 0f;
        latestAudioRhythmTsMs = 0L;
        latestAudioRhythmValid = false;
        
        videoRhythmEstimator.reset();
        latestVideoFreqHz = Float.NaN;
        latestVideoFreqConf = 0f;
        latestVideoFreqTsMs = 0L;
        
        // 清空分析结果
        latestVideoAction = "";
        latestAudioAction = "";
        latestFusedAction = "";
        latestVideoConfidence = 0f;
        latestAudioConfidence = 0f;
        latestVideoTimestamp = 0;
        latestAudioTimestamp = 0;
        lastAudioInferMs = 0;
        
        // 重置静音检测标志（防止重复触发）
        lastAudioActivityTime = System.currentTimeMillis();
        
        log("✅ 静音重置完成");
    }

    
    /**
     * 视频分析循环
     */
    private void videoAnalysisCycle() {
        if (!isAnalyzing || isPaused) {
            return;
        }
        
        try {
            // 获取当前屏幕帧
            BufferedImage frame = screenCapture.captureScreenOnce();
            if (frame == null) {
                return;
            }
            
            // 姿态检测
            float[][][][] keypointsRaw = inferenceHelper.detectPose(frame);
            if (keypointsRaw == null) {
                return;
            }
            
            // 处理关键点
            float[][] keypoints = keypointsRaw[0][0];
            int imgW = frame.getWidth();
            int imgH = frame.getHeight();
            
            // 归一化关键点坐标
            for (int i = 0; i < keypoints.length; i++) {
                keypoints[i][0] /= imgW;
                keypoints[i][1] /= imgH;
            }
            
            // 将归一化后的关键点推入视频节律器（对齐离线模式）
            long framePtsMs = System.currentTimeMillis();
            videoRhythmEstimator.onPoseFrame(keypoints, framePtsMs);
            
            // 拉取最新估计值并存储
            float videoFreq = videoRhythmEstimator.getLatestFreqHz();
            float videoFreqConf = videoRhythmEstimator.getLatestConf();
            long videoFreqTs = videoRhythmEstimator.getLatestTsMs();
            
            latestVideoFreqHz = videoFreq;
            latestVideoFreqConf = videoFreqConf;
            latestVideoFreqTsMs = videoFreqTs;
            
            // 添加到滑动窗口
            synchronized (poseWindow) {
                poseWindow.add(keypoints);
                if (poseWindow.size() > WINDOW_SIZE) {
                    poseWindow.poll();
                }
                
                // 如果窗口满了，进行动作识别
                if (poseWindow.size() == WINDOW_SIZE) {
                    float[][][] input = convertPoseWindowToInput(poseWindow);
                    input = ActionUtils.preNormalize2D(input);
                    float[] scores = inferenceHelper.runStgcnModel(input);
                    
                    if (scores != null) {
                        float[] probs = ActionUtils.softmax(scores);
                        String actionClass = ActionUtils.processStgcnOutput(probs);
                        float bestScore = ActionUtils.getBestScore(probs, actionClass);
                        
                        latestVideoAction = actionClass;
                        latestVideoConfidence = bestScore;
                        latestVideoTimestamp = System.currentTimeMillis();
                        
                        if (resultCallback != null) {
                            resultCallback.onVideoActionUpdated(actionClass, bestScore);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[OnlineAnalysis] Video analysis failed: " + e.getMessage());
        }
    }
    
    /**
     * 音频分析循环
     */
    private void audioAnalysisCycle() {
        if (!isAnalyzing || isPaused) {
            return;
        }
        
        long currentMs = System.currentTimeMillis();
        if (currentMs - lastAudioInferMs < AUDIO_STEP_MS) {
            return; // 1s步长节流
        }
        
        try {
            // 获取 2 秒音频用于分类
            float[] pcm = pcmBuffer.getLatestData(AUDIO_REQUIRED_POINTS);
            if (pcm == null) {
                return;
            }
            
            // === 节律估计：每 ~1 秒推送一次 16k 采样，并在预热(4s)后估计 ===
            float[] last1s = pcmBuffer.getLatestData(16_000);
            if (last1s != null && last1s.length > 0) {
                audioRhythmEstimator.push(last1s);
            }
            if (audioRhythmEstimator.isWarm()) {
                AudioRhythmEstimator.Result rr = audioRhythmEstimator.estimate(System.currentTimeMillis());
                latestAudioRhythmHz = rr.frequencyHz;
                latestAudioRhythmConf = rr.confidence;
                latestAudioRhythmTsMs = rr.timestampMs;
                latestAudioRhythmValid = rr.valid;
                log(String.format("[AudioRhythm] valid=%s, freq=%.2f Hz, conf=%.2f",
                        rr.valid, rr.frequencyHz, rr.confidence));
            }
            // === 节律估计结束 ===
            
            // 音频分类推理
            float[] audioProbs = audioHelper.predictProbs(pcm);
            if (audioProbs == null) {
                return;
            }
            
            int audioClassIndex = argMax(audioProbs);
            float audioProb = audioProbs[audioClassIndex];
            float threshold = 0.4f;
            String audioClass = (audioProb < threshold) ? "Noise" : audioClasses[audioClassIndex];
            
            latestAudioAction = audioClass;
            latestAudioConfidence = audioProb;
            latestAudioTimestamp = System.currentTimeMillis();
            lastAudioInferMs = currentMs;
            
            if (resultCallback != null) {
                resultCallback.onAudioActionUpdated(audioClass, audioProb);
            }
            
        } catch (Exception e) {
            System.err.println("[OnlineAnalysis] Audio analysis failed: " + e.getMessage());
        }
    }
    
    /**
     * 融合循环（对齐离线模式）
     */
    private void fusionCycle() {
        if (!isAnalyzing) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // 第一步：读取最新的分析结果
        String videoAction = latestVideoAction;
        String audioAction = latestAudioAction;
        float videoConf = latestVideoConfidence;
        float audioConf = latestAudioConfidence;
        long videoTime = latestVideoTimestamp;
        long audioTime = latestAudioTimestamp;
        
        // 第二步：时间过滤 - 计算结果的新鲜度
        long videoAge = videoTime > 0 ? currentTime - videoTime : Long.MAX_VALUE;
        long audioAge = audioTime > 0 ? currentTime - audioTime : Long.MAX_VALUE;
        
        // 如果结果过期，清空它
        if (videoAge > MAX_AGE_MS) {
            videoAction = "";
            videoConf = 0f;
        }
        if (audioAge > MAX_AGE_MS) {
            audioAction = "";
            audioConf = 0f;
        }
        
        // 第三步：添加当前记录到历史窗口
        synchronized (historyLock) {
            actionHistory.addLast(new ActionRecord(currentTime, videoAction, audioAction, videoConf, audioConf));
            while (actionHistory.size() > DECISION_WINDOW_SIZE) {
                actionHistory.removeFirst();
            }
        }
        
        // 第四步：使用决策函数获取最终动作
        String finalAction = decisionFusion();
        
        // 第五步：读取音视频节律并融合为最终档位（对齐离线模式）
        float audioFreq = latestAudioRhythmHz;
        float audioFreqConf = latestAudioRhythmConf;
        long audioFreqTs = latestAudioRhythmTsMs;
        boolean audioFreqValid = latestAudioRhythmValid;
        
        float videoFreq = latestVideoFreqHz;
        float videoFreqConf = latestVideoFreqConf;
        long videoFreqTs = latestVideoFreqTsMs;
        
        // 过滤过期的节律结果
        long audioFreqAge = audioFreqTs > 0 ? currentTime - audioFreqTs : Long.MAX_VALUE;
        if (audioFreqAge > MAX_AGE_MS || !audioFreqValid) {
            audioFreq = Float.NaN;
            audioFreqConf = 0f;
        }
        
        long videoFreqAge = videoFreqTs > 0 ? currentTime - videoFreqTs : Long.MAX_VALUE;
        if (videoFreqAge > MAX_AGE_MS) {
            videoFreq = Float.NaN;
            videoFreqConf = 0f;
        }
        
        // 融合音视频节律（优先使用音频节律）
        float finalFreq = audioFreq;
        if (Float.isNaN(finalFreq) && !Float.isNaN(videoFreq)) {
            finalFreq = videoFreq;
        }
        
        // 将频率映射为档位
        int finalLevel = mapFreqToLevel(finalFreq);
        
        // 第六步：使用蓝牙发送状态管理器，实现二级平滑策略 + 档位确认
        managedBluetoothUpdate(finalAction, finalLevel);
        
        // 第七步：更新融合结果回调
        if (!finalAction.equals(latestFusedAction)) {
            latestFusedAction = finalAction;
            if (resultCallback != null) {
                resultCallback.onFusedActionUpdated(finalAction);
            }
        }
    }
    
    // 辅助方法（从VideoProcessController复用）
    private float[][][] convertPoseWindowToInput(ArrayDeque<float[][]> window) {
        float[][][] input = new float[WINDOW_SIZE][17][3];
        int idx = 0;
        for (float[][] kpts : window) {
            input[idx++] = kpts;
        }
        return input;
    }
    
    private int argMax(float[] scores) {
        int idx = 0;
        float maxVal = scores[0];
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > maxVal) {
                maxVal = scores[i];
                idx = i;
            }
        }
        return idx;
    }
    
    /**
     * 核心决策融合函数 - 使用时间窗口平滑策略和加权评分算法（对齐离线模式）
     */
    private String decisionFusion() {
        synchronized (historyLock) {
            if (actionHistory.size() < MIN_OCCURRENCE_THRESHOLD) {
                return simpleDecision();
            }
            
            Map<String, Float> audioScores = new HashMap<>();
            Map<String, Float> videoScores = new HashMap<>();
            Map<String, Integer> actionCounts = new HashMap<>();
            
            int windowSize = actionHistory.size();
            
            for (int i = 0; i < windowSize; i++) {
                ActionRecord record = actionHistory.get(i);
                
                // 计算时间递增权重
                float timeWeight = (float)(i + 1) / windowSize;
                
                // 处理音频动作
                if (record.audioAction != null && !record.audioAction.isEmpty() && 
                    !record.audioAction.equals("Noise") && record.audioConfidence > 0) {
                    
                    float audioScore = record.audioConfidence * timeWeight * AUDIO_WEIGHT_FACTOR;
                    audioScores.put(record.audioAction, audioScores.getOrDefault(record.audioAction, 0f) + audioScore);
                    actionCounts.put(record.audioAction, actionCounts.getOrDefault(record.audioAction, 0) + 1);
                }
                
                // 处理视频动作
                if (record.videoAction != null && !record.videoAction.isEmpty() && 
                    !record.videoAction.equals("Noise") && record.videoConfidence > 0) {
                    
                    float videoScore = record.videoConfidence * timeWeight * VIDEO_WEIGHT_FACTOR;
                    videoScores.put(record.videoAction, videoScores.getOrDefault(record.videoAction, 0f) + videoScore);
                    actionCounts.put(record.videoAction, actionCounts.getOrDefault(record.videoAction, 0) + 1);
                }
            }
            
            // 噪声过滤：最小出现次数阈值
            Map<String, Float> finalScores = new HashMap<>();
            for (String action : actionCounts.keySet()) {
                if (actionCounts.get(action) >= MIN_OCCURRENCE_THRESHOLD) {
                    float totalScore = audioScores.getOrDefault(action, 0f) + videoScores.getOrDefault(action, 0f);
                    if (totalScore > 0) {
                        finalScores.put(action, totalScore);
                    }
                }
            }
            
            // 选择得分最高的动作
            if (finalScores.isEmpty()) {
                return "Noise";
            }
            
            String bestAction = "";
            float bestScore = 0f;
            for (Map.Entry<String, Float> entry : finalScores.entrySet()) {
                if (entry.getValue() > bestScore) {
                    bestScore = entry.getValue();
                    bestAction = entry.getKey();
                }
            }
            
            return bestAction.isEmpty() ? "Noise" : bestAction;
        }
    }
    
    /**
     * 简单的动作选择逻辑（用于历史记录不足时）
     */
    private String simpleDecision() {
        String audioAction = latestAudioAction;
        String videoAction = latestVideoAction;
        float audioConf = latestAudioConfidence;
        float videoConf = latestVideoConfidence;
        
        // 音频优先策略
        if (audioAction != null && !audioAction.isEmpty()) {
            if (!audioAction.equals("Noise") || audioConf > 0.7f) {
                return audioAction;
            }
        }
        
        if (videoAction != null && !videoAction.isEmpty()) {
            if (!videoAction.equals("Noise") || videoConf > 0.7f) {
                return videoAction;
            }
        }
        
        return "Noise";
    }
    
    /**
     * 频率→档位映射（对齐离线模式）
     */
    private static final float[][] LEVEL_RANGES = new float[][]{
        null,                // 0占位（停止）
        {0.05f, 0.80f},      // 档1
        {0.80f, 1.10f},      // 档2
        {1.10f, 1.40f},      // 档3
        {1.40f, 1.80f},      // 档4
        {1.80f, 2.30f},      // 档5
        {2.30f, 2.80f},      // 档6
        {2.80f, 3.40f},      // 档7
        {3.40f, 4.10f},      // 档8
        {4.10f, 4.80f},      // 档9
        {4.80f, 6.00f}       // 档10
    };
    
    private static int mapFreqToLevel(final float hz) {
        if (Float.isNaN(hz) || hz <= 0f) return 0;
        for (int lvl = 1; lvl <= 10; lvl++) {
            float[] r = LEVEL_RANGES[lvl];
            if (r != null && hz >= r[0] && hz < r[1]) return lvl;
        }
        return 10;
    }
    
    
    /**
     * 蓝牙发送状态管理器（对齐离线模式）
     */
    private void managedBluetoothUpdate(String finalAction, int finalFreq) {
        long currentTime = System.currentTimeMillis();
        
        if (finalAction == null || finalAction.isEmpty() || "Noise".equals(finalAction)) {
            return;
        }
        
        BLEManager bleManager = BLEManager.globalManager;
        if (bleManager != null && bleManager.isPaused()) {
            return;
        }
        
        int levelToSend = finalFreq;
        
        // 动作稳定确认
        if (!finalAction.equals(pendingBluetoothState)) {
            pendingBluetoothState = finalAction;
            pendingStateStartTime = currentTime;
            return;
        }
        
        long pendingDuration = currentTime - pendingStateStartTime;
        if (pendingDuration < STABILITY_CONFIRM_TIME_MS) {
            return;
        }
        
        // 情况 A：切换到不同动作
        if (!pendingBluetoothState.equals(currentBluetoothState)) {
            if (!currentBluetoothState.isEmpty()) {
                long currentStateDuration = currentTime - currentStateStartTime;
                if (currentStateDuration < MIN_DURATION_TIME_MS) {
                    return;
                }
            }
            
            long timeSinceLastSend = currentTime - lastBluetoothSendTime;
            if (timeSinceLastSend < SEND_INTERVAL_MS) {
                return;
            }
            
            updateBluetoothState(pendingBluetoothState, levelToSend);
            
            currentBluetoothState = pendingBluetoothState;
            currentStateStartTime = currentTime;
            lastBluetoothSendTime = currentTime;
            lastSentLevel = levelToSend;
        }
        // 情况 B：动作未变，但节律档位数值发生变化
        else {
            boolean levelChanged = (levelToSend != lastSentLevel);
            boolean gapOk = (currentTime - lastBluetoothSendTime) >= SEND_INTERVAL_MS;
            
            if (levelChanged && gapOk) {
                updateBluetoothState(currentBluetoothState, levelToSend);
                lastSentLevel = levelToSend;
                lastBluetoothSendTime = currentTime;
            }
        }
    }
    
    /**
     * 重置蓝牙状态管理器
     */
    private void resetBluetoothStateManager() {
        pendingBluetoothState = "";
        currentBluetoothState = "";
        pendingStateStartTime = 0;
        currentStateStartTime = 0;
        lastBluetoothSendTime = 0;
        
        lastSentLevel = 0;
        
        log("蓝牙管理器：状态已完全重置（包括档位）");
    }
    
    /**
     * 发送蓝牙动作指令（带档位）
     */
    private void updateBluetoothState(String state, int level) {
        BLEManager bleManager = BLEManager.globalManager;
        if (bleManager != null && state != null && !state.isEmpty()) {
            bleManager.sendAction(state, level);
            log("蓝牙发送：动作=" + state + ", 档位=" + level);
        }
    }
    
    // Getter方法
    public String getLatestVideoAction() { return latestVideoAction; }
    public String getLatestAudioAction() { return latestAudioAction; }
    public String getLatestFusedAction() { return latestFusedAction; }
    public float getLatestVideoConfidence() { return latestVideoConfidence; }
    public float getLatestAudioConfidence() { return latestAudioConfidence; }
    public boolean isAnalyzing() { return isAnalyzing; }
    public boolean isPaused() { return isPaused; }
    
    /**
     * 日志输出辅助函数
     */
    private void log(String message) {
        System.out.println("[" + TAG + "] " + message);
    }
    
    /**
     * 关闭服务
     */
    public void close() {
        stopAnalysis();
        
        if (screenCapture != null) {
            screenCapture.close();
        }
        // 清理音频捕获
        if (audioCapture != null) {
            audioCapture.close();
            audioCapture = null;
        }
        if (inferenceHelper != null) {
            inferenceHelper.close();
        }
        if (audioHelper != null) {
            audioHelper.close();
        }
        
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        System.out.println("[OnlineAnalysis] Service closed");
    }
}
