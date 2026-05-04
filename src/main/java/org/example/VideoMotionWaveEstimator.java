package org.example;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VideoMotionWaveEstimator
 * <p>
 * 基于 ROI 块运动 → 主方向投影 → 泄漏积分 → 自相关主频 的视频节律估计器，
 * 替代旧版基于 COCO17 关键点位移的 {@link VideoRhythmEstimator}。
 * <p>
 * 设计要点（与 Android 端一致，方便三端对照）：
 * <ul>
 *   <li>关键点（已归一化到 [0,1]）只用于定 ROI；节律信号来自 ROI 内的鲁棒块运动场。</li>
 *   <li>跨帧 EMA 累积 2×2 二阶矩矩阵，PCA 取主特征向量作为主运动方向 (ux, uy)；
 *       带符号稳定保证跨帧方向连续。</li>
 *   <li>signedMotion = median(dx_i*ux + dy_i*uy)，支持任意方向（不再假设上下）。</li>
 *   <li>骨盆置信度 (LHIP/RHIP) 不达阈值 → 直接 NaN，不做 bbox fallback。</li>
 *   <li>线程安全：所有 public 方法 synchronized；最新结果通过 {@link AtomicReference} 暴露。</li>
 * </ul>
 */
public class VideoMotionWaveEstimator {

    // ============================ 参数 ============================
    public static class Params {
        // ROI
        public float roiAlpha          = 0.30f;
        public float roiWidthFactor    = 1.6f;
        public float roiHeightFactor   = 1.6f;
        public float roiVerticalShift  = 0.20f;
        public float roiAspectMin      = 0.50f;
        public float roiAspectMax      = 2.40f;
        public float roiMinFracW       = 0.10f;
        public float roiMinFracH       = 0.12f;
        public float roiMaxFracW       = 0.85f;
        public float roiMaxFracH       = 0.85f;
        public float kpConfTh          = 0.30f;
        public float pelvisKpConfTh    = 0.30f;

        // Gray ROI
        public int grayW = 64;
        public int grayH = 64;

        // Block matching
        public int   gridX        = 8;
        public int   gridY        = 8;
        public int   searchRadius = 4;
        public float texVarTh     = 25.0f;
        public float outlierMadK  = 2.5f;

        // 主运动方向
        public boolean enableMotionDirection = true;
        public float   motionDirAlpha        = 0.10f;
        public float   motionDirMinEnergy    = 0.50f;

        // 泄漏积分 / 滑窗
        public float leak                 = 0.95f;
        public int   windowSize           = 60;
        public int   minWarmSamples       = 30;
        public int   estimateEveryNFrames = 3;

        // 频率搜索范围
        public float minFreqHz = 0.95f;
        public float maxFreqHz = 1.85f;

        // 置信度
        public float minMotionEnergyZero = 0.05f;
        public float minMotionEnergyFull = 0.50f;
        public float minValidBlockRatio  = 0.30f;
        public float maxFreqJumpHz       = 0.40f;

        // 位置归一化
        public int   posNormWindow    = 20;
        public float pos01SmoothAlpha = 0.40f;

        // 生命周期
        public int maxConsecutiveLostBeforeReset = 4;
    }

    // ============================ 字段 ============================
    private final Params P;
    private final AtomicReference<VideoWaveResult> latest =
            new AtomicReference<>(VideoWaveResult.empty());

    // ROI EMA (pixel)
    private boolean roiInitialized = false;
    private float roiCx, roiCy, roiW, roiH;

    // 灰度 ROI
    private byte[] prevGray;
    private byte[] currGray;
    private int[]  pixelBuf;

    // position 滑窗（环形）
    private final float[] posWindow;
    private final long[]  posTsWindow;
    private int   posCount    = 0;
    private int   posWriteIdx = 0;
    private float rawPosition = 0f;

    // 估计调度
    private int     framesSinceEst   = 0;
    private float   lastFreqHz       = Float.NaN;
    private float   lastPos01        = 0.5f;
    private boolean roiLost          = true;
    private int     consecutiveLost  = 0;

    // 主方向：2 阶矩矩阵 EMA + 主特征向量
    private float   dirMxx = 0f, dirMxy = 0f, dirMyy = 0f;
    private boolean dirInitialized = false;
    private float   mainDirX = 0f;
    private float   mainDirY = 1f;

