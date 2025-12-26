package org.example;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Windows屏幕捕获辅助类
 * 使用Robot类实现屏幕截图功能，用于在线模式的视频分析
 */
public class ScreenCaptureHelper {
    private static final String TAG = "ScreenCaptureHelper";
    
    // 捕获参数
    private static final int CAPTURE_INTERVAL_MS = 100; // 100ms一帧，10fps
    private static final float SCALE_FACTOR = 0.5f; // 降低分辨率到50%提高性能
    
    private Robot robot;
    private Rectangle screenBounds;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> captureTask;
    private volatile boolean isCapturing = false;
    
    // 帧回调接口
    public interface FrameCallback {
        void onFrameCaptured(BufferedImage frame);
    }
    
    private FrameCallback frameCallback;
    
    public ScreenCaptureHelper() throws AWTException {
        // 初始化Robot
        robot = new Robot();
        robot.setAutoDelay(0); // 禁用自动延迟
        robot.setAutoWaitForIdle(false); // 禁用等待空闲
        
        // 获取主屏幕大小
        screenBounds = getMainScreenBounds();
        System.out.println("[ScreenCapture] Initialized, screen size: " + screenBounds.width + "x" + screenBounds.height);
        
        // 创建线程池
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ScreenCapture");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * 获取主屏幕边界
     */
    private Rectangle getMainScreenBounds() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();
        
        // 使用主屏幕
        if (screens.length > 0) {
            Rectangle bounds = screens[0].getDefaultConfiguration().getBounds();
            System.out.println("[ScreenCapture] Primary screen: " + bounds);
            return bounds;
        }
        
        // 备选方案：使用工具包获取屏幕大小
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        return new Rectangle(0, 0, screenSize.width, screenSize.height);
    }
    
    /**
     * 开始屏幕捕获
     * @param callback 帧回调函数
     */
    public synchronized void startCapture(FrameCallback callback) {
        if (isCapturing) {
            System.out.println("[ScreenCapture] Already running, ignoring duplicate start");
            return;
        }
        
        this.frameCallback = callback;
        isCapturing = true;
        
        System.out.println("[ScreenCapture] Started capturing, interval: " + CAPTURE_INTERVAL_MS + "ms, scale: " + SCALE_FACTOR);
        
        captureTask = scheduler.scheduleAtFixedRate(this::captureFrame, 0, 
                                                  CAPTURE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 停止屏幕捕获
     */
    public synchronized void stopCapture() {
        if (!isCapturing) {
            return;
        }
        
        isCapturing = false;
        if (captureTask != null) {
            captureTask.cancel(false);
            captureTask = null;
        }
        
        System.out.println("[ScreenCapture] Screen capture stopped");
    }
    
    /**
     * 捕获单帧
     */
    private void captureFrame() {
        if (!isCapturing || frameCallback == null) {
            return;
        }
        
        try {
            // 捕获全屏
            BufferedImage fullScreen = robot.createScreenCapture(screenBounds);
            
            // 缩放以提高性能
            BufferedImage scaledImage = scaleImage(fullScreen, SCALE_FACTOR);
            
            // 调用回调
            frameCallback.onFrameCaptured(scaledImage);
            
        } catch (Exception e) {
            System.err.println("[ScreenCapture] Frame capture failed: " + e.getMessage());
            // 不打印完整堆栈，避免日志污染
        }
    }
    
    /**
     * 缩放图像
     */
    private BufferedImage scaleImage(BufferedImage original, float scale) {
        if (scale == 1.0f) {
            return original;
        }
        
        int newWidth = Math.round(original.getWidth() * scale);
        int newHeight = Math.round(original.getHeight() * scale);
        
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g2d = scaled.createGraphics();
        
        // 使用双线性插值提高质量
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        
        g2d.drawImage(original, 0, 0, newWidth, newHeight, null);
        g2d.dispose();
        
        return scaled;
    }
    
    /**
     * 单次截图（同步方法）
     */
    public BufferedImage captureScreenOnce() {
        try {
            BufferedImage fullScreen = robot.createScreenCapture(screenBounds);
            return scaleImage(fullScreen, SCALE_FACTOR);
        } catch (Exception e) {
            System.err.println("[ScreenCapture] Single screenshot failed: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取当前屏幕尺寸
     */
    public Dimension getScaledScreenSize() {
        return new Dimension(
            Math.round(screenBounds.width * SCALE_FACTOR),
            Math.round(screenBounds.height * SCALE_FACTOR)
        );
    }
    
    /**
     * 检查是否正在捕获
     */
    public boolean isCapturing() {
        return isCapturing;
    }
    
    /**
     * 关闭资源
     */
    public void close() {
        stopCapture();
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
        System.out.println("[ScreenCapture] Resources closed");
    }
}
