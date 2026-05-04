package org.example;

/**
 * 视频运动波形 / 节律估计结果（VideoMotionWaveEstimator 的输出 DTO）。
 * 跨平台 DTO：仅基本类型，便于 Android / iOS / Windows 三端字段一一对应。
 */
public class VideoWaveResult {

    public long timestampMs;     // 该结果对应的帧时间戳
    public float freqHz;         // 主频；置信度低 / 未预热时为 NaN
    public float confidence;     // 综合置信度 0..1
    public float position01;     // 归一化位置 0..1（envelope 归一化 + 平滑）
    public float rawPosition;    // 泄漏积分原始位置（无单位）
    public float motionEnergy;   // 沿主方向投影后 mean(|m_i|)
    public float periodicity;    // 自相关主峰相对强度 0..1

    // ROI 在原始帧像素坐标
    public int roiX;
    public int roiY;
    public int roiW;
    public int roiH;

    // 主运动方向单位向量（默认 (0, 1)）
    public float mainDirX;
    public float mainDirY;

    public boolean locked;       // 稳定锁定（窗口预热 + 连续帧未丢）
    public boolean valid;        // 当前结果是否可用（false → 调用方应忽略 freqHz）
    public String  debugInfo;    // 调试日志字符串

    public static VideoWaveResult empty() {
        VideoWaveResult r = new VideoWaveResult();
        r.timestampMs  = 0L;
        r.freqHz       = Float.NaN;
        r.confidence   = 0f;
        r.position01   = 0.5f;
        r.rawPosition  = 0f;
        r.motionEnergy = 0f;
        r.periodicity  = 0f;
        r.roiX = 0; r.roiY = 0; r.roiW = 0; r.roiH = 0;
        r.mainDirX = 0f;
        r.mainDirY = 1f;
        r.locked = false;
        r.valid  = false;
        r.debugInfo = "empty";
        return r;
    }

    public VideoWaveResult copy() {
        VideoWaveResult c = new VideoWaveResult();
        c.timestampMs  = this.timestampMs;
        c.freqHz       = this.freqHz;
        c.confidence   = this.confidence;
        c.position01   = this.position01;
        c.rawPosition  = this.rawPosition;
        c.motionEnergy = this.motionEnergy;
        c.periodicity  = this.periodicity;
        c.roiX = this.roiX; c.roiY = this.roiY; c.roiW = this.roiW; c.roiH = this.roiH;
        c.mainDirX = this.mainDirX;
        c.mainDirY = this.mainDirY;
        c.locked = this.locked;
        c.valid  = this.valid;
        c.debugInfo = this.debugInfo;
        return c;
    }
}
