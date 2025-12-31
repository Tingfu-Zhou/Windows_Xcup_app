package org.example;

public class AudioLoudnessLevelEstimator {

    public static class Result {
        public final boolean valid;
        public final int level;          // 0..9
        public final float confidence;   // 0..1
        public final float rms;          // linear RMS
        public final float db;           // dBFS (<=0)
        public final long timestampMs;

        public Result(boolean valid, int level, float confidence, float rms, float db, long ts) {
            this.valid = valid;
            this.level = level;
            this.confidence = confidence;
            this.rms = rms;
            this.db = db;
            this.timestampMs = ts;
        }
    }

    private final int sampleRate;

    // --- 可调参数（先给一版合理默认） ---
    private static final float EPS = 1e-8f;

    // RMS → dBFS 后做 EMA 平滑：alpha 越大越“跟手”，越小越“稳”
    private static final float EMA_ALPHA = 0.35f;

    // 自适应噪声底/峰值的更新速度（越小越慢，越稳）
    private static final float NOISE_ADAPT = 0.03f;
    private static final float PEAK_ADAPT  = 0.03f;

    // 低于这个 dBFS 基本当作“无有效节律”（静音/弱音）：输出 level=0
    private static final float HARD_SILENCE_DB = -50f;

    // 动态范围太小时，置信度要惩罚（避免一直处于“几乎不变的底噪”也被映射到高档）
    private static final float MIN_DYNAMIC_RANGE_DB = 10f;

    // 归一化后做轻微非线性，让中低响度更细腻 or 更激进（>1 更保守，<1 更激进）
    private static final float GAMMA = 1.15f;

    // 分段阈值：norm ∈ [0,1]，用于把“归一化响度”映射到 0..9 档
    // 解释：大量时间处在低能量，所以 1..6 档给更大覆盖；7..9 档要求更高的 norm（爆发才上）
    // 你后续只需要调这 9 个阈值即可改变“手感”
    private static final float[] NORM_THRESH = new float[] {
            0.10f, // >=0.10 -> 1档（低于此视为0档）
            0.20f, // -> 2
            0.30f, // -> 3
            0.40f, // -> 4
            0.50f, // -> 5
            0.60f, // -> 6  （1..6：转速档，覆盖0.10~0.60）
            0.75f, // -> 7  （7..9：强度档，需要更强响度）
            0.88f, // -> 8
            0.96f  // -> 9
    };

    // 把 norm 映射为 0..9 档（分段/非线性）
    private static int mapNormToLevel(float norm) {
        // 0档：静音/极弱
        if (norm < NORM_THRESH[0]) return 0;

        // 1..8
        for (int i = 0; i < NORM_THRESH.length - 1; i++) {
            if (norm < NORM_THRESH[i + 1]) {
                return i + 1; // i=0 -> 1档
            }
        }
        return 9; // >=最后一个阈值 -> 9档
    }

    // --- 状态 ---
    private boolean inited = false;
    private int warmCount = 0;

    private float emaDb = HARD_SILENCE_DB;
    private float noiseFloorDb = -45f; // 初值：相对保守
    private float peakDb = -12f;       // 初值：假设有一定响度峰

    public AudioLoudnessLevelEstimator(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void reset() {
        inited = false;
        warmCount = 0;
        emaDb = HARD_SILENCE_DB;
        noiseFloorDb = -45f;
        peakDb = -12f;
    }

    /** 推入一段 PCM（float，通常在 [-1,1]） */
    public void push(float[] pcm) {
        if (pcm == null || pcm.length == 0) return;

        float rms = computeRms(pcm);
        float db = (float) (20.0 * Math.log10(rms + EPS));  // dBFS，<=0

        // [ADD] NaN/Inf 防御：一旦坏数据进入会“毒化”EMA状态，后续永久异常
        if (!Float.isFinite(rms) || !Float.isFinite(db)) {
            return; // 直接丢弃本次 push，不更新内部状态
        }

        if (!inited) {
            emaDb = db;
            // 用首帧做初始化，避免一开始阈值太离谱
            noiseFloorDb = Math.min(db, noiseFloorDb);
            peakDb = Math.max(db, peakDb);
            inited = true;
        } else {
            emaDb = EMA_ALPHA * db + (1f - EMA_ALPHA) * emaDb;

            // 噪声底：只在“相对安静”时更积极更新（避免被峰值拉高）
            if (emaDb < noiseFloorDb + 3f) {
                noiseFloorDb = NOISE_ADAPT * emaDb + (1f - NOISE_ADAPT) * noiseFloorDb;
            } else {
                // 很响时，噪声底也允许极慢漂移，适配不同片源底噪变化
                noiseFloorDb = (NOISE_ADAPT * 0.2f) * emaDb + (1f - NOISE_ADAPT * 0.2f) * noiseFloorDb;
            }

            // 峰值：只在“更响”时更积极更新，下降时慢慢回落
            if (emaDb > peakDb) {
                peakDb = PEAK_ADAPT * emaDb + (1f - PEAK_ADAPT) * peakDb;
            } else {
                peakDb = (PEAK_ADAPT * 0.15f) * emaDb + (1f - PEAK_ADAPT * 0.15f) * peakDb;
            }
        }

        warmCount++;
    }

    /** 产出档位（0..9）与置信度（0..1） */
    public Result estimate(long nowMs) {
        if (!inited || warmCount < 2) { // 至少 push 两次（≈2秒）再开始可信输出
            return new Result(false, 0, 0f, 0f, emaDb, nowMs);
        }

        // 强静音门
        if (emaDb < HARD_SILENCE_DB) {
            return new Result(true, 0, 0f, dbToRms(emaDb), emaDb, nowMs);
        }

        float dyn = peakDb - noiseFloorDb;
        if (dyn < 1f) dyn = 1f;

        // 归一化 loudness：0..1
        float norm = (emaDb - noiseFloorDb) / dyn;
        norm = clamp01(norm);

        // level：0..9 —— 分段/非线性映射（替代 pow(norm,GAMMA) 的连续曲线）
        int level = mapNormToLevel(norm);

        // confidence：由 norm + 动态范围惩罚组成
        float conf = norm;

        // 动态范围过小惩罚
        if (dyn < MIN_DYNAMIC_RANGE_DB) {
            float penalty = dyn / MIN_DYNAMIC_RANGE_DB; // 0..1
            conf *= clamp01(penalty);
        }

        // 靠近噪声底时再压一下（避免轻微噪声也驱动）
        if (emaDb < noiseFloorDb + 2.0f) {
            conf *= 0.3f;
        }

        conf = clamp01(conf);

        // valid：这里定义为“不是静音 + 有一定置信度”
        boolean valid = (level > 0) && (conf >= 0.10f);

        return new Result(valid, level, conf, dbToRms(emaDb), emaDb, nowMs);
    }

    // --- utils ---
    private static float computeRms(float[] x) {
        double s = 0.0;
        for (float v : x) {
            s += (double) v * (double) v;
        }
        return (float) Math.sqrt(s / Math.max(1, x.length));
    }

    private static float dbToRms(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

}
