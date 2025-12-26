package org.example;

// =============================================================
// 文件: AudioRhythmEstimator.java
// 用途: 4秒滑动窗口音频节奏估计器 (0.5-6 Hz)
//      设计用于插入到您现有的音频线程中。
//      每个音频线程节拍推送约1秒的16 kHz PCM数据。
//      当内部缓冲区达到4秒时，估计器
//      输出(频率Hz, 置信度, 时间戳Ms)。
//
// 说明:
//   - 使用宽带短时能量包络(下采样到100 Hz)，避免额外DSP依赖。
//   - 在延迟范围[17..200]个bin内进行自相关(≈6-0.5 Hz，包络采样率100 Hz)。
//   - 置信度来自归一化自相关峰值和能量合理性检查。
// =============================================================

import java.util.Arrays;

public final class AudioRhythmEstimator {
	public static final class Result {
		public final boolean valid;          // 当我们有>=4秒缓冲并有可用估计时为true
		public final float frequencyHz;      // 估计的节奏频率(Hz)
		public final float confidence;       // [0,1]
		public final long timestampMs;       // 产生时的系统时间(由调用者提供)

		private Result(boolean valid, float f, float c, long ts) {
			this.valid = valid; this.frequencyHz = f; this.confidence = c; this.timestampMs = ts;
		}
		public static Result invalid(long ts) { return new Result(false, 0f, 0f, ts); }
		public static Result of(float f, float c, long ts) { return new Result(true, f, c, ts); }
	}

	// ======== 配置 ========
	private final int sr;                 // 采样率(预期16000)
	private final int secondsWindow = 4;  // 4秒节奏窗口
	private final int capacity;           // sr * secondsWindow

	// 包络参数
	private final int envFs = 100;                 // 包络采样率(每秒的bin数)
	private final int envHop;                      // sr / envFs = 16000/100 = 160 采样/bin
	private final int minLagBins = 17;             // ≈ 100/6 Hz ≈ 17
	private final int maxLagBins = 200;            // ≈ 100/0.5 Hz = 200
	private final int maSmooth = 5;                // 移动平均长度(~50毫秒)

	// ======== 状态(4秒滑动窗口) ========
	private final float[] ring;
	private int writeIdx = 0;          // 写入游标(对容量取模)
	private int filled = 0;            // 当前缓冲的有效采样数

	public AudioRhythmEstimator(int sampleRateHz) {
		this.sr = sampleRateHz;
		this.capacity = sampleRateHz * secondsWindow; // 16 kHz时为64000
		this.ring = new float[this.capacity];
		this.envHop = Math.max(1, sampleRateHz / envFs);
	}

	/** 重置内部缓冲区和状态(在跳转/停止时调用)。 */
	public void reset() {
		Arrays.fill(ring, 0f);
		writeIdx = 0;
		filled = 0;
	}

	/**
	 * 推送一块最新的单声道PCM采样(float值在[-1,1]范围内)。
	 * 推荐块大小: ~1秒(sr个采样)。允许更短或更长。
	 */
	public void push(float[] mono16k) { push(mono16k, 0, mono16k.length); }

	public void push(float[] mono16k, int off, int len) {
		int i = 0;
		while (i < len) {
			int spaceToEnd = capacity - writeIdx;
			int cp = Math.min(spaceToEnd, len - i);
			System.arraycopy(mono16k, off + i, ring, writeIdx, cp);
			writeIdx = (writeIdx + cp) % capacity;
			i += cp;
			filled = Math.min(capacity, filled + cp);
		}
	}

	/** 当我们至少缓冲了4秒的音频并可以估计节奏时返回true。 */
	public boolean isWarm() { return filled >= capacity; }