    // ============================ 构造 / 重置 ============================
    public VideoMotionWaveEstimator() {
        this(new Params());
    }

    public VideoMotionWaveEstimator(Params params) {
        this.P = params;
        this.posWindow   = new float[P.windowSize];
        this.posTsWindow = new long[P.windowSize];
    }

    public synchronized void reset() {
        roiInitialized = false;
        roiCx = roiCy = roiW = roiH = 0f;
        prevGray = null;
        currGray = null;
        pixelBuf = null;
        Arrays.fill(posWindow, 0f);
        Arrays.fill(posTsWindow, 0L);
        posCount = 0;
        posWriteIdx = 0;
        rawPosition = 0f;
        framesSinceEst = 0;
        lastFreqHz = Float.NaN;
        lastPos01 = 0.5f;
        roiLost = true;
        consecutiveLost = 0;
        dirMxx = dirMxy = dirMyy = 0f;
        dirInitialized = false;
        mainDirX = 0f;
        mainDirY = 1f;
        latest.set(VideoWaveResult.empty());
    }

    // ============================ 对外读接口 ============================
    public VideoWaveResult getLatestResult() {
        return latest.get();
    }

    /** 与旧 VideoRhythmEstimator 的兼容 helper：valid=false 时返回 NaN。 */
    public float getLatestFreqHz() {
        VideoWaveResult r = latest.get();
        return (r != null && r.valid) ? r.freqHz : Float.NaN;
    }

    public float getLatestConf() {
        VideoWaveResult r = latest.get();
        return (r != null) ? r.confidence : 0f;
    }

    public long getLatestTsMs() {
        VideoWaveResult r = latest.get();
        return (r != null) ? r.timestampMs : 0L;
    }

