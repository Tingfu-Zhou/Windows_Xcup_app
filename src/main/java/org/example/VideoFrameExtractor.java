package org.example;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 桌面版视频抽帧工具 —— 等价于 Android 的 VideoFrameExtractor+EGLRenderer。
 *
 * <pre>
 *   • 内部持有一个 FFmpegFrameGrabber（打开一次即可重复 seek）
 *   • getFrameAt(timeUs)  →  返回 java.awt.image.BufferedImage
 *   • seekTo(timeUs)      →  主动跳转（下次 getFrameAt 会立刻生效）
 *   • release()           →  关闭解码器
 *
 *  ⚠ 性能说明
 *    - FFmpeg random seek 不是零成本；若 300 ms 抽 1 帧 OK。
 *    - 如需极限实时，可在主循环外建独立线程连续 grab，再按时间戳缓存。
 *    - 支持任意分辨率视频输入，YOLO8模型会自动处理尺寸适配
 * </pre>
 */
public class VideoFrameExtractor implements AutoCloseable {

    /* ---------------- 成员 ---------------- */
    private final FFmpegFrameGrabber grabber;
    private final Java2DFrameConverter converter = new Java2DFrameConverter();

    private volatile boolean started = false;

    /* ------------------------------------------------------------------ */
    /*                               构造                                 */
    /* ------------------------------------------------------------------ */

    /**
     * @param videoPath 本地视频文件路径
     * @throws IOException 打不开文件或启动解码器失败
     */
    public VideoFrameExtractor(Path videoPath) throws IOException {
        try {
            grabber = new FFmpegFrameGrabber(videoPath.toFile());
            // 不设置固定分辨率，保持原始分辨率以支持任意尺寸视频
            // YOLO8模型会自动处理尺寸适配
            grabber.start();
            started = true;
            System.out.println("[VideoFrameExtractor] Video opened: " + 
                             grabber.getImageWidth() + "x" + grabber.getImageHeight() + 
                             " @ " + grabber.getVideoFrameRate() + "fps");
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new IOException("Failed to start FFmpeg decoder: " + e.getMessage(), e);
        }
    }

    /* ------------------------------------------------------------------ */
    /*                            核心方法                                 */
    /* ------------------------------------------------------------------ */

    /**
     * 按"微秒"时间戳抓取一帧并返回 BufferedImage。
     * @param timeUs 目标时间，单位 μs（微秒）。同 Android 版接口保持一致。
     * @return 若无法抓到图像，返回 null
     * @throws IOException seek 或解码异常
     */
    public BufferedImage getFrameAt(long timeUs) throws IOException {
        if (!started) {
            throw new IOException("Frame grabber not started");
        }

        try {
            // 设置时间戳
            grabber.setTimestamp(timeUs);
            
            // 抓取帧
            Frame frame = grabber.grabImage();
            if (frame == null) {
                return null;
            }

            // 转换为 BufferedImage
            BufferedImage image = converter.convert(frame);
            if (image == null) {
                return null;
            }

            return image;
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new IOException("Failed to grab frame at " + timeUs + "μs: " + e.getMessage(), e);
        }
    }

    /**
     * 主动跳转到指定时间戳（微秒）。
     * 下次调用 getFrameAt() 时会从该位置开始。
     */
    public void seekTo(long timeUs) {
        if (started) {
            try {
                grabber.setTimestamp(timeUs);
            } catch (FFmpegFrameGrabber.Exception e) {
                System.err.println("Failed to seek to " + timeUs + "μs: " + e.getMessage());
            }
        }
    }

    /**
     * 获取视频总时长（微秒）
     */
    public long getDuration() {
        if (started) {
            return grabber.getLengthInTime();
        }
        return 0;
    }

    /**
     * 获取视频帧率
     */
    public double getFrameRate() {
        if (started) {
            return grabber.getVideoFrameRate();
        }
        return 0;
    }

    /**
     * 获取视频原始宽度
     */
    public int getWidth() {
        if (started) {
            return grabber.getImageWidth();
        }
        return 0;
    }

    /**
     * 获取视频原始高度
     */
    public int getHeight() {
        if (started) {
            return grabber.getImageHeight();
        }
        return 0;
    }

    /* ------------------------------------------------------------------ */
    /*                             资源释放                                */
    /* ------------------------------------------------------------------ */

    @Override
    public void close() {
        if (started) {
            try {
                converter.close();
                grabber.stop();
                grabber.release();
                started = false;
            } catch (FFmpegFrameGrabber.Exception e) {
                System.err.println("Error closing frame grabber: " + e.getMessage());
            }
        }
    }
}
