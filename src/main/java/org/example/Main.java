package org.example;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.net.URI;

/**
 * 桌面版入口窗口，等价于 Android 的 MainActivity：
 * 1. 选择本地视频 → 新开窗启动 VideoProcessController
 * 2. 连接蓝牙设备 → 弹出 BluetoothConnectDialog 供用户挑选
 */
public class Main extends Application {

    private static final String RESUME_BTN_DISABLED_STYLE =
            "-fx-background-color: #9E9E9E; -fx-text-fill: white; -fx-font-weight: bold;";
    private static final String RESUME_BTN_ENABLED_STYLE =
            "-fx-background-color: #FF8C00; -fx-text-fill: white; -fx-font-weight: bold;";

    private Button btnScanConnect;  // 蓝牙连接按钮

    // 静态初始化块 - 在所有代码执行前运行
    static {
        // 首先修复控制台编码
        try {
            ConsoleUtils.fixConsoleEncoding();
        } catch (Exception e) {
            System.err.println("控制台编码设置失败: " + e.getMessage());
        }
        
        // 检查 VC++ Runtime
        if (!checkVCRedist()) {
            int result = JOptionPane.showConfirmDialog(null,
                    "检测到系统缺少必要的运行库。\n" +
                            "本程序需要 Microsoft Visual C++ Redistributable 2015-2022 (x64)。\n\n" +
                            "是否立即下载并安装？\n" +
                            "（安装完成后请重新启动本程序）",
                    "缺少系统组件",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (result == JOptionPane.YES_OPTION) {
                try {
                    Desktop.getDesktop().browse(new URI("https://aka.ms/vs/17/release/vc_redist.x64.exe"));
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(null,
                            "无法打开下载页面，请手动下载：\n" +
                                    "https://aka.ms/vs/17/release/vc_redist.x64.exe",
                            "错误",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
            System.exit(0);
        }
    }

    // 检查 VC++ Redistributable 是否已安装
    private static boolean checkVCRedist() {
        try {
            // 尝试加载 VC++ Runtime DLL
            System.loadLibrary("vcruntime140");
            System.loadLibrary("msvcp140");
            return true;
        } catch (UnsatisfiedLinkError e) {
            // 检查注册表（备选方案）
            try {
                String[] cmd = {
                        "reg", "query",
                        "HKLM\\SOFTWARE\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64",
                        "/v", "Version"
                };
                Process process = Runtime.getRuntime().exec(cmd);
                int exitCode = process.waitFor();
                return exitCode == 0;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private Stage primaryStage;   // 全局保存
    private Scene mainScene;      // 主菜单 Scene
    private OnlineAnalysisActivity onlineAnalysis; // 在线分析活动
    private Button btnResumeControl;  // 恢复控制按钮

    @Override
    public void start(Stage primaryStage) {
        System.out.println("Current app.version = " + System.getProperty("app.version"));
        // 修复控制台编码问题
        ConsoleUtils.fixConsoleEncoding();
        ConsoleUtils.testChineseDisplay();
        
        this.primaryStage = primaryStage;
        
        // 初始化全局 BLEManager
        if (BLEManager.globalManager == null) {
            BLEManager.globalManager = new BLEManager();
            BLEManager.globalManager.setConnectionCallback(this::onBLEConnectionChanged);
            BLEManager.globalManager.setPauseCallback(this::onBLEPauseChanged);
        }

        /* --------- 主菜单 UI --------- */
        Button btnSelectVideo      = new Button("选择本地视频");
        Button btnOnlineMode       = new Button("在线模式");
        btnScanConnect = new Button("一键扫描并连接蓝牙设备");

        // 设置按钮样式
        btnOnlineMode.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        btnScanConnect.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;");
        
        // 恢复控制按钮（初始不可见）
        btnResumeControl = new Button("已暂停 app 操作，点击恢复");
        btnResumeControl.setFocusTraversable(false);
        updateResumeButtonState(false);

        VBox root = new VBox(12, btnSelectVideo, btnOnlineMode, btnScanConnect, btnResumeControl);
        root.setPadding(new Insets(35));
        mainScene = new Scene(root, 350, 360); // 增加宽度和高度容纳新按钮

        primaryStage.setTitle("X姬 – 主菜单");
        primaryStage.setScene(mainScene);
        primaryStage.show();

        // 启动/回到主界面时检查版本
        UpdateCheckerWin.checkForUpdates(primaryStage);

        /* --------- 选择视频 → 切换到分析页 --------- */
        btnSelectVideo.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择要分析的 MP4");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("视频文件", "*.mp4", "*.mkv", "*.avi"));
            File file = fc.showOpenDialog(primaryStage);
            if (file != null) {
                try {
                    // ① 不再 new Stage，而是把 primaryStage 传进去
                    // ② 同时把"回到主菜单"的 lambda 一并传进去
                    new VideoProcessController(
                            primaryStage,
                            Path.of(file.getAbsolutePath()),
                            this::backToMainMenu);   // Runnable
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        /* --------- 在线模式 → 启动在线分析 --------- */
        btnOnlineMode.setOnAction(e -> startOnlineMode());

        /* --------- 一键扫描并连接蓝牙 --------- */
        btnScanConnect.setOnAction(e -> {
            if (BLEManager.globalManager != null) {
                if (BLEManager.globalManager.isConnected()) {
                    BLEManager.globalManager.disconnect();
                } else {
                    BLEManager.globalManager.scanAndConnect();
                }
            }
        });
        
        /* --------- 恢复控制按钮 --------- */
        btnResumeControl.setOnAction(e -> {
            if (BLEManager.globalManager != null) {
                BLEManager.globalManager.sendResumeAppControl();
            }
        });
    }

    /** 启动在线模式 */
    private void startOnlineMode() {
        if (onlineAnalysis != null && onlineAnalysis.isRunning()) {
            System.out.println("[主菜单] 在线模式已在运行中");
            return;
        }
        
        System.out.println("[主菜单] 正在启动在线模式...");
        
        try {
            onlineAnalysis = new OnlineAnalysisActivity();
            boolean success = onlineAnalysis.startOnlineMode(() -> {
                // 在线模式退出回调
                System.out.println("[主菜单] 在线模式已退出");
                onlineAnalysis = null;
            });
            
            if (success) {
                System.out.println("[主菜单] ✅ 在线模式启动成功");
                System.out.println("现在可以返回桌面播放在线视频，悬浮窗将显示分析结果");
            } else {
                System.err.println("[主菜单] ❌ 在线模式启动失败");
                onlineAnalysis = null;
            }
        } catch (Exception ex) {
            System.err.println("[主菜单] 在线模式启动异常: " + ex.getMessage());
            ex.printStackTrace();
            onlineAnalysis = null;
            
            // 显示错误对话框
            showError("在线模式错误", "启动在线模式时发生错误：\n" + ex.getMessage() + 
                     "\n\n请检查：\n1. 麦克风权限\n2. 屏幕录制权限\n3. 系统兼容性");
        }
    }
    
    
    
    /** 显示错误对话框 */
    private void showError(String title, String message) {
        try {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText("错误");
            alert.setContentText(message);
            alert.initOwner(primaryStage);
            alert.showAndWait();
        } catch (Exception e) {
            System.err.println("无法显示错误对话框: " + e.getMessage());
        }
    }

    /** 回到主菜单：切回原来的 Scene，并改标题 */
    private void backToMainMenu() {
        // 如果在线模式正在运行，先关闭它
        if (onlineAnalysis != null && onlineAnalysis.isRunning()) {
            onlineAnalysis.close();
            onlineAnalysis = null;
        }
        
        primaryStage.setScene(mainScene);
        primaryStage.setTitle("X姬 – 主菜单");

        UpdateCheckerWin.checkForUpdates(primaryStage);
    }
    
    /**
     * BLE连接状态变化回调
     */
    private void onBLEConnectionChanged(Boolean connected) {
        System.out.println("[主菜单] BLE连接状态: " + (connected ? "已连接" : "未连接"));

        // 在 JavaFX 线程中更新 UI
        javafx.application.Platform.runLater(() -> {
            if (btnScanConnect != null) {
                if (connected) {
                    btnScanConnect.setText("断开连接");
                    btnScanConnect.setStyle("-fx-background-color: #F44336; -fx-text-fill: white; -fx-font-weight: bold;");
                } else {
                    btnScanConnect.setText("一键扫描并连接蓝牙设备");
                    btnScanConnect.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;");
                }
            }
        });
    }
    
    /**
     * BLE暂停状态变化回调
     */
    private void onBLEPauseChanged(Boolean paused) {
        System.out.println("[主菜单] BLE暂停状态: " + (paused ? "已暂停" : "已恢复"));
        updateResumeButtonState(paused);
    }

    private void updateResumeButtonState(boolean paused) {
        if (btnResumeControl == null) {
            return;
        }
        if (paused) {
            btnResumeControl.setDisable(false);
            btnResumeControl.setStyle(RESUME_BTN_ENABLED_STYLE);
        } else {
            btnResumeControl.setDisable(true);
            btnResumeControl.setStyle(RESUME_BTN_DISABLED_STYLE);
        }
    }

    public static void main(String[] args) { launch(args); }
}