    // ============================ 主入口 ============================
    /**
     * @param timestampMs    当前帧时间戳（毫秒）
     * @param frame          当前帧 BufferedImage（可能为 null，表示本帧没拿到图像）
     * @param keypointsNorm  17×3 关键点（x/y∈[0,1]，conf 原始值）；null 表示未检测到人体（场景切换语义）
     */
    public synchronized void pushFrame(long timestampMs,
                                       BufferedImage frame,
                                       float[][] keypointsNorm) {
        // (a) 关键点全无
        if (keypointsNorm == null) {
            consecutiveLost++;
            if (consecutiveLost >= P.maxConsecutiveLostBeforeReset) {
                reset();
            } else {
                roiLost = true;
                decayConfidence(timestampMs, "no-keypoints");
            }
            return;
        }

        // (a') 帧无效
        if (frame == null) {
            consecutiveLost++;
            roiLost = true;
            decayConfidence(timestampMs, "no-frame");
            return;
        }
        int frameW = frame.getWidth();
        int frameH = frame.getHeight();
        if (frameW <= 0 || frameH <= 0) {
            consecutiveLost++;
            roiLost = true;
            decayConfidence(timestampMs, "no-frame");
            return;
        }

        // (a.1) 骨盆置信度门控
        final int LHIP = 11, RHIP = 12;
        if (keypointsNorm.length < 17
                || keypointsNorm[LHIP][2] < P.pelvisKpConfTh
                || keypointsNorm[RHIP][2] < P.pelvisKpConfTh) {
            consecutiveLost++;
            roiLost = true;
            if (consecutiveLost >= P.maxConsecutiveLostBeforeReset) {
                reset();
            } else {
                float lc = (keypointsNorm.length > LHIP) ? keypointsNorm[LHIP][2] : 0f;
                float rc = (keypointsNorm.length > RHIP) ? keypointsNorm[RHIP][2] : 0f;
                publishNaN(timestampMs, String.format(Locale.US,
                        "low-pelvis-conf Lhip=%.2f Rhip=%.2f th=%.2f",
                        lc, rc, P.pelvisKpConfTh));
            }
            return;
        }
        consecutiveLost = 0;

        // (b) 计算目标 ROI（仅 hip 路径）
        float[] newRoi = computeRoi(keypointsNorm, frameW, frameH);
        if (newRoi == null) {
            roiLost = true;
            decayConfidence(timestampMs, "no-roi");
            return;
        }

        // (c) ROI EMA 平滑
        if (!roiInitialized) {
            roiCx = newRoi[0];
            roiCy = newRoi[1];
            roiW  = newRoi[2];
            roiH  = newRoi[3];
            roiInitialized = true;
            roiLost = true;
        } else {
            float a = P.roiAlpha;
            roiCx = a * newRoi[0] + (1f - a) * roiCx;
            roiCy = a * newRoi[1] + (1f - a) * roiCy;
            roiW  = a * newRoi[2] + (1f - a) * roiW;
            roiH  = a * newRoi[3] + (1f - a) * roiH;
        }

        // (d) Crop ROI → 灰度 64×64
        int rx = Math.max(0, Math.round(roiCx - roiW * 0.5f));
        int ry = Math.max(0, Math.round(roiCy - roiH * 0.5f));
        int rw = Math.min(frameW - rx, Math.round(roiW));
        int rh = Math.min(frameH - ry, Math.round(roiH));
        if (rw < 16 || rh < 16) {
            roiLost = true;
            decayConfidence(timestampMs, "roi-too-small");
            return;
        }

        if (currGray == null) currGray = new byte[P.grayW * P.grayH];
        int neededPixels = rw * rh;
        if (pixelBuf == null || pixelBuf.length < neededPixels) {
            pixelBuf = new int[neededPixels];
        }
        try {
            frame.getRGB(rx, ry, rw, rh, pixelBuf, 0, rw);
        } catch (Throwable t) {
            roiLost = true;
            decayConfidence(timestampMs, "rgb-read-fail");
            return;
        }
        for (int gy = 0; gy < P.grayH; gy++) {
            int srcY  = (gy * rh) / P.grayH;
            int rowOff = srcY * rw;
            int outOff = gy * P.grayW;
            for (int gx = 0; gx < P.grayW; gx++) {
                int srcX = (gx * rw) / P.grayW;
                int p = pixelBuf[rowOff + srcX];
                int r = (p >> 16) & 0xFF;
                int g = (p >>  8) & 0xFF;
                int b = (p      ) & 0xFF;
                int y = (r * 30 + g * 59 + b * 11) / 100;
                currGray[outOff + gx] = (byte) y;
            }
        }

        // (e) Block matching
        boolean haveMatch = (prevGray != null) && !roiLost;
        float signedMotion = 0f;
        float motionEnergy = 0f;
        int   validBlocks  = 0;
        if (haveMatch) {
            float[] bm = blockMatch();
            signedMotion = bm[0];
            motionEnergy = bm[1];
            validBlocks  = (int) bm[2];
        }
        roiLost = false;

        // 交换 prev/curr 缓冲
        byte[] tmp = prevGray;
        prevGray = currGray;
        currGray = (tmp != null) ? tmp : new byte[P.grayW * P.grayH];

        // (f) 泄漏积分
        int totalBlocks = P.gridX * P.gridY;
        int minBlocks   = Math.max(4, Math.round(totalBlocks * P.minValidBlockRatio));
        if (haveMatch && validBlocks >= minBlocks) {
            rawPosition = P.leak * rawPosition + signedMotion;
        } else {
            rawPosition = P.leak * rawPosition;
        }

        // (g) 推入 position 滑窗
        posWindow[posWriteIdx]   = rawPosition;
        posTsWindow[posWriteIdx] = timestampMs;
        posWriteIdx = (posWriteIdx + 1) % P.windowSize;
        if (posCount < P.windowSize) posCount++;
        framesSinceEst++;

        // (h) 周期性触发主频估计
        if (posCount >= P.minWarmSamples && framesSinceEst >= P.estimateEveryNFrames) {
            framesSinceEst = 0;
            VideoWaveResult result = doEstimate(
                    timestampMs, rx, ry, rw, rh,
                    signedMotion, motionEnergy, validBlocks, totalBlocks);
            latest.set(result);
        } else {
            // 预热期：复制上次结果，刷新帧相关字段
            VideoWaveResult prev = latest.get();
            VideoWaveResult upd  = (prev != null) ? prev.copy() : VideoWaveResult.empty();
            upd.timestampMs  = timestampMs;
            upd.rawPosition  = rawPosition;
            upd.motionEnergy = motionEnergy;
            upd.roiX = rx; upd.roiY = ry; upd.roiW = rw; upd.roiH = rh;
            upd.mainDirX = mainDirX;
            upd.mainDirY = mainDirY;
            float dirDeg = (float)(Math.atan2(mainDirY, mainDirX) * 180.0 / Math.PI);
            upd.debugInfo = String.format(Locale.US,
                    "[warm] roi=(%d,%d,%d,%d) blk=%d/%d sM=%.2f rawP=%.2f mE=%.3f dir=(%.2f,%.2f,%.0f°) posCount=%d/%d",
                    rx, ry, rw, rh, validBlocks, totalBlocks,
                    signedMotion, rawPosition, motionEnergy,
                    mainDirX, mainDirY, dirDeg,
                    posCount, P.minWarmSamples);
            latest.set(upd);
        }
    }

