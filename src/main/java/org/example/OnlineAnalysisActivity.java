package org.example;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;  // === 新增: 导入HBox用于按钮水平排列 ===
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 在线分析活动
 * 提供悬浮窗界面显示实时分析结果
 * 相当于Android版本的OnlineAnalysisService
 */
public class OnlineAnalysisActivity {
    
    private Stage floatingWindow;
    private OnlineAnalysisService analysisService;
    
    // UI组件
    private Label videoActionLabel;
    private Label audioActionLabel;
    private Label fusedActionLabel;
    private Label fusedLevelLabel;
    private Button exitButton;
    private Label statusLabel;

    // === 新增: 最小化相关的UI组件和状态 ===
    private Button minimizeButton;      // 最小化按钮
    private VBox contentBox;            // 内容区域(最小化时隐藏)
    private boolean isMinimized = false; // 当前是否处于最小化状态
    private static final double NORMAL_WIDTH = 300;   // 正常宽度
    private static final double NORMAL_HEIGHT = 200;  // 正常高度(增大以容纳按钮栏)
    private static final double MINIMIZED_WIDTH = 150; // 最小化宽度(需容纳两个按钮)
    private static final double MINIMIZED_HEIGHT = 80; // 最小化高度(需容纳标题栏+按钮栏)
    // === 新增结束 ===
    
    // 回调接口
    public interface OnExitCallback {
        void onExitOnlineMode();
    }
    
    private OnExitCallback exitCallback;
    
    /**
     * 构造函数
     */
    public OnlineAnalysisActivity() {
        // OnlineAnalysisService 已经处理蓝牙发送，这里不需要额外的蓝牙助手
    }
    
    /**
     * 启动在线分析模式
     * WASAPI Loopback自动捕获系统默认输出设备，无需手动选择设备
     */
    public boolean startOnlineMode(OnExitCallback callback) {
        this.exitCallback = callback;
        
        System.out.println("[OnlineMode] Starting online analysis mode...");
        System.out.println("[OnlineMode] WASAPI Loopback will automatically capture system audio output");
        
        // 在后台线程执行耗时的初始化操作
        new Thread(() -> {
            try {
                // 初始化分析服务（自动使用系统默认输出设备）
                analysisService = new OnlineAnalysisService();
                
                System.out.println("[OnlineMode] Initializing service...");
                if (!analysisService.initialize()) {
                    Platform.runLater(() -> {
                        showError("Initialization Failed", 
                            "Failed to initialize online analysis service.\n\n" +
                            "Please check:\n" +
                            "1. System audio is working\n" +
                            "2. Screen capture permissions\n" +
                            "3. wasapi_loopback.dll is in the project directory");
                    });
                    return;
                }
                
                System.out.println("[OnlineMode] Service initialized, creating floating window...");
                
                // 在UI线程创建悬浮窗
                Platform.runLater(() -> {
                    createFloatingWindow();
                    
                    // 启动分析
                    if (!analysisService.startAnalysis(new AnalysisResultHandler())) {
                        showError("Start Failed", 
                            "Failed to start online analysis.\n\n" +
                            "Suggestions:\n" +
                            "1. Make sure system audio is playing\n" +
                            "2. Check screen capture permissions\n" +
                            "3. Restart the application");
                        return;
                    }
                    
                    System.out.println("[OnlineMode] Online analysis mode started successfully");
                });
                
            } catch (Exception e) {
                System.err.println("[OnlineMode] Failed to start: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    showError("Startup Error", "Error starting online mode:\n" + e.getMessage());
                });
            }
        }, "OnlineMode-Initializer").start();
        
