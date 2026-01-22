package org.example;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
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
    private Button exitButton;
    private Label statusLabel;
    
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
            videoActionLabel = new Label("视频分析动作(V): --");
            audioActionLabel = new Label("音频分析动作(A): --");
            fusedActionLabel = new Label("蓝牙发送动作(融合): --");
            statusLabel = new Label("状态: 正在初始化...");
            
            exitButton = new Button("退出");
            exitButton.setStyle("-fx-background-color: #ff4444; -fx-text-fill: white; -fx-font-weight: bold;");
            exitButton.setOnAction(e -> exitOnlineMode());
            
            // 设置标签样式
            String labelStyle = "-fx-font-size: 12px; -fx-padding: 2px;";
            videoActionLabel.setStyle(labelStyle);
            audioActionLabel.setStyle(labelStyle);
            fusedActionLabel.setStyle(labelStyle);
            statusLabel.setStyle(labelStyle + "-fx-font-weight: bold;");
            
            // 布局
            VBox root = new VBox(8);
            root.setPadding(new Insets(10));
            root.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1px;");
            
            root.getChildren().addAll(
                statusLabel,
                videoActionLabel,
                audioActionLabel,
                fusedActionLabel,
                exitButton
            );
            
            Scene scene = new Scene(root, 300, 150);
            floatingWindow.setScene(scene);
            
            // 设置窗口位置（右上角）
            floatingWindow.setX(javafx.stage.Screen.getPrimary().getVisualBounds().getMaxX() - 320);
            floatingWindow.setY(20);
            
            // 窗口关闭处理
            floatingWindow.setOnCloseRequest(e -> exitOnlineMode());
            
            floatingWindow.show();
            
            System.out.println("[在线模式] 悬浮窗已创建");
        });
    }
    
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
                String text = String.format("视频分析动作(V): %s (%.1f%%)", action, confidence * 100);
                videoActionLabel.setText(text);
            });
        }
        
        @Override
        public void onAudioActionUpdated(String action, float confidence) {
            Platform.runLater(() -> {
                String text = String.format("音频分析动作(A): %s (%.1f%%)", action, confidence * 100);
                audioActionLabel.setText(text);
            });
        }
        
        @Override
        public void onFusedActionUpdated(String fusedAction) {
            Platform.runLater(() -> {
                String text = String.format("蓝牙发送动作(融合): %s", fusedAction);
                fusedActionLabel.setText(text);
                
                // 注意：OnlineAnalysisService 中的 fusionCycle 已经处理了蓝牙发送
                // 这里只更新UI显示，不需要再次发送
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