    // ============================ ROI 计算 ============================
    private float[] computeRoi(float[][] kp, int frameW, int frameH) {
        if (kp.length < 17) return null;
        final int LSHO = 5, RSHO = 6, LHIP = 11, RHIP = 12, LKNEE = 13, RKNEE = 14;

        // 防御性双检（pushFrame 已门控）
        if (kp[LHIP][2] < P.pelvisKpConfTh || kp[RHIP][2] < P.pelvisKpConfTh) return null;

        boolean lkneeOK = kp[LKNEE][2] >= P.kpConfTh;
        boolean rkneeOK = kp[RKNEE][2] >= P.kpConfTh;
        boolean lshoOK  = kp[LSHO ][2] >= P.kpConfTh;
        boolean rshoOK  = kp[RSHO ][2] >= P.kpConfTh;

        float hipCx   = 0.5f * (kp[LHIP][0] + kp[RHIP][0]);
        float hipCy   = 0.5f * (kp[LHIP][1] + kp[RHIP][1]);
        float hipDx   = kp[RHIP][0] - kp[LHIP][0];
        float hipDy   = kp[RHIP][1] - kp[LHIP][1];
        float hipDist = (float)Math.sqrt(hipDx * hipDx + hipDy * hipDy);

        // 兜底：两 hip 太近改用肩宽
        float refSize = hipDist;
        if (refSize < 0.04f && lshoOK && rshoOK) {
            float sdx = kp[RSHO][0] - kp[LSHO][0];
            float sdy = kp[RSHO][1] - kp[LSHO][1];
            refSize = (float)Math.sqrt(sdx * sdx + sdy * sdy);
        }
        if (refSize < 0.03f) refSize = 0.03f;

        // 估计纵向尺度：优先 hip-knee 距离
        float vert = refSize * 1.6f;
        if (lkneeOK || rkneeOK) {
            float kneeYSum = 0f;
            int n = 0;
            if (lkneeOK) { kneeYSum += kp[LKNEE][1]; n++; }
            if (rkneeOK) { kneeYSum += kp[RKNEE][1]; n++; }
            float kneeY = kneeYSum / n;
            float v = Math.abs(kneeY - hipCy);
            if (v > vert * 0.5f) vert = v * 1.4f;
        }

        float cx = hipCx;
        float cy = hipCy + P.roiVerticalShift * vert;
        float w  = refSize * P.roiWidthFactor;
        float h  = vert    * P.roiHeightFactor;

        // 归一化 → 像素
        cx *= frameW; cy *= frameH;
        w  *= frameW; h  *= frameH;

        // min/max 尺寸（占帧比例）
        float wMin = P.roiMinFracW * frameW, wMax = P.roiMaxFracW * frameW;
        float hMin = P.roiMinFracH * frameH, hMax = P.roiMaxFracH * frameH;
        if (w < wMin) w = wMin;
        if (w > wMax) w = wMax;
        if (h < hMin) h = hMin;
        if (h > hMax) h = hMax;

        // aspect
        if (w > 1f) {
            float aspect = h / w;
            if (aspect < P.roiAspectMin) h = w * P.roiAspectMin;
            if (aspect > P.roiAspectMax) h = w * P.roiAspectMax;
        }

        // 中心 clamp
        if (cx - w * 0.5f < 0)        cx = w * 0.5f;
        if (cy - h * 0.5f < 0)        cy = h * 0.5f;
        if (cx + w * 0.5f > frameW)   cx = frameW - w * 0.5f;
        if (cy + h * 0.5f > frameH)   cy = frameH - h * 0.5f;

        if (w < P.roiMinFracW * frameW * 0.5f
                || h < P.roiMinFracH * frameH * 0.5f) return null;

        return new float[] { cx, cy, w, h };
    }

