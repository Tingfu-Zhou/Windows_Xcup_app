package org.example;

import ai.onnxruntime.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.security.GeneralSecurityException;

/**
 * AudioInferenceHelper (Windows / ONNX Runtime)
 *
 *  • 加载 YAMNet(embedding) + 微调分类器 两个 ONNX<br>
 *  • 输入 2 s / 32 000 点 float32 音频波形 (16 kHz, mono)<br>
 *  • 输出动作类别索引
 */
public class AudioInferenceHelper {

    // 静态初始化块：确保 ONNX Runtime DLL 已加载
    static {
        try {
            // 检查是否已经加载（InferenceHelper 可能已经加载过）
            System.out.println("[AudioInferenceHelper] Checking ONNX Runtime...");

            // 尝试创建一个简单的 ONNX 对象来验证加载状态
            OrtEnvironment.getEnvironment();
            System.out.println("[AudioInferenceHelper] ONNX Runtime is ready");
        } catch (Exception e) {
            System.err.println("[AudioInferenceHelper] ONNX Runtime initialization failed: " + e.getMessage());
        }
    }

    /* --------------- 常量 --------------- */
    private static final int INPUT_LENGTH     = 32_000;  // 2 s @16k
    private static final int EMBEDDING_SIZE   = 1024;

    /* --------------- ONNX Runtime --------------- */
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession     yamnetSession;
    private final OrtSession     classifierSession;

    /* --------------- 构造 --------------- */
    /**
     * @param yamnetModel      e.g. "models/yamnet.onnx"  或 绝对路径
     * @param classifierModel  e.g. "models/yamnet_finetuned.onnx"
     */
    public AudioInferenceHelper(String yamnetModel, String classifierModel) throws OrtException, IOException {
        yamnetSession      = createSession(loadBytes(yamnetModel));
        classifierSession  = createSession(loadBytes(classifierModel));
        System.out.println("[AudioInference] Model loaded");
        System.out.println(yamnetSession.getOutputNames());  // [scores, embeddings, spectrogram]

    }

    /* --------------- 公开 API --------------- */
    /**
     * @param audioBuffer 长度 32 000 的 float[]
     * @return 分类器输出的 softmax 概率分布（长度 = 类别数）
     */
    public float[] predictProbs(float[] audioBuffer) {
        if (audioBuffer.length != INPUT_LENGTH) {
            System.err.println("[AudioInference] The input length is incorrect. It should be 32 000. The actual length is " + audioBuffer.length);
            return null;
        }
        try {
            // 1. YAMNet 推理...
            long[] yamShape = {INPUT_LENGTH};
            try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioBuffer), yamShape);
                 OrtSession.Result res = yamnetSession.run(Collections.singletonMap(name(yamnetSession), in))) {

                // 取 "output_1" (embeddings)
                OnnxValue embVal = res.get("output_1")
                        .orElseThrow(() -> new IllegalStateException("output_1 not found"));
                float[][] embed = (float[][]) embVal.getValue(); // [numFrames, 1024]

                // 2. 均值池化
                float[] mean = new float[EMBEDDING_SIZE];
                for (float[] frame : embed)
                    for (int j = 0; j < EMBEDDING_SIZE; j++)
                        mean[j] += frame[j];
                for (int j = 0; j < EMBEDDING_SIZE; j++)
                    mean[j] /= embed.length;

                // 3. 分类器
                long[] clsShape = {1, EMBEDDING_SIZE};
                try (OnnxTensor clsIn = OnnxTensor.createTensor(env, FloatBuffer.wrap(mean), clsShape);
                     OrtSession.Result clsRes = classifierSession.run(Collections.singletonMap(name(classifierSession), clsIn))) {

                    float[][] logits = (float[][]) clsRes.get(0).getValue();
                    float[] probs = logits[0]; // 已为 softmax 概率分布

                    return probs; // 直接返回概率分布
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }



    /** 关闭模型，释放内存 */
    public void close() {
        try { yamnetSession.close(); }      catch (OrtException ignored) {}
        try { classifierSession.close(); } catch (OrtException ignored) {}
        env.close();
    }

    /* --------------- 私有辅助 --------------- */

    private OrtSession createSession(byte[] modelBytes) throws OrtException {
        OrtSession.SessionOptions opt = new OrtSession.SessionOptions();
        opt.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        return env.createSession(modelBytes, opt);
    }

    /** 支持 “classpath 资源” 或 “文件路径” 两种读取方式 */
    private static byte[] loadBytes(String pathOrResource) throws IOException {
        // ① 尝试按文件系统路径读取
        Path p = Path.of(pathOrResource);
        if (Files.exists(p)) return Files.readAllBytes(p);

        // ② 尝试从 classpath 资源读取（[模型加密] 支持 .enc 自动解密）
        try {
            return ModelCryptoUtil.loadModelBytes(pathOrResource);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to decrypt audio model: " + pathOrResource, e);
        }
        /*
        // ② 尝试从 classpath 资源读取
        try (InputStream is = Objects.requireNonNull(
                AudioInferenceHelper.class.getClassLoader().getResourceAsStream(pathOrResource),
                "Audio model file not found: " + pathOrResource)) {
            return is.readAllBytes();
        }
        */
    }

    private static String name(OrtSession s) {
        return s.getInputNames().iterator().next();
    }

    private static int argMax(float[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) if (arr[i] > arr[best]) best = i;
        return best;
    }
}
