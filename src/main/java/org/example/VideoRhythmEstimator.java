package org.example;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;

/**
 * VideoRhythmEstimator
 * 目标：在视频线程侧，从每帧关键点生成一维"位移标量"序列，基于滑动窗做周期性检测，输出 (videoFreqHz, videoFreqConf)。
 * 频率范围：0.5–6 Hz，窗口建议 32–64 样本（适配你的视频线程 ~100ms/帧）。
 *
 * 使用方式（最小改动）：
 *  1) 在 VideoProcessController 的姿态检测回调里，拿到 17x3 关键点（已做与你训练一致的 PreNormalize2D 后），调用 onPoseFrame(kps, ptsMs)。
 *  2) 周期性（例如每隔 ~300ms）内部自动触发 estimate()，并将结果写入 latestVideoFreqHz / latestVideoFreqConf（原子引用）。
 *  3) 主融合线程暂不使用该值；先仅做缓存。
 *
 * 关键步骤：
 *  A. 关键点可见性判定（≥40% 关键点 conf>0.4，否则跳过该帧）
 *  B. 计算位移标量 s_t ：mid-hip 与 mid-shoulder 的欧氏距离（在已 PreNormalize2D 坐标系下）
 *  C. 写入滑动窗（ring buffer），并轻量带通（高通去趋势 + 低通 EMA）
 *  D. 自相关主峰/频谱主峰（此处默认自相关） → 主频 f_v 与置信度 c_v（峰显著度、可见度、一致性）
 *  E. 轻量时序平滑：EMA 限幅 + 异常跳变抑制
 */
public class VideoRhythmEstimator {

    // ==== 可调参数（移动端友好，尽量保守） ====
    public static class Params {
        // 频率范围（Hz）
        public float fMin = 0.5f;
        public float fMax = 6.0f;

        // 滑动窗样本数（视频线程每 ~100ms 一次；64 ≈ 6.4s）
        public int windowSize = 64;

        // 估计步长（每多少帧尝试估计一次；3≈每300ms）
        public int estimateEveryNFrames = 3;

        // 可见度门槛（与现有一致：≥40% 关键点 conf>0.4）
        public float minKpConf = 0.4f;
        public float minVisibleRatio = 0.40f;

        // 带通：高通（去趋势）+ 低通 EMA 的系数（越小越平滑）
        public float hpAlpha = 0.95f;    // 高通用简单去均值 + 轻微泄漏
        public float lpAlpha = 0.20f;    // 低通 EMA 系数（0.1~0.3 较稳）

        // 频率 EMA 平滑（对最终 f_v 做一点点时间平滑）
        public float freqEma = 0.30f;

        // 异常跳变抑制阈值（Hz）
        public float maxJumpHz = 2.0f;

        // 置信度加权：峰显著度、关键点覆盖度、一致性
        public float wPeak = 0.60f;
        public float wVis  = 0.25f;
        public float wCons = 0.15f;

        // 一致性窗口（最近几次估计落在±10%内的占比）
        public int consistencyWindow = 5;
        public float consistencyTol = 0.10f; // ±10%
    }

    private final Params P;

    // === 对外可读（原子） ===
    private final AtomicReference<Float> latestFreqHz = new AtomicReference<>(Float.NaN);
    private final AtomicReference<Float> latestConf   = new AtomicReference<>(0f);
    private final AtomicLong latestTsMs = new AtomicLong(0);

    // === 内部缓冲 ===
    private final float[] ring;     // 原始位移标量 s_t
    private final float[] proc;     // 处理后的序列（去趋势/带通后）
    private int writeIdx = 0;
    private int filled = 0;
    private int frameSinceLastEst = 0;

    // 频率平滑
    private boolean haveFreq = false;
    private float emaFreq = Float.NaN;

    // 一致性缓存
    private final float[] recentFreqs;
    private int recentIdx = 0;
    private int recentCount = 0;

    public VideoRhythmEstimator() { this(new Params()); }
    public VideoRhythmEstimator(Params params) {
        this.P = params;
        this.ring = new float[P.windowSize];
        this.proc = new float[P.windowSize];
        this.recentFreqs = new float[Math.max(3, P.consistencyWindow)];
        Arrays.fill(ring, 0f);
        Arrays.fill(proc, 0f);
        Arrays.fill(recentFreqs, Float.NaN);
    }

    /** 重置（Seek/镜头切换时调用） */
    public synchronized void reset() {
        Arrays.fill(ring, 0f);
        Arrays.fill(proc, 0f);
        writeIdx = 0;
        filled = 0;
        frameSinceLastEst = 0;
        haveFreq = false;
        emaFreq = Float.NaN;
        Arrays.fill(recentFreqs, Float.NaN);
        recentIdx = 0; recentCount = 0;
        latestFreqHz.set(Float.NaN);
        latestConf.set(0f);
        latestTsMs.set(0);
    }