    // ============================ Block matching ============================
    /** @return [signedMotion, energy, validBlocks] */
    private float[] blockMatch() {
        final int W = P.grayW, H = P.grayH;
        final int gx = P.gridX, gy = P.gridY;
        final int bw = W / gx, bh = H / gy;
        final int R  = P.searchRadius;
        final int total = gx * gy;

        int[] dxs = new int[total];
        int[] dys = new int[total];
        boolean[] valid = new boolean[total];
        int validCount = 0;
        double Sxx = 0, Sxy = 0, Syy = 0;

        for (int by = 0; by < gy; by++) {
            int y0 = by * bh;
            if (y0 < R || y0 + bh + R > H) continue;
            for (int bx = 0; bx < gx; bx++) {
                int x0 = bx * bw;
                if (x0 < R || x0 + bw + R > W) continue;
                int idx = by * gx + bx;

                // 1) 块方差（在 prevGray 上算）
                long sum = 0, sum2 = 0;
                for (int yy = 0; yy < bh; yy++) {
                    int rowOff = (y0 + yy) * W + x0;
                    for (int xx = 0; xx < bw; xx++) {
                        int v = prevGray[rowOff + xx] & 0xFF;
                        sum  += v;
                        sum2 += (long) v * v;
                    }
                }
                int npx = bw * bh;
                double mean = sum / (double) npx;
                double var  = sum2 / (double) npx - mean * mean;
                if (var < P.texVarTh) continue;

                // 2) ±R SAD 搜索
                int bestSAD = Integer.MAX_VALUE;
                int bestDx = 0, bestDy = 0;
                for (int dy = -R; dy <= R; dy++) {
                    for (int dx = -R; dx <= R; dx++) {
                        int sad = 0;
                        for (int yy = 0; yy < bh; yy++) {
                            int prevOff = (y0 + yy) * W + x0;
                            int currOff = (y0 + yy + dy) * W + x0 + dx;
                            for (int xx = 0; xx < bw; xx++) {
                                int diff = (prevGray[prevOff + xx] & 0xFF)
                                        - (currGray[currOff + xx] & 0xFF);
                                sad += (diff < 0) ? -diff : diff;
                            }
                            if (sad >= bestSAD) break; // 早停
                        }
                        if (sad < bestSAD) {
                            bestSAD = sad;
                            bestDx  = dx;
                            bestDy  = dy;
                        }
                    }
                }

                dxs[idx]   = bestDx;
                dys[idx]   = bestDy;
                valid[idx] = true;
                validCount++;
                Sxx += (double) bestDx * bestDx;
                Sxy += (double) bestDx * bestDy;
                Syy += (double) bestDy * bestDy;
            }
        }

        if (validCount == 0) return new float[] { 0f, 0f, 0f };

        // 3) 更新主方向
        updateMainDirection((float) Sxx, (float) Sxy, (float) Syy, validCount);

        // 4) 投影到主方向
        float[] m = new float[validCount];
        double energySum = 0;
        int j = 0;
        for (int i = 0; i < total; i++) {
            if (valid[i]) {
                float proj = dxs[i] * mainDirX + dys[i] * mainDirY;
                m[j++] = proj;
                energySum += Math.abs(proj);
            }
        }

        // 5) 中位数
        float[] sorted = m.clone();
        Arrays.sort(sorted);
        float median = sorted[validCount / 2];

        // 6) MAD 异常剔除后再算一次中位数
        float[] absDev = new float[validCount];
        for (int i = 0; i < validCount; i++) absDev[i] = Math.abs(m[i] - median);
        Arrays.sort(absDev);
        float mad = absDev[validCount / 2];
        float thresh = Math.max(1.0f, P.outlierMadK * mad);

        int countF = 0;
        for (int i = 0; i < validCount; i++) {
            if (Math.abs(m[i] - median) <= thresh) countF++;
        }
        float signedMotion;
        if (countF == 0) {
            signedMotion = median;
        } else {
            float[] filtered = new float[countF];
            int k = 0;
            for (int i = 0; i < validCount; i++) {
                if (Math.abs(m[i] - median) <= thresh) filtered[k++] = m[i];
            }
            Arrays.sort(filtered);
            signedMotion = filtered[countF / 2];
        }

        float energy = (float) (energySum / Math.max(1, validCount));
        return new float[] { signedMotion, energy, validCount };
    }

