package org.example;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.util.Duration;

import ai.onnxruntime.OrtException;

import java.net.URISyntaxException;
 
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
 
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * Windows 桌面版核心控制器，等价于 Android 的 VideoProcessActivity。
 * <p>
 * 职责：
 * <ol>
 *   <li>播放本地视频（JavaFX MediaPlayer）。</li>
 *   <li>每 300 ms 同步抽帧 → 姿态检测 → 滑窗积累 → ST‑GCN++。</li>
 *   <li>拉取 2 s PCM → YAMNet ONNX → 音频动作。</li>
 *   <li>融合音视频动作，更新 UI & 通过 BluetoothHelper 发送指令。</li>
 *   <li>监听拖动、暂停/恢复、播放完，保持分析与视频时间线一致。</li>
 * </ol>
 * <p>
 */
public class VideoProcessController {

    /* ---------------------------- 常量配置 ---------------------------- */
    private static final int WINDOW_SIZE = 32;                           // ST‑GCN 窗宽
    private static final int AUDIO_REQUIRED_POINTS = 32_000;             // 2 s * 16 kHz
    // ST-GCN++ 滑窗步长：每 8 帧推理一次
    private static final int STGCN_STEP = 8;
    // 推理门控计数：窗口满后，每 STGCN_STEP 帧触发一次推理
    private int stgcnStrideCountdown = 0;

    // 多循环间隔（ms）
    private static final long VIDEO_ANALYSIS_INTERVAL_MS = 100;          // 视频分析线程周期
    private static final long AUDIO_ANALYSIS_INTERVAL_MS = 100;          // 音频分析线程周期（内部 1s 触发一次推理）
    private static final long FUSION_INTERVAL_MS         = 800;          // 融合/UI/蓝牙线程周期
    private static final long AUDIO_STEP_MS               = 1_000;        // 音频推理步长 1s

    /* ---------------------------- UI 组件 ---------------------------- */
    private final Stage stage;
    private final Runnable onBack;      // 调主菜单的回调
    private final MediaView mediaView = new MediaView();
    private final Label tvVideoAction = new Label("Video Action: --");
    private final Label tvAudioAction = new Label("Audio Action: --");
    private final Label tvOverlay     = new Label("Final Action: --");
    private final Button btnFullscreen = new Button("⛶");
    private Slider slider;           // 进度条
    private Button btnPlayPause;     // ▶ / Ⅱ

    // [ADD] 显示播放时间：current / total
    private Label lbTime;

    // [ADD] 缓存总时长，避免频繁取 Duration
    private volatile long totalDurationMs = 0;