        return true;
    }
    
    // 设备选择对话框已删除 - WASAPI Loopback自动使用系统默认输出设备
    // 加载对话框已删除 - 直接在后台初始化
    
    /**
     * 创建悬浮窗
     */
    private void createFloatingWindow() {
        Platform.runLater(() -> {
            floatingWindow = new Stage();
            floatingWindow.initStyle(StageStyle.UTILITY);
            floatingWindow.setTitle("X姬 - 在线分析");
            floatingWindow.setAlwaysOnTop(true);
            floatingWindow.setResizable(false);
            
            // 创建UI组件
            videoActionLabel = new Label("(V): --");
            audioActionLabel = new Label("(A): --");
            fusedActionLabel = new Label("final: --");
            fusedLevelLabel = new Label("Level: --");
            statusLabel = new Label("状态: 正在初始化...");
            
            exitButton = new Button("退出");
            exitButton.setStyle("-fx-background-color: #ff4444; -fx-text-fill: white; -fx-font-weight: bold;");
            exitButton.setOnAction(e -> exitOnlineMode());

            // === 新增: 创建最小化按钮 ===
            minimizeButton = new Button("_");
            minimizeButton.setStyle("-fx-background-color: #4488ff; -fx-text-fill: white; -fx-font-weight: bold; -fx-min-width: 30px;");
            minimizeButton.setTooltip(new Tooltip("最小化 / 还原"));
            minimizeButton.setOnAction(e -> toggleMinimize());
            // === 新增结束 ===
            
            // 设置标签样式
            String labelStyle = "-fx-font-size: 12px; -fx-padding: 2px;";
            videoActionLabel.setStyle(labelStyle);
            audioActionLabel.setStyle(labelStyle);
            fusedActionLabel.setStyle(labelStyle);
            fusedLevelLabel.setStyle(labelStyle);
            statusLabel.setStyle(labelStyle + "-fx-font-weight: bold;");

            // === 新增: 将退出按钮和最小化按钮放入水平布局(最小化在左,退出在右) ===
            HBox buttonBar = new HBox(8);
            buttonBar.getChildren().addAll(minimizeButton, exitButton);
            // === 新增结束 ===
            
            // === 新增: 将分析信息标签放入一个单独的VBox,方便最小化时隐藏 ===
            contentBox = new VBox(4);
            contentBox.getChildren().addAll(
                videoActionLabel,
                audioActionLabel,
                fusedActionLabel,
                fusedLevelLabel
            );
            // === 新增结束 ===
            
            // 布局
            VBox root = new VBox(8);
            root.setPadding(new Insets(10));
            root.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1px;");
            
            // === 修改: 使用contentBox和buttonBar替换原来的直接添加方式 ===
            root.getChildren().addAll(
                statusLabel,
                contentBox,   // 替换原来直接添加的四个label
                buttonBar     // 替换原来单独的exitButton
            );
            // === 修改结束 ===
            
            Scene scene = new Scene(root, NORMAL_WIDTH, NORMAL_HEIGHT);
            floatingWindow.setScene(scene);
            
            // 设置窗口位置（右上角）
            floatingWindow.setX(javafx.stage.Screen.getPrimary().getVisualBounds().getMaxX() - 320);
            floatingWindow.setY(20);
            
            // 窗口关闭处理
            floatingWindow.setOnCloseRequest(e -> exitOnlineMode());
            
            floatingWindow.show();
            floatingWindow.sizeToScene(); // === 新增: 自动调整窗口大小以适配所有内容 ===
            
            System.out.println("[在线模式] 悬浮窗已创建");
        });
    }

    // === 新增: 最小化/还原切换方法 ===
    /**
     * 切换最小化/还原状态
     * 最小化时隐藏分析内容,窗口缩小为一个小条
     * 还原时显示所有内容,窗口恢复原始大小
     */
    private void toggleMinimize() {
        isMinimized = !isMinimized;
        
        if (isMinimized) {
            // 最小化: 隐藏内容区域和状态标签,缩小窗口
            contentBox.setVisible(false);
            contentBox.setManaged(false);   // 不占用布局空间
            statusLabel.setVisible(false);
            statusLabel.setManaged(false);
            
            floatingWindow.setWidth(MINIMIZED_WIDTH);
            floatingWindow.setHeight(MINIMIZED_HEIGHT);
            
            // 更新按钮文字,提示可以还原
            minimizeButton.setText("□");
            minimizeButton.setTooltip(new Tooltip("还原窗口"));
            
            System.out.println("[在线模式] 悬浮窗已最小化");
        } else {
            // 还原: 显示所有内容,恢复窗口大小
            contentBox.setVisible(true);
            contentBox.setManaged(true);
            statusLabel.setVisible(true);
            statusLabel.setManaged(true);
            
            floatingWindow.setWidth(NORMAL_WIDTH);
            floatingWindow.setHeight(NORMAL_HEIGHT);
            floatingWindow.sizeToScene(); // === 新增: 还原时确保窗口适配内容 ===
            
            // 恢复按钮文字
            minimizeButton.setText("_");
            minimizeButton.setTooltip(new Tooltip("最小化"));
            
            System.out.println("[在线模式] 悬浮窗已还原");
        }
    }
    // === 新增结束 ===
    
    /**
     * 退出在线模式
     */
    private void exitOnlineMode() {
        System.out.println("[在线模式] 正在退出在线模式...");
        
        // 停止分析服务
        if (analysisService != null) {
            analysisService.stopAnalysis();
            analysisService.close();
            analysisService = null;
        }
        
        // 关闭悬浮窗
        Platform.runLater(() -> {
            if (floatingWindow != null) {
                floatingWindow.close();
                floatingWindow = null;
            }
        });
        
        // 调用回调
        if (exitCallback != null) {
            exitCallback.onExitOnlineMode();
        }
        
        System.out.println("[在线模式] ✅ 已退出在线模式");
    }
    
    /**
     * 显示错误对话框
     */
    private void showError(String title, String message) {
        Platform.runLater(() -> {
            try {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle(title);
                alert.setHeaderText("在线模式错误");
                alert.setContentText(message);
                alert.showAndWait();
            } catch (Exception e) {
                System.err.println("[在线模式] 无法显示错误对话框: " + e.getMessage());
            }
        });
    }
    
    /**
     * 分析结果处理器
     */
    private class AnalysisResultHandler implements OnlineAnalysisService.AnalysisResultCallback {
        
        @Override
        public void onVideoActionUpdated(String action, float confidence) {
            Platform.runLater(() -> {
                String text = String.format("(V): %s (%.1f%%)", action, confidence * 100);
                videoActionLabel.setText(text);
            });
        }
        
        @Override
        public void onAudioActionUpdated(String action, float confidence) {
            Platform.runLater(() -> {
                String text = String.format("(A): %s (%.1f%%)", action, confidence * 100);
                audioActionLabel.setText(text);
            });
        }
        
        @Override
        public void onFusedActionUpdated(String fusedAction) {
            Platform.runLater(() -> {
                String text = String.format("final: %s", fusedAction);
                fusedActionLabel.setText(text);
                
                // 注意：OnlineAnalysisService 中的 fusionCycle 已经处理了蓝牙发送
                // 这里只更新UI显示，不需要再次发送
            });
        }

        @Override
        public void onFusedLevelUpdated(int finalLevel) {
            Platform.runLater(() -> {
                String text = String.format("Level: %d", finalLevel);
                fusedLevelLabel.setText(text);
            });
        }
        
        @Override
        public void onAnalysisPausedChanged(boolean isPaused) {
            Platform.runLater(() -> {
                String status = isPaused ? "Status: Paused (No audio activity)" : "Status: Analyzing";
                statusLabel.setText(status);
                statusLabel.setStyle(isPaused ? 
                    "-fx-font-size: 12px; -fx-padding: 2px; -fx-font-weight: bold; -fx-text-fill: orange;" :
                    "-fx-font-size: 12px; -fx-padding: 2px; -fx-font-weight: bold; -fx-text-fill: green;"
                );
            });
        }
    }
    
    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return analysisService != null && analysisService.isAnalyzing();
    }
    
    /**
     * 手动关闭（清理资源）
     */
    public void close() {
        exitOnlineMode();
    }
}