    // ============================ 主方向估计 ============================
    private void updateMainDirection(float Sxx, float Sxy, float Syy, int validBlocks) {
        if (!P.enableMotionDirection) {
            mainDirX = 0f;
            mainDirY = 1f;
            return;
        }
        float frameEnergy = (float) Math.sqrt(Sxx + Syy);
        int minBlocks = Math.max(4, Math.round(P.gridX * P.gridY * P.minValidBlockRatio));
        if (validBlocks < minBlocks || frameEnergy < P.motionDirMinEnergy) return;

        if (!dirInitialized) {
            dirMxx = Sxx; dirMxy = Sxy; dirMyy = Syy;
            dirInitialized = true;
        } else {
            float a = P.motionDirAlpha;
            dirMxx = (1 - a) * dirMxx + a * Sxx;
            dirMxy = (1 - a) * dirMxy + a * Sxy;
            dirMyy = (1 - a) * dirMyy + a * Syy;
        }

        float[] eigen = principalEigenvector2x2(dirMxx, dirMxy, dirMyy);
        float newUx = eigen[0], newUy = eigen[1];

        // 符号稳定：与上次方向点积 < 0 即翻号
        if (newUx * mainDirX + newUy * mainDirY < 0) {
            newUx = -newUx;
            newUy = -newUy;
        }
        mainDirX = newUx;
        mainDirY = newUy;
    }

    /** 2×2 对称矩阵 [[a,b],[b,d]] 的主特征向量（单位化）。 */
    private static float[] principalEigenvector2x2(float a, float b, float d) {
        float half = (a - d) * 0.5f;
        float disc = (float) Math.sqrt(half * half + b * b);
        float lambda1 = (a + d) * 0.5f + disc;
        float ex, ey;
        if (Math.abs(b) < 1e-9f) {
            if (a >= d) { ex = 1f; ey = 0f; }
            else        { ex = 0f; ey = 1f; }
        } else {
            ex = b;
            ey = lambda1 - a;
        }
        float norm = (float) Math.sqrt(ex * ex + ey * ey);
        if (norm < 1e-9f) return new float[] { 0f, 1f };
        return new float[] { ex / norm, ey / norm };
    }