	/**
	 * 对最后4秒窗口执行节奏估计。
	 * @param nowMs 用于标记结果的时间戳。
	 */
	public Result estimate(long nowMs) {
		if (!isWarm()) return Result.invalid(nowMs);
		// 1) 按时间顺序提取连续的4秒窗口
		float[] win = new float[capacity];
		if (writeIdx == 0) {
			System.arraycopy(ring, 0, win, 0, capacity);
		} else {
			int tail = capacity - writeIdx;
			System.arraycopy(ring, writeIdx, win, 0, tail);
			System.arraycopy(ring, 0, win, tail, writeIdx);
		}

		// 2) 预归一化(RMS归一化到~1.0，避免溢出和响度漂移)
		float rms = (float) Math.sqrt(eps + meanSquare(win));
		if (rms > 0f) {
			float g = 1.0f / rms;
			for (int i = 0; i < win.length; i++) win[i] *= g;
		}

		// 3) 构建~100 Hz的能量包络(对envHop求平方和)
		int bins = win.length / envHop; // ~64000/160 = 400个bin
		float[] env = new float[bins];
		int idx = 0;
		for (int b = 0; b < bins; b++) {
			float e = 0f;
			int end = idx + envHop;
			while (idx < end) { float s = win[idx++]; e += s * s; }
			env[b] = e; // 已经≥0
		}

		// 4) 移动平均平滑(~50毫秒)
		if (maSmooth > 1) env = movingAverage(env, maSmooth);

		// 5) 标准化包络: 减去均值，除以标准差(避免直流偏置)
		standardizeInPlace(env);

		// 6) 在延迟范围[minLagBins..maxLagBins]内进行自相关
		float r0 = autocorrAtLag(env, 0); // 等于方差 * N (因为零均值)
		if (r0 <= 1e-6f) return Result.invalid(nowMs);

		int bestLag = -1; float bestR = -Float.MAX_VALUE;
		for (int k = minLagBins; k <= maxLagBins && k < env.length - 2; k++) {
			float r = autocorrAtLag(env, k);
			if (r > bestR) { bestR = r; bestLag = k; }
		}
		if (bestLag < 0) return Result.invalid(nowMs);

		// 7) 将延迟(100 Hz的bin数) -> Hz
		float freq = (float) envFs / bestLag; // Hz

		// 8) 从归一化峰值高度和基本合理性检查计算置信度
		float peakNorm = bestR / r0;                     // [0..1]
		float conf = scoreConfidence(peakNorm, env, bestLag);

		// 9) 将频率限制在[0.5, 6] Hz以减少异常值
		if (freq < 0.5f || freq > 6f) {
			freq = clamp(freq, 0.5f, 6f);
			conf *= 0.5f;
		}
		return Result.of(freq, conf, nowMs);
	}

	// ======== 辅助函数 ========
	private static final float eps = 1e-12f;

	private static float meanSquare(float[] x) {
		double acc = 0.0; for (float v : x) acc += v * (double) v; return (float) (acc / x.length);
	}

	private static float[] movingAverage(float[] x, int m) {
		int n = x.length; if (m <= 1 || m >= n) return Arrays.copyOf(x, n);
		float[] y = new float[n];
		double acc = 0.0;
		for (int i = 0; i < n; i++) {
			acc += x[i]; if (i >= m) acc -= x[i - m];
			if (i >= m - 1) y[i] = (float) (acc / m); else y[i] = x[i];
		}
		return y;
	}

	private static void standardizeInPlace(float[] x) {
		double sum = 0.0; for (float v : x) sum += v;
		double mean = sum / x.length;
		double vacc = 0.0; for (float v : x) { double d = v - mean; vacc += d * d; }
		double std = Math.sqrt(vacc / Math.max(1, x.length - 1));
		if (std < 1e-9) { Arrays.fill(x, 0f); return; }
		for (int i = 0; i < x.length; i++) x[i] = (float) ((x[i] - mean) / std);
	}

	private static float autocorrAtLag(float[] x, int lag) {
		int n = x.length - lag; if (n <= 1) return 0f;
		double acc = 0.0; for (int i = 0; i < n; i++) acc += x[i] * (double) x[i + lag];
		return (float) acc;
	}

	private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

	/** 置信度曲线: 基于归一化峰值的线性斜坡，带有轻微惩罚。 */
	private static float scoreConfidence(float peakNorm, float[] env, int bestLag) {
		float c = (peakNorm - 0.15f) / 0.55f; // 0.15->0, 0.70->1.0 (可调)
		c = clamp(c, 0f, 1f);
		double energy = 0.0; for (float v : env) energy += v * (double) v;
		double avg = energy / env.length;
		if (avg < 0.2) c *= 0.8f;
		int k = bestLag;
		float side = 0f;
		if (k - 2 >= 0 && k + 2 < env.length) {
			side = 0.25f * (autocorrAtLag(env, k - 2) + autocorrAtLag(env, k + 2) +
					autocorrAtLag(env, k - 1) + autocorrAtLag(env, k + 1));
			float sharp = peakNorm - side / Math.max(1e-6f, autocorrAtLag(env, 0));
			if (sharp < 0.05f) c *= 0.8f; // 宽峰 -> 稍低的置信度
		}
		return clamp(c, 0f, 1f);
	}
}