    // [ADD] 毫秒 -> mm:ss 或 HH:mm:ss
    private static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long s = totalSec % 60;
        long m = (totalSec / 60) % 60;
        long h = totalSec / 3600;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }


    /* ---------------------------- 多媒体核心 ---------------------------- */
    private MediaPlayer mediaPlayer;
    private VideoFrameExtractor frameExtractor;
    private AudioDecoder audioDecoder;

    /* ---------------------------- 动作类别 ---------------------------- */
    // ST-GCN++ 现在输出8类，不再直接使用actionClasses数组
    // 音频分类：3类（快慢由节律估计器负责，不再由分类模型区分）
    private final String[] audioClasses = {"do", "oral", "Noise"};

    /* ---------------------------- 推理相关 ---------------------------- */
    private InferenceHelper inferenceHelper;          // 视频推理
    private AudioInferenceHelper audioHelper;   // YAMNet + classifier ONNX

    /* ---------------------------- 节律估计 ---------------------------- */
    /*
    private final AudioRhythmEstimator audioRhythmEstimator = new AudioRhythmEstimator(16_000);
    private volatile float latestAudioRhythmHz = Float.NaN;
    private volatile float latestAudioRhythmConf = 0f;
    private volatile long  latestAudioRhythmTsMs = 0L;
    private volatile boolean latestAudioRhythmValid = false;
    */
    /* ---------------------------- [MOD] Loudness 档位估计（替代音频节律） ---------------------------- */
    private final AudioLoudnessLevelEstimator loudnessEstimator = new AudioLoudnessLevelEstimator(16_000); // [ADD]

    // 音频“响度档位”输出（0..9）
    private volatile int   latestAudioLoudLevel = 0;
    private volatile float latestAudioLoudConf  = 0f;
    private volatile long  latestAudioLoudTsMs  = 0L;
    private volatile boolean latestAudioLoudValid = false;

    // 视频节律估计器（对齐 Android）— 新方案：基于 ROI 块运动 + 主方向投影 + 自相关主频
    private final VideoMotionWaveEstimator videoRhythmEstimator = new VideoMotionWaveEstimator();
    private volatile float latestVideoFreqHz = Float.NaN;
    private volatile float latestVideoFreqConf = 0f;
    private volatile long  latestVideoFreqTsMs = 0L;

    /* ---------------------------- 滑窗与缓存 ---------------------------- */
    private final ArrayDeque<float[][]> poseWindow = new ArrayDeque<>();
    private final PcmCircularBuffer pcmBuffer = new PcmCircularBuffer(16_000, 20); // 20 s 环形缓冲

    /* ---------------------------- 蓝牙 ---------------------------- */
    private final BLEManager bleManager = BLEManager.globalManager;

    /* ---------------------------- 运行时状态 ---------------------------- */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final BooleanProperty analysisPaused = new SimpleBooleanProperty(false);
    private volatile boolean videoEnded = false;
    private volatile String latestVideoAction = "";
    private volatile String latestAudioAction = "";
    private volatile String lastFinalAction   = "";
    private volatile long   audioStartWallMs  = 0;  // 首帧播放时间，用于 4 s 缓冲
    private ScheduledFuture<?> pendingSeekFuture;
    
    /* ---------------------------- 决策函数相关 ---------------------------- */
    // 时间过滤相关
    private volatile float latestVideoConfidence = 0f;
    private volatile float latestAudioConfidence = 0f;
    private volatile long latestVideoTimestamp = 0;
    private volatile long latestAudioTimestamp = 0;
    
    // 决策窗口历史记录
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
    
    // 决策窗口配置
    private static final int DECISION_WINDOW_SIZE = 10;  // 10帧窗口
    private static final long MAX_AGE_MS = 2000;         // 2秒时效
    private static final int MIN_OCCURRENCE_THRESHOLD = 3; // 最小出现次数
    private static final float AUDIO_WEIGHT_FACTOR = 1.3f; // 音频权重系数
    private static final float VIDEO_WEIGHT_FACTOR = 0.7f; // 视频权重系数
    
    // 历史记录队列（线程安全）
    private final java.util.LinkedList<ActionRecord> actionHistory = new java.util.LinkedList<>();
    private final Object historyLock = new Object();

    // 三个循环的句柄
    private ScheduledFuture<?> videoLoopFuture;
    private ScheduledFuture<?> audioLoopFuture;
    private ScheduledFuture<?> fusionLoopFuture;

    // 音频推理节流
    private volatile long lastAudioInferMs = 0;

    // 旧版平滑历史（保留兼容性，但已被决策函数替代）
    private static class TimedLabel {
        final long timeMs;
        final String label;
        TimedLabel(long t, String l){ this.timeMs = t; this.label = l; }
    }
    private final ArrayDeque<TimedLabel> recentVideo = new ArrayDeque<>();
    private final ArrayDeque<TimedLabel> recentAudio = new ArrayDeque<>();
    
    // 蓝牙发送状态管理器相关变量
    private static final long STABILITY_CONFIRM_TIME_MS = 1600;  // 稳定确认时间：1600ms
    private static final long MIN_DURATION_TIME_MS = 2000;       // 最小持续时间：2秒
    private static final long SEND_INTERVAL_MS = 1600;           // 发送间隔：1600ms
    
    private volatile String pendingBluetoothState = "";          // 待确认的蓝牙状态
    private volatile String currentBluetoothState = "";          // 当前执行的蓝牙状态
    private volatile long pendingStateStartTime = 0;             // 待确认状态开始时间
    private volatile long currentStateStartTime = 0;             // 当前状态开始时间
    private volatile long lastBluetoothSendTime = 0;             // 上次蓝牙发送时间
    
    // 频率档位记录（直接透传 finalFreq）
    private volatile int lastSentLevel = 0;                      // 最近一次已发送档位

    // ★ 新增：暂停时挂起的蓝牙动作与档位
    private volatile String suspendedBluetoothState = "";
    private volatile int suspendedLevel = 0;

    // =====================[ 频率档位确认与节流相关成员 ]=====================
    // 档位（0..10）确认状态
    private volatile int currentLevel = 1;                 // 已确认生效的档位
    private volatile long currentLevelSinceMs = 0L;        // 当前档位生效起点
    private volatile Integer pendingLevel = null;          // 待确认档位
    private volatile long pendingLevelSinceMs = 0L;        // 待确认起点

    // 档位确认参数（融合循环=800ms，因此短稳=800ms 即 1 tick）
    private static final long LEVEL_STABLE_MS   = 0;     // 新档位短稳确认
    private static final long LEVEL_MIN_DUR_MS  = 0;    // 生效档位最小驻留（≈2 tick）

    /* ---------------------------- 其他补充 ---------------------------- */
    // 防止重复 close（避免 OrtSession 被二次关闭）
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    /* ---------------------------- 构造与启动 ---------------------------- */

    /**
     * @param stage 由上层应用传入的主 Stage。
     * @param videoPath 用户选择的视频文件路径。
     */
    public VideoProcessController(Stage stage,
                                  Path videoPath,
                                  Runnable onBack) throws Exception {
        this.stage = stage;
        this.onBack  = onBack;

        BorderPane root = buildUi();           // 返回 UI 根节点
        stage.setScene(new Scene(root, 960, 540));
        stage.setTitle("X姬 – Video Analysis");

        System.out.println("[VideoProcess] 开始初始化各个组件...");
        
        try {
            // 1. 先初始化视频播放器（最关键）
            System.out.println("[VideoProcess] 1/5 初始化MediaPlayer...");
            initPlayer(videoPath.toUri());
            
            // 2. 初始化推理引擎
            System.out.println("[VideoProcess] 2/5 初始化推理引擎...");
            initInferenceEngines();
            
            // 3. 初始化视频帧提取器
            System.out.println("[VideoProcess] 3/5 初始化视频帧提取器...");
            frameExtractor = new VideoFrameExtractor(videoPath);
            
            // 4. 初始化音频管道
            System.out.println("[VideoProcess] 4/5 初始化音频管道...");
            initAudioPipeline(videoPath);
            
            // 5. 启动监控和工作循环
            System.out.println("[VideoProcess] 5/5 启动监控循环...");
            startPlayStateWatcher();
            startWorkerLoops();
            
            System.out.println("[VideoProcess] ✅ 所有组件初始化完成");
        } catch (Exception e) {
            System.err.println("[VideoProcess] ❌ 初始化失败: " + e.getMessage());
            e.printStackTrace();
            throw e; // 重新抛出异常
        }
    }

    /* ------------------------------------------------------------------ */
    /*                               UI                                   */
    /* ------------------------------------------------------------------ */

    private BorderPane buildUi() {
        BorderPane root = new BorderPane();
        mediaView.setPreserveRatio(true);          // 保留长宽比
        mediaView.setSmooth(true);
        mediaView.setStyle("-fx-background-color: black;"); // 其余区域填黑
        root.setCenter(mediaView);

        // 全屏开关
        btnFullscreen.setOnAction(e -> {
            boolean fs = !stage.isFullScreen();
            stage.setFullScreen(fs);
            btnFullscreen.setText(fs ? "🗗" : "⛶");
        });

        // 返回主界面
        Button btnBack = new Button("← 返回");
        btnBack.setOnAction(e -> {
            close();          // 释放线程 / 解码器等
            onBack.run();     // 关键：切回主菜单，不关窗口
        });

        /* ---------- 媒体控制栏 ---------- */
        btnPlayPause = new Button("Ⅱ");            // 初始假定自动播放
        slider       = new Slider(0, 100, 0);      // 最大值稍后绑定总时长, totalDuration 出来后，再更新 slider 最大值
        // slider.setPrefWidth(400);
        // [ADD] 让 slider 在 HBox 里自动拉伸占满剩余空间
        slider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(slider, Priority.ALWAYS);

        // [ADD] 时间显示：00:00 / 00:00
        lbTime = new Label("00:00 / 00:00");
        lbTime.setMinWidth(110);         // 防止太窄挤压抖动（你也可以调大一点）
        lbTime.setAlignment(Pos.CENTER_RIGHT);

        VBox controlBarWrapper = new VBox();  // ⚠️ 这个 VBox 放到底部
        HBox controlBar = new HBox(10, btnPlayPause, slider, lbTime);   // [MOD] 把 lbTime 加进去
        controlBar.setPadding(new Insets(5));
        controlBar.setAlignment(Pos.CENTER_LEFT); // [ADD] 视觉更稳定
        controlBarWrapper.getChildren().add(controlBar);
        root.setBottom(controlBarWrapper); // overlay + 控制栏

        HBox infoBar = new HBox(10, tvVideoAction, tvAudioAction, tvOverlay,btnFullscreen,btnBack);
        root.setTop(infoBar);

        //stage.setScene(new Scene(root, 960, 540));
        //stage.setTitle("X姬 – Video Analysis");

        // 当用户点击窗口右上角 “X” 时，先释放资源再关闭窗口
        stage.setOnCloseRequest(evt -> {
            close();      // 和 ← 返回 按钮做同样的清理
            //   ▸ 若 Main 界面一直开着，什么也不用做
            //   ▸ 若你想始终回到 Main，可保存 Main 的 Stage 并在这里 show()
        });

        // root 是你在 buildUi() 创建的 BorderPane
        // infoBar、controlBar 已在前面定义
        mediaView.fitWidthProperty().bind(
                root.widthProperty());                                   // 横向铺满

        // 垂直方向要减去顶部 infoBar 与底部 controlBar 的高度
        mediaView.fitHeightProperty().bind(
                root.heightProperty()
                        .subtract(infoBar.heightProperty())
                        .subtract(controlBarWrapper.heightProperty())); // ✅ 正确绑定 VBox


        /* 让 root 本身呈现全黑背景，剩余区域自动填充黑边 */
        root.setStyle("-fx-background-color: black;");
        return root;
    }

    /* ------------------------------------------------------------------ */
    /*                         MediaPlayer 初始化                         */
    /* ------------------------------------------------------------------ */

    private void initPlayer(URI videoUri) {
        System.out.println("[VideoProcess] 尝试加载视频: " + videoUri);
        System.out.println("[VideoProcess] 视频文件路径: " + videoUri.getPath());
        System.out.println("[VideoProcess] URI Scheme: " + videoUri.getScheme());
        
        // 详细验证文件
        try {
            Path videoPath = Path.of(videoUri);
            System.out.println("[VideoProcess] 解析后的文件路径: " + videoPath.toAbsolutePath());
            
            if (!java.nio.file.Files.exists(videoPath)) {
                System.err.println("❌ 视频文件不存在: " + videoPath.toAbsolutePath());
                showErrorMessage("视频文件不存在", "找不到视频文件: " + videoPath.toAbsolutePath());
                return;
            }
            
            long fileSize = java.nio.file.Files.size(videoPath);
            System.out.println("✅ 视频文件存在，大小: " + fileSize + " bytes");
            
            if (fileSize == 0) {
                System.err.println("❌ 视频文件为空");
                showErrorMessage("视频文件错误", "视频文件大小为0字节，文件可能已损坏");
                return;
            }
            
            // 检查文件扩展名和格式兼容性
            String fileName = videoPath.getFileName().toString().toLowerCase();
            String fileExtension = "";
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0) {
                fileExtension = fileName.substring(lastDot);
            }
            System.out.println("[VideoProcess] 文件扩展名: " + fileExtension);
            
            // 检查JavaFX支持的格式
            validateVideoFormat(fileExtension, fileName);
            
        } catch (Exception e) {
            System.err.println("❌ 无法验证视频文件: " + e.getMessage());
            e.printStackTrace();
            showErrorMessage("视频文件验证失败", "无法访问视频文件: " + e.getMessage());
            return;
        }
        
        Media media = new Media(videoUri.toString());
        mediaPlayer = new MediaPlayer(media);
        mediaView.setMediaPlayer(mediaPlayer);

        // 新增：监听 MediaPlayer 状态变化
        mediaPlayer.statusProperty().addListener((obs, oldStatus, newStatus) -> {
            System.out.println("[VideoProcess] MediaPlayer status changed: " + oldStatus + " -> " + newStatus);
            if (newStatus == MediaPlayer.Status.HALTED) {
                System.err.println("❌ MediaPlayer HALTED - 播放失败！");
            } else if (newStatus == MediaPlayer.Status.STALLED) {
                System.err.println("⚠️ MediaPlayer STALLED - 播放卡顿！");
            }
        });

        // 添加错误处理监听器
        mediaPlayer.setOnError(() -> {
            javafx.scene.media.MediaException error = mediaPlayer.getError();
            if (error != null) {
                System.err.println("❌ MediaPlayer 错误: " + error.getMessage());
                System.err.println("错误类型: " + error.getType());
                error.printStackTrace();
            }
        });

        // 添加媒体信息监听器  
        mediaPlayer.getMedia().setOnError(() -> {
            javafx.scene.media.MediaException error = mediaPlayer.getMedia().getError();
            if (error != null) {
                System.err.println("❌ Media 加载错误: " + error.getMessage());
                System.err.println("错误类型: " + error.getType());
                error.printStackTrace();
                
                Platform.runLater(() -> {
                    showErrorMessage("媒体加载失败", 
                        "无法加载视频文件。\n\n错误详情：" + error.getMessage() + 
                        "\n错误类型：" + error.getType() +
                        "\n\n可能的解决方案：\n" +
                        "1. 检查视频文件是否完整\n" +
                        "2. 尝试使用H.264/AAC编码的MP4文件\n" +
                        "3. 使用其他视频转换工具重新编码");
                });
            }
        });

        /* ── totalDuration 出来后，更新 slider 最大值 ── */
        mediaPlayer.totalDurationProperty().addListener((o,oldDur,newDur) -> {
            slider.setMax(newDur.toMillis());
            // [ADD] 缓存总时长 + 刷新时间显示
            totalDurationMs = (long) newDur.toMillis();
            if (lbTime != null) {
                long curMs = (long) mediaPlayer.getCurrentTime().toMillis();
                lbTime.setText(formatMs(curMs) + " / " + formatMs(totalDurationMs));
            }
        });

        /* ── 播放时更新 slider ── */
        mediaPlayer.currentTimeProperty().addListener((obs,oldT,newT)->{
            if (!slider.isValueChanging()) {           // 只有用户没在拖动时同步
                slider.setValue(newT.toMillis());
            }

            // [ADD] 实时刷新时间显示
            if (lbTime != null) {
                long curMs = (long) newT.toMillis();
                lbTime.setText(formatMs(curMs) + " / " + formatMs(totalDurationMs));
            }

            // 拖动检测（保留原逻辑）
            if (Math.abs(newT.subtract(oldT).toMillis()) > 500) {  // 简易防抖：500 ms 以上认定为用户 seek
                onUserSeek((long) newT.toMillis());
            }
        });

        /* ── 用户拖动 slider ── */
        slider.valueChangingProperty().addListener((obs,wasChanging,isChanging)->{
            if (!isChanging) {                         // 用户松手
                long toMs = (long) slider.getValue();
                mediaPlayer.seek(Duration.millis(toMs));
                onUserSeek(toMs);                      // 触发生态同步
            }
        });

        /* ---- 点击 Slider 轨道立即 Seek ---- */
        slider.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (!slider.isDisabled() && e.getButton() == MouseButton.PRIMARY) {
                double mouseX   = e.getX();
                double percent  = mouseX / slider.getWidth();          // 0.0 – 1.0
                double toMs     = percent * slider.getMax();
                slider.setValue(toMs);                                 // 拖拽拇指同步 UI
                mediaPlayer.seek(Duration.millis((long) toMs));
                onUserSeek((long) toMs);                               // 同步分析
                e.consume();                                           // 吃掉事件，避免默认处理
            }
        });


        /* ── 播放 / 暂停按钮 ── */
        btnPlayPause.setOnAction(e -> {
            if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
                mediaPlayer.pause();
                btnPlayPause.setText("▶");
            } else {
                mediaPlayer.play();
                btnPlayPause.setText("Ⅱ");
            }
        });


        // ▶ 播放完只暂停分析，允许 seek 后继续
        mediaPlayer.setOnEndOfMedia(() -> {
            videoEnded = true;
            pauseAnalysis();
            log("Video playback is complete, synchronization analysis is paused, and user operation is awaited");
        });

        mediaPlayer.setOnReady(() -> {
            System.out.println("[VideoProcess] setOnReady called, currentTime=" + mediaPlayer.getCurrentTime());
            audioStartWallMs = (long) mediaPlayer.getCurrentTime().toMillis();// 记录用于 4 s 缓冲
            mediaPlayer.play();
            System.out.println("[VideoProcess] mediaPlayer.play() called in setOnReady");
        });
    }

    /* ------------------------------------------------------------------ */
    /*                        解码 & 推理初始化                           */
    /* ------------------------------------------------------------------ */

    // 替换 VideoProcessController.java 中的 initInferenceEngines 方法

    private void initInferenceEngines() throws OrtException, IOException, URISyntaxException {
        // 不再使用 Paths.get，而是直接传递资源路径
        // InferenceHelper 会自己处理资源加载
        inferenceHelper = new InferenceHelper();

        // AudioInferenceHelper 已经支持从 classpath 加载
        audioHelper = new AudioInferenceHelper("models/yamnet.onnx", "models/yamnet_finetuned.onnx");
    }

    private void initAudioPipeline(Path videoPath) {
        audioDecoder = new AudioDecoder(videoPath, pcmBuffer, () -> (int) getCurrentPositionMs());
        audioDecoder.startDecoding(); // 在内部线程解码并写 pcmBuffer
    }

    /* ------------------------------------------------------------------ */
    /*                        播放状态监测 (200 ms)                       */
    /* ------------------------------------------------------------------ */
    private void startPlayStateWatcher() {
        scheduler.scheduleAtFixedRate(() -> {
            boolean playing = mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING;
            Platform.runLater(() -> {
                if (playing && !videoEnded && analysisPaused.get()) {
                    resumeAnalysis();
                } else if ((!playing || videoEnded) && !analysisPaused.get()) {
                    pauseAnalysis();
                }
            });
        }, 0, 200, TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------------ */
    /*                           三线程工作循环                            */
    /* ------------------------------------------------------------------ */

    private void startWorkerLoops() {
        // 视频分析循环（100 ms）
        videoLoopFuture = scheduler.scheduleAtFixedRate(this::videoAnalysisCycle, 0,
                VIDEO_ANALYSIS_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 音频分析循环（100 ms 调度，内部 1s 节流）
        audioLoopFuture = scheduler.scheduleAtFixedRate(this::audioAnalysisCycle, 0,
                AUDIO_ANALYSIS_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 融合循环（800 ms）
        fusionLoopFuture = scheduler.scheduleAtFixedRate(this::fusionCycle, 0,
                FUSION_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void videoAnalysisCycle() {
        if (analysisPaused.get()) return;
        if (!running.get()) return;
        long currentMs = getCurrentPositionMs();
        try {
            BufferedImage frame = frameExtractor.getFrameAt(currentMs * 1000L);
            if (frame == null) return;

            float[][][][] keypointsRaw = inferenceHelper.detectPose(frame); // [1,1,17,3]
            if (keypointsRaw == null) {
                // 镜头切换 / 未检测到人体：通知节律器并清空 latestVideoFreqHz（修旧版漏掉 reset 的 bug）
                long framePtsMs = System.currentTimeMillis();
                videoRhythmEstimator.pushFrame(framePtsMs, null, null);
                latestVideoFreqHz = Float.NaN;
                latestVideoFreqConf = 0f;
                latestVideoFreqTsMs = framePtsMs;
                log("[Video] No person detected, skip this frame (estimator notified)");
                return;
            }

            float[][] keypoints = keypointsRaw[0][0];
            int imgW = frame.getWidth();
            int imgH = frame.getHeight();
            for (int i = 0; i < keypoints.length; i++) {
                keypoints[i][0] /= imgW;
                keypoints[i][1] /= imgH;
            }

            // 将归一化后的关键点 + 当前帧推入"视频运动波形估计器"
            long framePtsMs = System.currentTimeMillis();
            videoRhythmEstimator.pushFrame(framePtsMs, frame, keypoints);

            // 拉取最新估计值并存储（仅在 valid=true 时采用）
            VideoWaveResult vr = videoRhythmEstimator.getLatestResult();
            if (vr != null && vr.valid) {
                latestVideoFreqHz   = vr.freqHz;
                latestVideoFreqConf = vr.confidence;
                latestVideoFreqTsMs = vr.timestampMs;
                log(String.format("[频率测试] [离线模式] 视频运动波形 - f=%.2fHz, conf=%.2f, per=%.2f, mE=%.3f, pos01=%.2f, locked=%s",
                        vr.freqHz, vr.confidence, vr.periodicity, vr.motionEnergy, vr.position01, vr.locked));
                log("[VideoWave] " + vr.debugInfo);
            } else {
                latestVideoFreqHz   = Float.NaN;
                latestVideoFreqConf = 0f;
                latestVideoFreqTsMs = framePtsMs;
                if (vr != null) log("[VideoWave] " + vr.debugInfo);
            }

            poseWindow.add(keypoints);
            if (poseWindow.size() > WINDOW_SIZE) poseWindow.poll();
            if (poseWindow.size() == WINDOW_SIZE) {

                // stride=8 门控：第一次窗口满立即推理，此后每 8 帧推理一次
                if (stgcnStrideCountdown > 0) {
                    stgcnStrideCountdown--;
                    return; // [ADD] 本帧不做 ST-GCN 推理
                }
                stgcnStrideCountdown = STGCN_STEP - 1;

                float[][][] input = convertPoseWindowToInput(poseWindow);
                input = ActionUtils.preNormalize2D(input);
                float[] scores = inferenceHelper.runStgcnModel(input);
                if (scores != null) {
                    float[] probs = ActionUtils.softmax(scores);
                    // 新的8类合并逻辑
                    String actionClass = ActionUtils.processStgcnOutput(probs);
                    float bestScore = ActionUtils.getBestScore(probs, actionClass);

                    // 这里加入比例阈值过滤
                    // 这里本质是比例阈值判定，噪声需要比目标类高50%才被认定，如果识别为 Noise 但置信度较低，则判定为 do
                    if ("Noise".equals(actionClass) && bestScore < 0.6f) {
                        actionClass = "do";
                        bestScore = 1.0f - bestScore;
                    }

                    /* 判断为noise的情况时，将其对应的置信度设为0。
                    这样，视频分析线程分析所得的“noise”在后续的决策窗口中就会因为置信度为0的缘故变为 0.
                    (等价于null，不参与决策) */
                    if ("Noise".equals(actionClass)) {
                        actionClass = "Noise";
                        bestScore = 0.0f;
                    }
                    
                    // 更新最新的视频分析结果（原子操作，线程安全）
                    latestVideoAction = actionClass;
                    latestVideoConfidence = bestScore;
                    latestVideoTimestamp = System.currentTimeMillis();
                    
                    updateLabel(tvVideoAction, "Video Action: " + latestVideoAction +
                            String.format(" (%.2f%%)", bestScore * 100));

                    // 记录平滑历史
                    synchronized (recentVideo) {
                        recentVideo.addLast(new TimedLabel(currentMs, latestVideoAction));
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void audioAnalysisCycle() {
        if (analysisPaused.get()) return;
        if (!running.get()) return;
        long currentMs = getCurrentPositionMs();
        
        // 统一时序：使用视频时间差来计算缓冲等待
        long bufferWaitTime = currentMs - audioStartWallMs;
        if (bufferWaitTime <= 4_000) {
            log(String.format("[Audio] Waiting for PCM buffer warm-up... (%dms video time elapsed, need 4000ms)", bufferWaitTime));
            return;
        }
        
        if (currentMs - lastAudioInferMs < AUDIO_STEP_MS) {
            log(String.format("[Audio] Throttling: %dms since last inference, need %dms", 
                currentMs - lastAudioInferMs, AUDIO_STEP_MS));
            return; // 1s 步长节流
        }

        float[] pcm = pcmBuffer.readWindowRelaxed(currentMs, AUDIO_REQUIRED_POINTS);
        if (pcm == null) return;
        
        /*
        // === 节律估计：每 ~1 秒推送一次 16k 采样，并在预热(4s)后估计 ===
        float[] last1s = pcmBuffer.readWindowRelaxed(currentMs, 16_000);
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
        */
        // **************[MOD] 音频 Loudness → 档位分析（替代频率估计）**************
        float[] last1s = pcmBuffer.readWindowRelaxed(currentMs, 8_000); // 0.5秒, 16 kHz/s
        if (last1s != null && last1s.length > 0) {
            loudnessEstimator.push(last1s);
            AudioLoudnessLevelEstimator.Result lr = loudnessEstimator.estimate(System.currentTimeMillis());

            latestAudioLoudLevel = lr.level;
            latestAudioLoudConf  = lr.confidence;
            latestAudioLoudTsMs  = lr.timestampMs;
            latestAudioLoudValid = lr.valid;

            log(String.format("✅ [Loudness] valid=%s, level=%d, conf=%.2f, db=%.1f",
                    lr.valid, lr.level, lr.confidence, lr.db));
        }
        // **********************************************************************

        float[] audioProbs = audioHelper.predictProbs(pcm);
        if (audioProbs == null) return;

        int audioClassIndex = argMax(audioProbs);
        float audioProb = audioProbs[audioClassIndex];
        float threshold = 0.0f;
        String audioClass = (audioProb < threshold) ? "Noise" : audioClasses[audioClassIndex];

        // 这里加入比例阈值过滤
        // 这里本质是比例阈值判定，噪声需要比目标类高50%才被认定，如果识别为 Noise 但置信度较低，则判定为 do
        if ("Noise".equals(audioClass) && audioProb < 0.6f) {
            audioClass = "do";
            audioProb = 1.0f - audioProb;
        }
        
        // 更新最新的音频分析结果（原子操作，线程安全）
        latestAudioAction = audioClass;
        latestAudioConfidence = audioProb;
        latestAudioTimestamp = System.currentTimeMillis();
        
        lastAudioInferMs = currentMs;
        updateLabel(tvAudioAction, "Audio Action: " + latestAudioAction +
                String.format(" (%.2f%%)", audioProb * 100));

        synchronized (recentAudio) {
            recentAudio.addLast(new TimedLabel(currentMs, latestAudioAction));
        }
    }

    private void fusionCycle() {
        long currentTime = System.currentTimeMillis();
        if (analysisPaused.get()) return;
        if (!running.get()) return;

        // 第一步：读取最新的分析结果（原子操作，线程安全）
        String videoAction = latestVideoAction;
        String audioAction = latestAudioAction;
        float videoConf = latestVideoConfidence;
        float audioConf = latestAudioConfidence;
        long videoTime = latestVideoTimestamp;
        long audioTime = latestAudioTimestamp;
        
        // 第二步：时间过滤 - 计算结果的新鲜度（毫秒）
        long videoAge = videoTime > 0 ? currentTime - videoTime : Long.MAX_VALUE;
        long audioAge = audioTime > 0 ? currentTime - audioTime : Long.MAX_VALUE;
        
        // 如果视频结果过期，清空它
        if (videoAge > MAX_AGE_MS) {
            videoAction = "";
            videoConf = 0f;
            log("[融合]视频结果过期(" + videoAge + "ms),已忽略");
        }
        
        // 如果音频结果过期，清空它
        if (audioAge > MAX_AGE_MS) {
            audioAction = "";
            audioConf = 0f;
            log("[融合]音频结果过期(" + audioAge + "ms),已忽略");
        }

        // 动作类型归一化："oral" 统一处理为 "do"
        if ("oral".equals(videoAction)) {
            videoAction = "do";
        }
        if ("oral".equals(audioAction)) {
            audioAction = "do";
        }

        // [MOD] 第三/四步：对齐 Android —— 由 smoothedFusion 自己维护历史并输出 finalAction
        String finalAction = smoothedFusion(videoAction, audioAction, videoConf, audioConf);

        // 第五步：读取音视频节律并融合为最终档位（对齐 Android）
        // 读取音频节律
        /*
        float audioFreq = latestAudioRhythmHz;
        float audioFreqConf = latestAudioRhythmConf;
        long audioFreqTs = latestAudioRhythmTsMs;
        boolean audioFreqValid = latestAudioRhythmValid;
        */
        float audioFreq = (float) latestAudioLoudLevel;   // 用 level 伪装成 “audioFreq”
        float audioFreqConf = latestAudioLoudConf;
        long audioFreqTs = latestAudioLoudTsMs;
        boolean audioFreqValid = latestAudioLoudValid;

        // 读取视频节律
        float videoFreq = latestVideoFreqHz;
        float videoFreqConf = latestVideoFreqConf;
        long videoFreqTs = latestVideoFreqTsMs;
        
        // 过滤过期的音频节律结果
        long audioFreqAge = audioFreqTs > 0 ? currentTime - audioFreqTs : Long.MAX_VALUE;
        if (audioFreqAge > MAX_AGE_MS || !audioFreqValid) {
            audioFreq = Float.NaN;
            audioFreqConf = 0f;
        }
        
        // 过滤过期的视频节律结果
        long videoFreqAge = videoFreqTs > 0 ? currentTime - videoFreqTs : Long.MAX_VALUE;
        if (videoFreqAge > MAX_AGE_MS) {
            videoFreq = Float.NaN;
            videoFreqConf = 0f;
        }
        
        // 融合音视频节律（对齐 Android：临时采用音频节律作为最终节律）
        // TODO: 后续可修改
        // 将“最终节律计算”封装为独立方法，便于后续替换为音视频融合节律
        int finalLevel = computeFinalFreq(audioFreq, audioFreqConf, videoFreq, videoFreqConf);
        
        // 第六步：使用蓝牙发送状态管理器，实现二级平滑策略 + 档位确认
        managedBluetoothUpdate(finalAction, finalLevel);
        
        // 第七步：更新UI显示
        // 计算是否需要刷新UI：动作变化 或 档位变化
        boolean actionChanged = !finalAction.equals(lastFinalAction);
        boolean levelChanged  = (finalLevel != currentLevel);
        if (actionChanged || levelChanged) {
            lastFinalAction = finalAction;
            String bluetoothStatus = currentBluetoothState.isEmpty() ? "等待中" : currentBluetoothState;
            updateLabel(tvOverlay, "Video:" + videoAction + " | Audio:" + audioAction + " | Final:" + finalAction + 
                       " | 档位:" + finalLevel + " | 蓝牙:" + bluetoothStatus);
        }
    }


    /**
     * 12.11 计算最终节律档位（0..10）。
     *
     * <p>当前策略：仅使用音频节律映射为档位，并做“置信度阈值 + 方向性门控（涨档更严格，降档更宽松）”。
     * 预留 videoFreq/videoFreqConf 参数，便于未来扩展为音视频融合节律。</p>
     */
    private int computeFinalFreq(float audioFreq, float audioFreqConf, float videoFreq, float videoFreqConf) {
        // 临时采用音频节律作为最终节律
        // int finalFreq = mapFreqToLevel(audioFreq);
        int finalFreq = clampLevelFromLoudness(audioFreq); // [MOD] audioFreq 实际是 level(float)

        int videoFre = mapFreqToLevel(videoFreq);

        // === 12.12: 方向性置信度门控（涨档更严格，降档更宽松） ===
        {
            float conf = audioFreqConf;      // 当前这帧的置信度

            // 三个可调参数
            final float CONF_IGNORE = 0.10f;  // 极低置信度：整体忽略本次节律
            final float CONF_UP     = 0.10f;  // 涨档所需置信度（更严格）
            final float CONF_DOWN   = 0.10f;  // 降档所需置信度（相对宽松）

            int curLevel       = currentLevel;  // 当前已生效档位（0..10）
            int candidateLevel = finalFreq;     // 本次根据 audioFreq 映射出来的档位

            // 1) 极低置信度：直接清空本次节律，维持 currentLevel
            if (Float.isNaN(audioFreq) || conf < CONF_IGNORE) {
                finalFreq = curLevel;  // 不给 updateBluetoothState 提供变档机会
            } else {
                // 2) 根据档位变动方向应用不同门槛
                if (candidateLevel > curLevel && conf < CONF_UP) {
                    // 尝试“涨档”但置信度不足 → 不允许涨档
                    finalFreq = curLevel;
                } else if (candidateLevel < curLevel && conf < CONF_DOWN) {
                    // 尝试“降档”但置信度也太低 → 不允许降档（可视需要放宽）
                    finalFreq = curLevel;
                }
                // candidateLevel == curLevel 时无需处理
            }
        }

        return finalFreq;
    }

    /* ------------------------------------------------------------------ */
    /*                             决策融合函数                             */
    /* ------------------------------------------------------------------ */

    // =========================
    // [ADD] Android 对齐版：决策窗口平滑融合（smoothedFusion）
    // 目的：让 Windows finalAction 决策逻辑与 Android smoothedFusion/selectBestAction 看齐
    // =========================
    private String smoothedFusion(String videoAction, String audioAction, float videoConf, float audioConf) {
        synchronized (historyLock) {
            // [ADD] 由 smoothedFusion 自己维护历史（对齐 Android）
            actionHistory.addLast(new ActionRecord(System.currentTimeMillis(),
                    videoAction == null ? "" : videoAction,
                    audioAction == null ? "" : audioAction,
                    videoConf,
                    audioConf));

            while (actionHistory.size() > DECISION_WINDOW_SIZE) {
                actionHistory.removeFirst();
            }

            if (actionHistory.size() < 3) {
                return selectBestAction(videoAction, audioAction, videoConf, audioConf);
            }

            Map<String, Float> actionScores = new HashMap<>();
            Map<String, Integer> actionCounts = new HashMap<>();

            int index = 0;
            int windowSize = actionHistory.size();

            for (ActionRecord record : actionHistory) {
                float weight = (float) (index + 1) / windowSize;

                // 视频：过滤 Background（对齐 Android）
                if (record.videoAction != null && !record.videoAction.isEmpty()
                        && !"Background".equals(record.videoAction)) {
                    String key = record.videoAction;
                    float score = actionScores.getOrDefault(key, 0f);
                    score += record.videoConfidence * weight * VIDEO_WEIGHT_FACTOR; // 0.7
                    actionScores.put(key, score);
                    actionCounts.put(key, actionCounts.getOrDefault(key, 0) + 1);
                }

                // 音频：不强制过滤 Noise（对齐 Android：计分阶段允许 Noise 进入，最终由出现次数/回退策略处理）
                if (record.audioAction != null && !record.audioAction.isEmpty()) {
                    String key = record.audioAction;
                    float score = actionScores.getOrDefault(key, 0f);
                    score += record.audioConfidence * weight * AUDIO_WEIGHT_FACTOR; // 1.3
                    actionScores.put(key, score);
                    actionCounts.put(key, actionCounts.getOrDefault(key, 0) + 1);
                }

                index++;
            }

            String bestAction = "";
            float bestScore = 0f;

            for (Map.Entry<String, Float> entry : actionScores.entrySet()) {
                String action = entry.getKey();
                float score = entry.getValue();
                int count = actionCounts.getOrDefault(action, 0);

                if (count >= MIN_OCCURRENCE_THRESHOLD && score > bestScore) {
                    bestScore = score;
                    bestAction = action;
                }
            }

            if (bestAction.isEmpty()) {
                ActionRecord latest = actionHistory.getLast();
                bestAction = selectBestAction(latest.videoAction, latest.audioAction,
                        latest.videoConfidence, latest.audioConfidence);
            }

            return bestAction;
        }
    }

    // =========================
    // [ADD] Android 对齐版：窗口不足/回退策略（selectBestAction）
    // 关键差异：两者都空且没有 Noise 时返回 ""（而不是 "Noise"）
    // =========================
    private String selectBestAction(String videoAction, String audioAction, float videoConf, float audioConf) {
        String a = (audioAction == null) ? "" : audioAction;
        String v = (videoAction == null) ? "" : videoAction;

        // 音频优先
        if (!a.isEmpty()) {
            if (!"Noise".equals(a) || audioConf > 0.7f) {
                return a;
            }
        }

        // 视频次之（过滤 Background）
        if (!v.isEmpty() && !"Background".equals(v)) {
            if (!"Noise".equals(v) || videoConf > 0.7f) {
                return v;
            }
        }

        // 若明确出现 Noise，则输出 Noise
        if ("Noise".equals(a) || "Noise".equals(v)) {
            return "Noise";
        }

        // 否则返回空（对齐 Android：无结论 → 不发蓝牙）
        return "";
    }




    /* ------------------------------------------------------------------ */
    /*                             辅助函数                               */
    /* ------------------------------------------------------------------ */

    /**
     * 处理ST-GCN++的8类输出，合并为3类并使用比例阈值判定
     * @param probs ST-GCN++输出的8类概率 [label0, label1, ..., label7]
     * @return 最终动作类别 ("oral", "do", "Noise")
     */
    // 使用 ActionUtils 统一实现
    private String processStgcnOutput(float[] probs) {
        return ActionUtils.processStgcnOutput(probs);
    }
    
    /**
     * 获取最终动作对应的最佳得分，用于UI显示
     * @param probs ST-GCN++输出的8类概率
     * @param actionClass 最终确定的动作类别
     * @return 对应的概率得分
     */
    private float getBestScore(float[] probs, String actionClass) {
        return ActionUtils.getBestScore(probs, actionClass);
    }

    // 增加 softmax 计算和阈值判断
    // 统一改用 ActionUtils.softmax
    private float[] softmax(float[] logits) { return ActionUtils.softmax(logits); }

    /**
     * 复刻 MMAction2 PreNormalize2D（align_center=True）
     * 输入:  float[T][V][3]  —— 本窗口内 T 帧, 17 关键点 (x,y,conf)
     * 输出:  同维度 float[][][] ，已做中心化+等比缩放
     */
    // 统一改用 ActionUtils.preNormalize2D
    private static float[][][] preNormalize2D(float[][][] window) { return ActionUtils.preNormalize2D(window); }



    private void onUserSeek(long toMs) {
        log("user seek → " + toMs + " ms, clear the buffer and resynchronize (debounce)");
        // ✅ 取消之前排队的任务
        if (pendingSeekFuture != null && !pendingSeekFuture.isDone()) {
            pendingSeekFuture.cancel(false);
        }
        // ✅ 500 ms 后只执行最后一次
        pendingSeekFuture = scheduler.schedule(() -> doSeekSync(toMs), 500, TimeUnit.MILLISECONDS);
    }

    private void doSeekSync(long toMs) {
        poseWindow.clear();
        pcmBuffer.reset();
        frameExtractor.seekTo(toMs * 1000L);
        audioDecoder.seekTo((int) toMs);
        audioStartWallMs = toMs; // 重新记录视频位置用于缓冲等待
        videoEnded = false;
        lastFinalAction = "";
        lastAudioInferMs = 0;
        stgcnStrideCountdown = 0; // 拖动/seek 后立即允许重新推理

        // 清空所有动作识别结果缓存
        latestVideoAction = "";
        latestAudioAction = "";
        
        // 重置时间戳和置信度
        latestVideoConfidence = 0f;
        latestAudioConfidence = 0f;
        latestVideoTimestamp = 0;
        latestAudioTimestamp = 0;
        
        // 清空决策历史窗口（Seek操作特殊处理：避免时间错位）
        synchronized (historyLock) {
            actionHistory.clear();
            log("决策窗口：已清空历史记录队列（用户拖拽seek操作）");
        }
        
        // 重置蓝牙状态管理器的所有状态
        resetBluetoothStateManager();
        // ★ 新增：清空挂起状态
        suspendedBluetoothState = "";
        suspendedLevel = 0;
        
        // 重置节律估计器与缓存（音频 + 视频）
        /*
        audioRhythmEstimator.reset();
        latestAudioRhythmHz = Float.NaN;
        latestAudioRhythmConf = 0f;
        latestAudioRhythmTsMs = 0L;
        latestAudioRhythmValid = false;
        */
        // [12.30] reset loudness estimator & outputs
        loudnessEstimator.reset();
        latestAudioLoudLevel = 0;
        latestAudioLoudConf  = 0f;
        latestAudioLoudTsMs  = 0L;
        latestAudioLoudValid = false;

        videoRhythmEstimator.reset();
        latestVideoFreqHz = Float.NaN;
        latestVideoFreqConf = 0f;
        latestVideoFreqTsMs = 0L;
        
        // 清空平滑窗口历史数据（保留旧的兼容性）
        synchronized (recentVideo) { recentVideo.clear(); }
        synchronized (recentAudio) { recentAudio.clear(); }
        
        if (analysisPaused.get() && mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            resumeAnalysis();                          // ✅ 如已暂停且视频在播 → 恢复分析
        }
        
        log("蓝牙管理器：已完全重置所有动作缓存和状态（用户拖拽到 " + toMs + "ms）");
        log("决策函数：seek操作已清空所有历史数据和缓冲，重新开始分析");
    }

    private long getCurrentPositionMs() {
        return (long) mediaPlayer.getCurrentTime().toMillis();
    }

    private void pauseAnalysis() {
        analysisPaused.set(true);

        // ★ 新增：挂起当前蓝牙动作并发送停止信号
        if (!currentBluetoothState.isEmpty() && !"Noise".equals(currentBluetoothState)) {
            suspendedBluetoothState = currentBluetoothState;
            suspendedLevel = currentLevel;
            log("[暂停] 挂起动作: " + suspendedBluetoothState + ", 档位: " + suspendedLevel);
        }
        if (bleManager != null && bleManager.isConnected() && !bleManager.isPaused()) {
            bleManager.sendAction("Noise", 0);
            log("[暂停] 已发送停止信号(Noise)");
        }
    }
    private void resumeAnalysis() {
        analysisPaused.set(false);

        // ★ 新增：恢复挂起的蓝牙动作
        if (!suspendedBluetoothState.isEmpty()) {
            log("[恢复] 恢复动作: " + suspendedBluetoothState + ", 档位: " + suspendedLevel);
            currentBluetoothState = suspendedBluetoothState;
            currentLevel = suspendedLevel;
            currentStateStartTime = System.currentTimeMillis();
            currentLevelSinceMs = System.currentTimeMillis();

            if (bleManager != null && bleManager.isConnected() && !bleManager.isPaused()) {
                bleManager.sendAction(suspendedBluetoothState, suspendedLevel);
                lastBluetoothSendTime = System.currentTimeMillis();
                lastSentLevel = suspendedLevel;
                log("[恢复] 已发送恢复动作: " + suspendedBluetoothState + " 档位: " + suspendedLevel);
            }

            suspendedBluetoothState = "";
            suspendedLevel = 0;
        }
    }

    /**
     * 转换滑动窗口为 ST-GCN++ 输入
     */
    private float[][][] convertPoseWindowToInput(ArrayDeque<float[][]> window) {
        float[][][] input = new float[WINDOW_SIZE][17][3];
        int idx = 0;
        for (float[][] kpts : window) input[idx++] = kpts;
        return input;
    }

    /**
     * 返回最大得分对应的索引
     */
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

    // 旧的 fuse 保留以防切回，当前未使用
    @SuppressWarnings("unused")
    private static String fuse(String vid, String aud) {
        if (!vid.isEmpty() && !aud.isEmpty()) return vid; // 旧的简单策略（仍保留备用）
        return vid.isEmpty() ? aud : vid;
    }

    /* ------------------------------------------------------------------ */
    /*                        频率→档位映射（对齐 Android）                 */
    /* ------------------------------------------------------------------ */
    
    /**
     * 频率档位映射表（0..10档，index 0 为停止，1..10 为实际档位）
     * 根据工厂实际标定值调整区间
     */
    /* 频率->10档映射占位表（index 1..10：对应档位1~10；0为停止）
    “周期性事件”指的是：一次完整的抽插周期（前推 + 后拉，或一次节律峰到下一次节律峰）
    1 Hz = 1 次/秒 的完整抽插周期
     * 频率 -> 10 档映射（真实机械频率）
     * 说明：
     * - 1 次抽插 = 马达 3 转
     * - freq = RPM / 180
     * - 当前硬件可达范围 ≈ 1.0 – 1.8 Hz
     */
    private static final float[][] LEVEL_RANGES = new float[][]{
            null,                // 0 占位（停止）
            {0.95f, 1.12f},      // 档1 ≈ 190 RPM (1.06 Hz)
            {1.12f, 1.27f},      // 档2 ≈ 220 RPM (1.22 Hz)
            {1.27f, 1.40f},      // 档3 ≈ 240 RPM (1.33 Hz)
            {1.40f, 1.53f},      // 档4 ≈ 270 RPM (1.50 Hz)
            {1.53f, 1.58f},      // 档5 ≈ 280 RPM (1.56 Hz)
            {1.58f, 1.63f},      // 档6 ≈ 290 RPM (1.61 Hz)
            {1.63f, 1.66f},      // 档7 ≈ 295 RPM (1.64 Hz)
            {1.66f, 1.70f},      // 档8 ≈ 300 RPM (1.67 Hz)
            {1.70f, 1.75f},      // 档9 ≈ 310 RPM (1.72 Hz)
            {1.75f, 1.85f}       // 档10 ≈ 320 RPM (1.78 Hz)
    };
    
    /**
     * 将频率（Hz）映射为 0..9 档（0为停止）
     * @param hz 音频节律频率
     * @return 档位 0..9
     */
    private static int mapFreqToLevel(final float hz) {
        if (Float.isNaN(hz) || hz <= 0f) return 0;
        for (int lvl = 1; lvl <= 10; lvl++) {
            float[] r = LEVEL_RANGES[lvl];
            if (r != null && hz >= r[0] && hz < r[1]){
                return Math.min(lvl, 9);
            }
        }
        return 9; // 超出则钳到最高档
    }

    // [12.30] audioFreq 实际承载的是 loudness level（float），这里做 0..9 钳制
    private static int clampLevelFromLoudness(final float levelLike) {
        if (Float.isNaN(levelLike)) return 0;
        int lv = Math.round(levelLike);
        if (lv < 0) lv = 0;
        if (lv > 9) lv = 9;
        return lv;
    }


    // 迟滞阈值（Schmitt）
    private static int upThreshold(int cur)   { return Math.min(10, cur + 1); }
    private static int downThreshold(int cur) { return Math.max(0,  cur - 1); }

    // 门控——是否属于做爱大类/是否允许变速（按你 Windows 的动作命名规则改）
    private static boolean isSexAction(String action) {
        return "do".equalsIgnoreCase(action)
                || "oral".equalsIgnoreCase(action)   // [ADD]
                || "sex".equalsIgnoreCase(action);
    }
    private boolean currentStateSupportsSpeed() {
        // 如需限制某些模式固定速度，可在这里判断 currentBluetoothState
        return true;
    }

    /**
     * 蓝牙发送状态管理器 - 实现二级平滑策略 + 档位确认（对齐 Android）
     *
     * @param finalAction 融合决策层产生的动作
     * @param finalFreq 从音频节律映射的档位 (0..10)
     */
    private void managedBluetoothUpdate(String finalAction, int finalFreq) {
        long currentTime = System.currentTimeMillis();
        
        // 仅忽略空动作；Noise 需要进入状态机用于“停止/不转”
        if (finalAction == null || finalAction.isEmpty()) {
            return;
        }
        
        // 检查蓝牙是否已暂停（本地按键优先）
        if (bleManager != null && bleManager.isPaused()) {
            log("蓝牙管理器：本地按键优先，App操作已暂停");
            return;
        }
        // levelToSend 不再直接透传 finalFreq，而是先做档位确认
        int levelToSend = finalFreq;
        // =====================[ 档位确认（迟滞 + 短稳 + 最小驻留） ]=====================
        boolean supportsLevel = isSexAction(finalAction) && currentStateSupportsSpeed(); // NEW

        if (supportsLevel) {
            int latestLevel = finalFreq; // 0..10

            // 迟滞：只有跨过 currentLevel±1 才认为值得处理
            if (latestLevel >= upThreshold(currentLevel) || latestLevel <= downThreshold(currentLevel)) {

                // 新候选档位出现：开始计时
                if (pendingLevel == null || pendingLevel.intValue() != latestLevel) {
                    pendingLevel = latestLevel;
                    pendingLevelSinceMs = currentTime;
                } else {
                    long dwell = currentTime - pendingLevelSinceMs;
                    boolean stableOk = (dwell >= LEVEL_STABLE_MS); // 800ms = 1个融合tick

                    // 当前档位已驻留足够久，且候选档位稳定 ≥800ms → 切换生效
                    if (stableOk && (currentTime - currentLevelSinceMs >= LEVEL_MIN_DUR_MS)) {
                        currentLevel = pendingLevel;
                        currentLevelSinceMs = currentTime;
                    }
                }
            } else {
                // 未跨阈值：清空 pending，避免无意义计时
                pendingLevel = null;
            }

            // 最终用于发送的档位取已确认的 currentLevel
            levelToSend = currentLevel;
        } else {
            pendingLevel = 0;
            // 若非做爱动作，你也可以选择 levelToSend=0 或保持 finalFreq（看你协议策略）
        }
        // =====================档位确认结束 ======================

        // 第一层：检查待确认状态
        if (!finalAction.equals(pendingBluetoothState)) {
            // 新的动作出现，重新开始确认计时
            pendingBluetoothState = finalAction;
            pendingStateStartTime = currentTime;
            log("蓝牙管理器：检测到新动作 " + finalAction + "，开始稳定性确认");
            return;
        }
        
        // 第二层：检查稳定确认时间
        long pendingDuration = currentTime - pendingStateStartTime;
        // [ADD] 动作稳定确认时间：默认 1600ms；若从目标动作(do/oral)切到 Noise，则延长到 2400ms
        long actionStableMs = STABILITY_CONFIRM_TIME_MS;
        if (isSexAction(currentBluetoothState) && "Noise".equals(pendingBluetoothState)) {
            actionStableMs = 2400;
        }
        // [MOD] 使用动态稳定确认时间
        if (pendingDuration < actionStableMs) {
            // 动作还未稳定足够时间，继续等待
            return;
        }
        
        // ===== 情况 A：切换到"不同动作" =====
        if (!pendingBluetoothState.equals(currentBluetoothState)) {
            // 第三层：检查当前执行状态的最小持续时间
            if (!currentBluetoothState.isEmpty()) {
                long currentStateDuration = currentTime - currentStateStartTime;
                if (currentStateDuration < MIN_DURATION_TIME_MS) {
                    // 当前动作执行时间不足，不能切换
                    log("蓝牙管理器：当前动作 " + currentBluetoothState + " 执行时间不足 " + 
                        currentStateDuration + "ms，需要等待至少 " + MIN_DURATION_TIME_MS + "ms");
                    return;
                }
            }
            
            // 第四层：检查发送间隔
            long timeSinceLastSend = currentTime - lastBluetoothSendTime;
            if (timeSinceLastSend < SEND_INTERVAL_MS) {
                // 发送间隔时间不足，不能发送
                return;
            }
            
            // 所有条件满足，可以发送新的蓝牙指令（带档位）
            log("蓝牙管理器：发送稳定动作 " + pendingBluetoothState + 
                "（稳定时间：" + pendingDuration + "ms）");
            
            // 实际发送蓝牙指令（动作切换/含档位）
            updateBluetoothState(pendingBluetoothState, levelToSend);
            
            currentBluetoothState = pendingBluetoothState;
            currentStateStartTime = currentTime;
            lastBluetoothSendTime = currentTime;
            lastSentLevel = levelToSend; // 记录本次已下发的档位
        }
        // ===== 情况 B：动作未变，但节律档位数值发生变化 → 允许"重复发送同一动作以更新档位" =====
        else {
            // 仅当档位确实变化且达到发送节流间隔时才重发
            boolean levelChanged = (levelToSend != lastSentLevel);
            boolean gapOk = (currentTime - lastBluetoothSendTime) >= SEND_INTERVAL_MS;
            
            if (levelChanged && gapOk) {
                log("蓝牙管理器：同动作更新档位：" + currentBluetoothState + " -> level=" + levelToSend);
                
                updateBluetoothState(currentBluetoothState, levelToSend);
                lastSentLevel = levelToSend;
                lastBluetoothSendTime = currentTime;
            }
        }
    }
    
    /**
     * 重置蓝牙状态管理器的所有状态（包括档位状态）
     * 在seek、暂停、重新开始等场景下调用
     */
    private void resetBluetoothStateManager() {
        pendingBluetoothState = "";
        currentBluetoothState = "";
        pendingStateStartTime = 0;
        currentStateStartTime = 0;
        lastBluetoothSendTime = 0;
        
        // 重置档位状态
        lastSentLevel = 0;
        // 重置档位确认状态
        currentLevel = 1;
        currentLevelSinceMs = 0L;
        pendingLevel = null;
        pendingLevelSinceMs = 0L;
        log("蓝牙管理器：状态已完全重置（包括档位）");
    }
    
    /**
     * 发送蓝牙动作指令（带档位）
     */
    private void updateBluetoothState(String state, int level) {
        if (bleManager != null && state != null && !state.isEmpty()) {
            bleManager.sendAction(state, level);
            log("蓝牙发送：动作=" + state + ", 档位=" + level);
        }
    }
    
    /**
     * 发送蓝牙动作指令（不带档位，向后兼容）
     */
    @SuppressWarnings("unused")
    private void updateBluetoothState(String state) {
        if (bleManager != null && state != null && !state.isEmpty()) {
            bleManager.sendAction(state);
        }
    }

    private void updateLabel(Label lbl, String text) {
        Platform.runLater(() -> lbl.setText(text));
    }

    private void log(String msg) { System.out.println("[VideoProcess] " + msg); }

    /**
     * 验证视频格式是否被JavaFX MediaPlayer支持
     */
    private void validateVideoFormat(String fileExtension, String fileName) {
        System.out.println("[VideoProcess] 验证视频格式兼容性...");
        
        // JavaFX MediaPlayer支持的格式（有限）
        String[] supportedExtensions = {".mp4", ".m4v", ".m4a", ".mp3", ".wav", ".aiff", ".flv"};
        boolean isSupported = false;
        
        for (String ext : supportedExtensions) {
            if (fileExtension.equals(ext)) {
                isSupported = true;
                break;
            }
        }
        
        if (!isSupported) {
            System.err.println("⚠️ 警告: 文件扩展名 " + fileExtension + " 可能不被JavaFX MediaPlayer支持");
            System.err.println("   支持的格式: MP4, M4V, M4A, MP3, WAV, AIFF, FLV");
        } else {
            System.out.println("✅ 文件格式检查通过: " + fileExtension);
        }
        
        // 如果是MP4，进一步说明编码要求
        if (fileExtension.equals(".mp4") || fileExtension.equals(".m4v")) {
            System.out.println("📋 MP4格式提示:");
            System.out.println("   - 推荐视频编码: H.264 (AVC)");
            System.out.println("   - 推荐音频编码: AAC");
            System.out.println("   - 如果播放失败，请使用支持的编码器重新编码");
        }
    }
    
    /**
     * 显示错误对话框给用户
     */
    private void showErrorMessage(String title, String message) {
        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle(title);
                alert.setHeaderText("视频播放错误");
                alert.setContentText(message);
                
                // 设置对话框的父窗口
                alert.initOwner(stage);
                
                // 设置对话框大小可调整
                alert.setResizable(true);
                alert.getDialogPane().setPrefWidth(500);
                alert.getDialogPane().setPrefHeight(300);
                
                // 显示对话框
                alert.showAndWait();
            } catch (Exception e) {
                System.err.println("无法显示错误对话框: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /* ---------------------------- 资源关闭 ----------------------------- */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            log("close() ignored: already closed");
            return;
        }

        // 先发“停止请求”，让各循环自行退出（不要靠 interrupt）
        running.set(false);

        // ★ 新增：退出时发送停止信号
        if (bleManager != null && bleManager.isConnected()) {
            bleManager.sendAction("Noise", 0);
            log("[退出] 已发送停止信号(Noise)");
        }

        resetBluetoothStateManager();

        // 不要 cancel(true)；避免 interrupt 打断 native 调用
        if (videoLoopFuture != null) videoLoopFuture.cancel(false);
        if (audioLoopFuture != null) audioLoopFuture.cancel(false);
        if (fusionLoopFuture != null) fusionLoopFuture.cancel(false);

        // 优先温和关闭线程池并等待退出
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(800, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // 超时再兜底强杀
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // MediaPlayer 的 stop/dispose 放到 FX 线程执行（避免 native 竞态）
        if (mediaPlayer != null) {
            MediaPlayer mp = mediaPlayer;
            mediaPlayer = null;
            try {
                if (javafx.application.Platform.isFxApplicationThread()) {
                    safeDisposeMediaPlayer(mp);
                } else {
                    javafx.application.Platform.runLater(() -> safeDisposeMediaPlayer(mp));
                }
            } catch (Throwable t) {
                log("MediaPlayer dispose error: " + t);
            }
        }

        // 其他资源：确保此时不会再有 loop 访问它们
        if (frameExtractor != null) {
            try { frameExtractor.close(); } catch (Throwable t) { log("frameExtractor close err: " + t); }
            frameExtractor = null;
        }

        if (audioDecoder != null) {
            try { audioDecoder.stop(); } catch (Throwable t) { log("audioDecoder stop err: " + t); }
            audioDecoder = null;
        }

        if (inferenceHelper != null) {
            try { inferenceHelper.close(); } catch (Throwable t) { log("inferenceHelper close err: " + t); }
            inferenceHelper = null;
        }

        if (audioHelper != null) {
            try { audioHelper.close(); } catch (Throwable t) { log("audioHelper close err: " + t); }
            audioHelper = null;
        }

        log("VideoProcessController 已完全关闭，所有资源和状态已清理");
    }

    private void safeDisposeMediaPlayer(MediaPlayer mp) {
        try { mp.stop(); } catch (Throwable ignored) {}
        try { mp.dispose(); } catch (Throwable ignored) {}
    }

    public Runnable getOnBack() {
        return onBack;
    }
}