    // ============================ 主频估计 ============================
    private VideoWaveResult doEstimate(long timestampMs,
                                       int rx, int ry, int rw, int rh,
                                       float signedMotion, float motionEnergy,
                                       int validBlocks, int totalBlocks) {
        float[] seq = snapshotPosWindow();
        float dt   = estimateAvgDt();
        float[] est = estimateByAutocorr(seq, dt);
        float freqHz      = est[0];
        float periodicity = est[1];

        // ---- 5 个置信度因子 ----
        float periodicityFactor = clamp01(periodicity);

        float meFactor;
        if (motionEnergy <= P.minMotionEnergyZero)        meFactor = 0f;
        else if (motionEnergy >= P.minMotionEnergyFull)   meFactor = 1f;
        else meFactor = (motionEnergy - P.minMotionEnergyZero)
                / (P.minMotionEnergyFull - P.minMotionEnergyZero);

        float blockRatio = (totalBlocks > 0) ? validBlocks / (float) totalBlocks : 0f;
        float blockFactor;
        if (blockRatio <= P.minValidBlockRatio) blockFactor = 0f;
        else if (blockRatio >= 0.7f)            blockFactor = 1f;
        else blockFactor = (blockRatio - P.minValidBlockRatio)
                / (0.7f - P.minValidBlockRatio);

        float roiLockFactor;
        if (consecutiveLost == 0 && posCount >= P.minWarmSamples) roiLockFactor = 1f;
        else if (posCount < P.minWarmSamples) roiLockFactor = posCount / (float) P.minWarmSamples;
        else roiLockFactor = 0.5f;

        float freqStability;
        if (Float.isNaN(lastFreqHz) || Float.isNaN(freqHz)) {
            freqStability = 0.7f;
        } else {
            float jump = Math.abs(freqHz - lastFreqHz);
            freqStability = Math.max(0.3f, clamp01(1f - jump / P.maxFreqJumpHz));
        }

        float confidence = clamp01(periodicityFactor * meFactor * blockFactor
                * roiLockFactor * freqStability);
        if (Float.isNaN(freqHz)) confidence = 0f;

        float pos01 = computePosition01(motionEnergy);

        VideoWaveResult r = new VideoWaveResult();
        r.timestampMs  = timestampMs;
        r.freqHz       = freqHz;
        r.confidence   = confidence;
        r.position01   = pos01;
        r.rawPosition  = rawPosition;
        r.motionEnergy = motionEnergy;
        r.periodicity  = periodicityFactor;
        r.roiX = rx; r.roiY = ry; r.roiW = rw; r.roiH = rh;
        r.mainDirX = mainDirX;
        r.mainDirY = mainDirY;
        r.locked = (consecutiveLost == 0 && posCount >= P.minWarmSamples);
        r.valid  = !Float.isNaN(freqHz) && posCount >= P.minWarmSamples;

        float dirDeg     = (float)(Math.atan2(mainDirY, mainDirX) * 180.0 / Math.PI);
        float blkRatePct = blockRatio * 100f;
        r.debugInfo = String.format(Locale.US,
                "roi=(%d,%d,%d,%d) blk=%d/%d (%.0f%%) sM=%.2f rawP=%.2f f=%.2fHz per=%.2f mE=%.3f conf=%.2f dir=(%.2f,%.2f,%.0f°) [me=%.2f blk=%.2f lock=%.2f stab=%.2f] dt=%.0fms",
                rx, ry, rw, rh, validBlocks, totalBlocks, blkRatePct,
                signedMotion, rawPosition, freqHz, periodicityFactor, motionEnergy, confidence,
                mainDirX, mainDirY, dirDeg,
                meFactor, blockFactor, roiLockFactor, freqStability,
                dt * 1000f);

        if (!Float.isNaN(freqHz)) lastFreqHz = freqHz;
        return r;
    }

