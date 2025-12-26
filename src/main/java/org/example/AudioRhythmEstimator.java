package org.example;

// =============================================================
// 文件: AudioRhythmEstimator.java
// 用途: 4秒滑动窗口音频节奏估计器 (0.5-4 Hz)
//      设计用于插入到您现有的音频线程中。
//      每个音频线程节拍推送约1秒的16 kHz PCM数据。
//      当内部缓冲区达到4秒时，估计器
//      输出(频率Hz, 置信度, 时间戳Ms)。
//
// 为什么先简单实现:
//   - 使用宽带短时能量包络(下采样到100 Hz)
//     以避免繁重的DSP依赖。
//   - 在延迟范围[25..200]个bin内进行自相关(≈4-0.5 Hz，包络采样率100 Hz)。
//   - 置信度来自归一化自相关峰值和能量合理性检查。
//
// 下一步迭代(保留TODO标记):
//   - 多频带包络(80-250 / 250-800 / 800-3000 Hz)，使用简单的IIR带通滤波器。
//   - 当音乐/语音占主导时，可选的HPSS或VAD门控。
//   - 用1极点低通滤波器@10 Hz替换移动平均平滑处理包络。
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

	private static final String TAG = "AudioRhythmEstimator";

	// ======== 配置 ========
	private final int sr;                 // 采样率(预期16000)
	private final int secondsWindow = 4;  // 4秒节奏窗口
	private final int capacity;           // sr * secondsWindow

	// 包络参数
	private final int envFs = 100;                 // 包络采样率(每秒的bin数)
	private final int envHop;                      // sr / envFs = 16000/100 = 160 采样/bin
	private final int minLagBins = 54;             // ≈ 100/1.85Hz
	private final int maxLagBins = 106;            // ≈ 100/0.95Hz
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
	public void push(float[] mono16k) {
		push(mono16k, 0, mono16k.length);
	}

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

		// [12.9 ADD] 在标准化之前，拷贝一份“原始包络”用于弱信号检测
		float[] envRaw = env.clone();

		// 5) 标准化包络: 减去均值，除以标准差(避免直流偏置)
		standardizeInPlace(env);

		// 6) 在延迟范围[minLagBins..maxLagBins]内进行自相关
		float r0 = autocorrAtLag(env, 0); // 等于方差 * N (因为零均值)
		if (r0 <= 1e-6f) return Result.invalid(nowMs);

		int bestLag = -1; float bestR = -Float.MAX_VALUE;
		// 可选: 在峰值周围进行小的抛物线插值可以精细化频率。
		for (int k = minLagBins; k <= maxLagBins && k < env.length - 2; k++) {
			float r = autocorrAtLag(env, k);
			if (r > bestR) { bestR = r; bestLag = k; }
		}
		if (bestLag < 0) return Result.invalid(nowMs);


		// [12.10 ADD] 次谐波纠错：
		// 如果当前估计的频率偏高（例如 >2.0 Hz），
		// 则在 2×bestLag 附近再寻找一个“更慢一倍”的候选峰，
		// 若该候选峰足够强，则将其视为真正的基础节奏。
		{
			float baseFreq = (float) envFs / bestLag; // 先用原 bestLag 算一个频率
			if (baseFreq > 2.0f) {
				// 只对节奏>2.0Hz 的情况尝试纠错，避免误伤本来就很慢的节奏
				int searchRadius = 2;   // 在 2×bestLag ±2 个 bin 内搜索
				int candidateLag = -1;
				float candidateR = -Float.MAX_VALUE;

				// 2 倍周期（频率减半）附近
				int cand2 = bestLag * 2;
				if (cand2 <= maxLagBins) {
					int start = Math.max(minLagBins, cand2 - searchRadius);
					int end   = Math.min(maxLagBins, cand2 + searchRadius);
					for (int k = start; k <= end && k < env.length - 2; k++) {
						float r = autocorrAtLag(env, k);
						if (r > candidateR) {
							candidateR = r;
							candidateLag = k;
						}
					}
				}

				// 如有需要，可以在这里继续扩展到 3×bestLag 的次谐波搜索（暂不启用）
				// int cand3 = bestLag * 3;
				// ...

				if (candidateLag > 0) {
					float mainNorm = bestR / r0;
					float subNorm  = candidateR / r0;

					// 要求：次谐波峰不能比当前主峰弱太多，且自身也不能太弱
					// 阈值可根据实际测试调节：
					//  - subNorm >= 0.8 * mainNorm   表示“强度接近”
					//  - subNorm >= 0.25f            表示“至少有一定周期性”
					if (subNorm >= 0.6f * mainNorm && subNorm >= 0.20f) {
						// 采用更慢的“基础节奏”
						bestLag = candidateLag;
						bestR   = candidateR;
					}
				}
			}
		}

		// 7) 将延迟(100 Hz的bin数) -> Hz
		float freq = (float) envFs / bestLag; // Hz

		// 8) 从归一化峰值高度和基本合理性检查计算置信度
		float peakNorm = bestR / r0;                     // [0..1] 准周期信号通常 < 0.7

		// [12. 9 MOD] 给 scoreConfidence 额外传入 envRaw
		float conf = scoreConfidence(peakNorm, env, envRaw, bestLag);
		//Log.d(TAG, "[音频节律] bestR:" + bestR +" r0: " + r0 + " peakNorm 值为：" + peakNorm);

		// 9) 将频率限制在[0.95, 1.85] Hz以减少异常值
		if (freq < 0.95f || freq > 1.85f) {
			// 如果超出范围，视为低置信度，但仍返回限制后的频率
			freq = clamp(freq, 0.95f, 1.85f);
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
		int n = x.length - lag;
		if (n <= 1) return 0f;
		double acc = 0.0;
		for (int i = 0; i < n; i++) acc += x[i] * (double) x[i + lag];
		return (float) (acc / n); // 关键：按有效长度归一化
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** 置信度曲线: 基于归一化峰值的线性斜坡，带有轻微惩罚。 */
	// [12. 9 MOD] 新增 envRaw 参数：标准化前的包络
	private static float scoreConfidence(float peakNorm, float[] envStd, float[] envRaw, int bestLag) {
		// 基于归一化峰值高度的基础值
		float c = (peakNorm - 0.06f) / 0.35f; // 0.15->0, 0.70->1.0 (可调)
		c = clamp(c, 0f, 1f);

		// [12.9 ADD] 使用未标准化的 envRaw 做“弱信号 / 动态范围”惩罚
		if (envRaw != null && envRaw.length > 0) {
			double energy = 0.0;
			float maxVal = Float.NEGATIVE_INFINITY;
			float minVal = Float.POSITIVE_INFINITY;

			for (float v : envRaw) {
				energy += (double) v * (double) v;
				if (v > maxVal) maxVal = v;
				if (v < minVal) minVal = v;
			}

			double avgEnergy = energy / envRaw.length;
			float dynamicRange = maxVal - minVal;

			// 能量过低（音频包络整体幅度太小，几乎听不到明显节奏） → 降低置信度（阈值可根据测试再微调）
			if (avgEnergy < 1e-4) {          // 建议调参范围：1e-4 ~ 1e-3
				c *= 0.8f;
			}

			// 动态范围过小（几乎没有起伏）→ 再次降低置信度
			if (dynamicRange < 1e-3f) {      // 建议调参范围：1e-3 ~ 1e-2
				c *= 0.8f;
			}
		}

		// 可选: 检查 bestLag 周围的局部一致性(峰值尖锐度)，检测自相关主峰是否“尖锐而清晰”，如果主峰不够尖锐（= 周围也很高 = 宽峰），就降低置信度。
        /* 因为该惩罚被频繁触发（无论是什么档位），因此暂时先不使用
        int k = bestLag;
        if (k - 2 >= 0 && k + 2 < envStd.length) {
            float side = 0f;
            side = 0.25f * (
                    autocorrAtLag(envStd, k - 2) + autocorrAtLag(envStd, k + 2) +
                            autocorrAtLag(envStd, k - 1) + autocorrAtLag(envStd, k + 1)
            );
            float sharp = peakNorm - side / Math.max(1e-6f, autocorrAtLag(envStd, 0));
            if (sharp < 0.05f) c *= 0.8f; // 宽峰 -> 稍低的置信度
            // Log.d(TAG, "[音频节律] [置信度惩罚] 触发主峰尖锐度过低惩罚 ");
        }
        */

		return clamp(c, 0f, 1f);
	}

}