    // ============== 外部读接口（供你缓存至 AtomicReference） ==============
    public float getLatestFreqHz() { return latestFreqHz.get(); }
    public float getLatestConf()   { return latestConf.get(); }
    public long  getLatestTsMs()   { return latestTsMs.get(); }

    // ============== 主入口：在姿态回调中调用 ======================
    /**
     * @param kps    17x3 关键点（x,y,conf），坐标需与你 ST-GCN++ 训练时的 PreNormalize2D 一致（[-1,1]）
     * @param ptsMs  当前帧的展示或编码时间戳
     */
    public synchronized void onPoseFrame(float[][] kps, long ptsMs) {
        // A) 关键点可见性检查
        if (!isPoseReliable(kps)) {
            // 关键点不可靠：不写入新样本，但做置信度衰减（可选，这里简单不更新）
            frameSinceLastEst++;
            tryEstimateIfReady(ptsMs);
            return;
        }

        // B) 计算位移标量：mid-hip 与 mid-shoulder 的欧氏距离
        float s = computeDisplacementScalar(kps);

        // C) 写入滑动窗
        ring[writeIdx] = s;
        // 预处理：去均值 + 轻微高通 + 低通 EMA
        // 为简单起见，先做去均值，再做一次一阶 EMA 低通
        proc[writeIdx] = prefilter(s, writeIdx);
        writeIdx = (writeIdx + 1) % P.windowSize;
        if (filled < P.windowSize) filled++;

        frameSinceLastEst++;
        tryEstimateIfReady(ptsMs);
    }

    // ============== 核心步骤：估计触发与结果写入 ==============
    private void tryEstimateIfReady(long ptsMs) {
        if (filled < Math.min(32, P.windowSize/2)) return; // 预热
        if (frameSinceLastEst < P.estimateEveryNFrames) return;
        frameSinceLastEst = 0;

        // 复制一份连续序列，避免环形索引复杂性
        float[] seq = snapshot(proc, filled);

        // D) 自相关主峰 → 频率 & 峰显著度
        EstResult est = estimateByAutocorr(seq, /*samplePeriodSec=*/0.1f, P.fMin, P.fMax);

        // 置信度：峰显著度、关键点可见度、一致性
        float vis = lastVisibility; // 在 isPoseReliable() 中更新
        float cons = consistencyScore(est.freqHz);
        float conf = clamp01(P.wPeak * est.peakScore + P.wVis * vis + P.wCons * cons);

        // E) 频率时间平滑与异常抑制
        float smoothF = est.freqHz;
        if (haveFreq && !Float.isNaN(emaFreq)) {
            if (Math.abs(est.freqHz - emaFreq) > P.maxJumpHz && conf < 0.5f) {
                // 大跳变且置信度不高 → 抑制：沿用旧值并降低 conf
                smoothF = emaFreq;
                conf *= 0.6f;
            } else {
                smoothF = (1f - P.freqEma) * est.freqHz + P.freqEma * emaFreq;
            }
        }
        emaFreq = smoothF;
        haveFreq = true;

        // 一致性缓存更新
        recentFreqs[recentIdx] = smoothF;
        recentIdx = (recentIdx + 1) % recentFreqs.length;
        if (recentCount < recentFreqs.length) recentCount++;

        // 写原子值（供外部缓存/读取）
        latestFreqHz.set(smoothF);
        latestConf.set(conf);
        latestTsMs.set(ptsMs);
    }

    // ============== 可见性与位移标量 ==========================
    private float lastVisibility = 0f;

    private boolean isPoseReliable(float[][] kps) {
        int visible = 0;
        for (int i = 0; i < kps.length; i++) {
            float conf = kps[i][2];
            if (conf >= P.minKpConf) visible++;
        }
        float ratio = (float) visible / Math.max(1, kps.length);
        lastVisibility = ratio; // 用作置信度分量
        return ratio >= P.minVisibleRatio;
    }

    private float computeDisplacementScalar(float[][] kps) {
        // mid-hip = (Lhip + Rhip)/2 ; mid-shoulder = (Lsho + Rsho)/2
        int L_HIP = 11, R_HIP = 12, L_SHO = 5, R_SHO = 6; // COCO 索引（按你项目使用的顺序调整）
        float mx = 0f, my = 0f, sx = 0f, sy = 0f;

        float lx = kps[L_HIP][0], ly = kps[L_HIP][1];
        float rx = kps[R_HIP][0], ry = kps[R_HIP][1];
        mx = 0.5f * (lx + rx);  my = 0.5f * (ly + ry);

        float lsx = kps[L_SHO][0], lsy = kps[L_SHO][1];
        float rsx = kps[R_SHO][0], rsy = kps[R_SHO][1];
        sx = 0.5f * (lsx + rsx); sy = 0.5f * (lsy + rsy);

        float dx = mx - sx, dy = my - sy;
        float dist = (float)Math.sqrt(dx*dx + dy*dy);

        // 可选：除以肩宽做自适应归一化（提升镜头推拉稳健性）
        float shoulderDx = rsx - lsx, shoulderDy = rsy - lsy;
        float shoulder = (float)Math.sqrt(shoulderDx*shoulderDx + shoulderDy*shoulderDy);
        if (shoulder > 1e-6f) {
            dist /= shoulder;
        }
        return dist;
    }