    private float computePosition01(float motionEnergy) {
        if (motionEnergy < P.minMotionEnergyZero) {
            lastPos01 = 0.95f * lastPos01 + 0.05f * 0.5f;
            return lastPos01;
        }
        int n = Math.min(P.posNormWindow, posCount);
        if (n < 5) return lastPos01;

        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            int idx = ((posWriteIdx - 1 - i) % P.windowSize + P.windowSize) % P.windowSize;
            float v = posWindow[idx];
            if (v < minV) minV = v;
            if (v > maxV) maxV = v;
        }
        float range = maxV - minV;
        if (range < 1e-3f) return lastPos01;
        float v01 = clamp01((rawPosition - minV) / range);
        lastPos01 = (1f - P.pos01SmoothAlpha) * lastPos01 + P.pos01SmoothAlpha * v01;
        return lastPos01;
    }

    /** @return [freqHz, periodicity] */
    private float[] estimateByAutocorr(float[] seq, float dt) {
        int n = seq.length;
        if (n < 16 || dt <= 0f) return new float[] { Float.NaN, 0f };

        float[] x = detrend(seq);
        double r0 = 0;
        for (int i = 0; i < n; i++) r0 += (double) x[i] * x[i];
        if (r0 < 1e-9) return new float[] { Float.NaN, 0f };

        int kMin = Math.max(1, (int) Math.floor(1.0f / (P.maxFreqHz * dt)));
        int kMax = Math.min(n - 2, (int) Math.ceil(1.0f / (P.minFreqHz * dt)));
        if (kMax <= kMin) return new float[] { Float.NaN, 0f };

        int lo = Math.max(1, kMin - 1);
        int hi = Math.min(n - 1, kMax + 1);
        double[] R = new double[hi + 2];
        for (int k = lo; k <= hi; k++) {
            double s = 0;
            int lim = n - k;
            for (int i = 0; i < lim; i++) s += (double) x[i] * x[i + k];
            R[k] = s;
        }

        int bestK = -1;
        double bestVal = -Double.MAX_VALUE;
        for (int k = kMin; k <= kMax; k++) {
            if (R[k] > bestVal) { bestVal = R[k]; bestK = k; }
        }
        if (bestK < 0) return new float[] { Float.NaN, 0f };

        double refK = bestK;
        if (bestK - 1 >= lo && bestK + 1 <= hi) {
            double a = R[bestK - 1], b = R[bestK], c = R[bestK + 1];
            double denom = a - 2 * b + c;
            if (Math.abs(denom) > 1e-9) {
                double delta = 0.5 * (a - c) / denom;
                if (delta > 0.5)  delta = 0.5;
                if (delta < -0.5) delta = -0.5;
                refK = bestK + delta;
            }
        }
        double tau = refK * dt;
        if (tau < 1e-6) return new float[] { Float.NaN, 0f };
        float freqHz      = (float)(1.0 / tau);
        float periodicity = clamp01((float)(R[bestK] / r0));

        if (freqHz < P.minFreqHz * 0.9f || freqHz > P.maxFreqHz * 1.1f) {
            return new float[] { Float.NaN, 0f };
        }
        return new float[] { freqHz, periodicity };
    }

    private static float[] detrend(float[] seq) {
        int n = seq.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX  += i;
            sumY  += seq[i];
            sumXY += (double) i * seq[i];
            sumX2 += (double) i * i;
        }
        double meanX = sumX / n, meanY = sumY / n;
        double varX  = sumX2 / n - meanX * meanX;
        double slope = (varX > 1e-12) ? (sumXY / n - meanX * meanY) / varX : 0;
        double intercept = meanY - slope * meanX;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float)(seq[i] - (slope * i + intercept));
        }
        return out;
    }

    private float[] snapshotPosWindow() {
        int n = posCount;
        float[] out = new float[n];
        int start = ((posWriteIdx - n) % P.windowSize + P.windowSize) % P.windowSize;
        for (int i = 0; i < n; i++) {
            out[i] = posWindow[(start + i) % P.windowSize];
        }
        return out;
    }

    private float estimateAvgDt() {
        if (posCount < 2) return 0.1f;
        int firstIdx = ((posWriteIdx - posCount) % P.windowSize + P.windowSize) % P.windowSize;
        int lastIdx  = ((posWriteIdx - 1)         % P.windowSize + P.windowSize) % P.windowSize;
        long t0 = posTsWindow[firstIdx];
        long t1 = posTsWindow[lastIdx];
        if (t1 <= t0) return 0.1f;
        float dt = (t1 - t0) / 1000f / Math.max(1, posCount - 1);
        if (dt < 0.02f || dt > 0.5f) return 0.1f;
        return dt;
    }

    // ============================ 辅助 ============================
    private void publishNaN(long timestampMs, String reason) {
        VideoWaveResult r = new VideoWaveResult();
        r.timestampMs  = timestampMs;
        r.freqHz       = Float.NaN;
        r.confidence   = 0f;
        r.position01   = lastPos01;
        r.rawPosition  = rawPosition;
        r.motionEnergy = 0f;
        r.periodicity  = 0f;
        r.roiX = 0; r.roiY = 0; r.roiW = 0; r.roiH = 0;
        r.mainDirX = mainDirX;   // 保留方向，不重置
        r.mainDirY = mainDirY;
        r.locked = false;
        r.valid  = false;
        r.debugInfo = "[NaN] " + reason;
        latest.set(r);
    }

    private void decayConfidence(long timestampMs, String reason) {
        VideoWaveResult prev = latest.get();
        if (prev == null) return;
        VideoWaveResult upd = prev.copy();
        upd.timestampMs = timestampMs;
        upd.confidence *= 0.6f;
        upd.locked = false;
        upd.debugInfo = "[decay] reason=" + reason + " prev=" + prev.debugInfo;
        latest.set(upd);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
