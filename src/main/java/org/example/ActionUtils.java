package org.example;

public final class ActionUtils {

    private ActionUtils() {}

    public static float[] softmax(float[] logits) {
        if (logits == null || logits.length == 0) return new float[0];
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float v : logits) if (v > maxLogit) maxLogit = v;
        float sum = 0f;
        float[] exps = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            exps[i] = (float) Math.exp(logits[i] - maxLogit);
            sum += exps[i];
        }
        if (sum == 0f) return new float[logits.length];
        for (int i = 0; i < exps.length; i++) exps[i] /= sum;
        return exps;
    }

    /**
     * 复刻 MMAction2 PreNormalize2D（align_center=true）
     * 输入:  float[T][V][3]  —— 窗口内 T 帧, 17 关键点 (x,y,conf)
     * 输出:  同维度 float[][][] ，已做中心化+等比缩放
     */
    public static float[][][] preNormalize2D(float[][][] window) {
        float xMin = Float.MAX_VALUE, yMin = Float.MAX_VALUE;
        float xMax = Float.MIN_VALUE, yMax = Float.MIN_VALUE;
        for (float[][] frame : window) {
            for (float[] kp : frame) {
                if (kp[2] <= 0) continue;
                xMin = Math.min(xMin, kp[0]);
                yMin = Math.min(yMin, kp[1]);
                xMax = Math.max(xMax, kp[0]);
                yMax = Math.max(yMax, kp[1]);
            }
        }
        if (xMax < xMin) { xMin = 0; yMin = 0; xMax = 1; yMax = 1; }
        float cx = 0.5f * (xMin + xMax);
        float cy = 0.5f * (yMin + yMax);
        float scale = Math.max(xMax - xMin, yMax - yMin);
        if (scale < 1e-6f) scale = 1e-6f;
        float[][][] out = new float[window.length][window[0].length][3];
        for (int t = 0; t < window.length; t++) {
            for (int v = 0; v < window[0].length; v++) {
                out[t][v][0] = (window[t][v][0] - cx) / scale;
                out[t][v][1] = (window[t][v][1] - cy) / scale;
                out[t][v][2] =  window[t][v][2];
            }
        }
        return out;
    }

    /**
     * ST-GCN++ 8类输出合并策略 → oral/do/Noise
     * 注：快慢由节律估计器负责，视频分类只区分动作类型
     */
    public static String processStgcnOutput(float[] probs) {
        if (probs == null || probs.length != 8) {
            return "Noise";
        }
        float probOral = probs[0] + probs[1];
        float probDo = probs[2] + probs[3] + probs[4] + probs[5];
        float probNoiseStand = probs[6];
        float probNoiseSit = probs[7];
        float maxTargetProb = Math.max(probOral, probDo);
        float maxNoiseProb = Math.max(probNoiseStand, probNoiseSit);
        final float NOISE_RATIO_THRESHOLD = 1.5f;
        final float CONFIDENCE_THRESHOLD = 0.2f;
        String actionClass;
        float bestScore;
        if (maxNoiseProb > maxTargetProb * NOISE_RATIO_THRESHOLD) {
            actionClass = "Noise";
            bestScore = maxNoiseProb;
        } else {
            if (probOral > probDo) {
                actionClass = "oral";
                bestScore = probOral;
            } else {
                actionClass = "do";
                bestScore = probDo;
            }
        }
        if (bestScore < CONFIDENCE_THRESHOLD) {
            return "Noise";
        }
        return actionClass;
    }

    public static float getBestScore(float[] probs, String actionClass) {
        if (probs == null || probs.length != 8) return 0f;
        switch (actionClass) {
            case "oral":
                return probs[0] + probs[1];
            case "do":
                return probs[2] + probs[3] + probs[4] + probs[5];
            case "Noise":
                return Math.max(probs[6], probs[7]);
            default:
                return 0f;
        }
    }
}