    // ============== 预处理（去均值/带通的轻量近似） ==================
    // 这里采用：去均值 + 简单低通 EMA。为避免存整窗均值，这里做一个渐进均值/泄漏式高通近似。
    private float runningMean = 0f;
    private float lpPrev = 0f;

    private float prefilter(float s, int idx) {
        // 去均值（渐进）
        runningMean = 0.99f * runningMean + 0.01f * s;
        float highpassed = s - runningMean;      // 简易高通（去趋势）

        // 低通 EMA
        float lp = (1f - P.lpAlpha) * highpassed + P.lpAlpha * lpPrev;
        lpPrev = lp;

        return lp;
    }

    // ============== 自相关主频估计 ==============================
    private static class EstResult {
        float freqHz;
        float peakScore; // 0..1
    }

    /**
     * @param seq  连续数组（长度 = filled）
     * @param dt   采样周期（秒），视频线程 ~100ms → 0.1
     * @param fMin/fMax  搜索频率范围
     */
    private EstResult estimateByAutocorr(float[] seq, float dt, float fMin, float fMax) {
        EstResult r = new EstResult();
        int n = seq.length;
        if (n < 16) { r.freqHz = Float.NaN; r.peakScore = 0f; return r; }

        // 计算自相关 R[k]（中心化后）
        float mean = 0f;
        for (float v: seq) mean += v;
        mean /= n;
        float var = 0f;
        float[] x = new float[n];
        for (int i=0;i<n;i++){ x[i]=seq[i]-mean; var += x[i]*x[i]; }
        if (var < 1e-12f) { r.freqHz=Float.NaN; r.peakScore=0f; return r; }

        int kMin = Math.max(1, (int)Math.floor(1.0f/(fMax*dt))); // 对应最大频率的最小滞后
        int kMax = Math.min(n-1, (int)Math.ceil(1.0f/(fMin*dt))); // 对应最小频率的最大滞后

        float bestVal = -Float.MAX_VALUE;
        int bestK = -1;
        for (int k = kMin; k <= kMax; k++) {
            float s = 0f;
            for (int i=0; i<n-k; i++) {
                s += x[i] * x[i+k];
            }
            if (s > bestVal) { bestVal = s; bestK = k; }
        }
        if (bestK <= 0) { r.freqHz=Float.NaN; r.peakScore=0f; return r; }

        float r0 = var; // k=0 的自相关值
        float norm = bestVal / (r0 + 1e-12f);

        // 估计频率
        float tau = bestK * dt;
        float f = 1.0f / Math.max(tau, 1e-6f);

        // 峰显著度（与相邻滞后比较）
        float neighbor = 0f;
        if (bestK-1>=kMin && bestK+1<=kMax) {
            float sm1 = 0f, sp1 = 0f;
            for (int i=0;i<n-(bestK-1);i++) sm1 += x[i]*x[i+bestK-1];
            for (int i=0;i<n-(bestK+1);i++) sp1 += x[i]*x[i+bestK+1];
            neighbor = Math.max(sm1, sp1) / (r0 + 1e-12f);
        }
        float peakScore = clamp01((norm - neighbor) * 2.0f); // 简易突出度 0..1

        r.freqHz = f;
        r.peakScore = peakScore;
        return r;
    }

    // ============== 一致性评分（最近几次在 ±10% 内的占比） ===========
    private float consistencyScore(float fNow) {
        if (Float.isNaN(fNow) || recentCount==0) return 0f;
        int ok = 0;
        for (int i=0;i<recentCount;i++){
            float v = recentFreqs[i];
            if (Float.isNaN(v)) continue;
            float tol = Math.max(P.consistencyTol * v, 0.1f);
            if (Math.abs(fNow - v) <= tol) ok++;
        }
        return clamp01(ok / (float)Math.max(1, recentCount));
    }

    // ============== 工具函数 ============================
    private static float clamp01(float v){ return Math.max(0f, Math.min(1f, v)); }

    private float[] snapshot(float[] buf, int len) {
        // 将环形缓冲的"filled"段按时间顺序拷贝出来（最近的在末尾）
        float[] out = new float[len];
        int start = (writeIdx - len + P.windowSize) % P.windowSize;
        for (int i=0; i<len; i++) {
            out[i] = buf[(start + i) % P.windowSize];
        }
        return out;
    }
}

