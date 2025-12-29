package org.example;

import ai.onnxruntime.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.awt.Color;
import java.security.GeneralSecurityException;

/**
 * Windows 桌面端 Inference 帮助类：
 *  • YOLOv8 Pose → COCO 17 keypoints (1,1,17,3)<br>
 *  • ST-GCN++ 32 帧滑窗动作识别<br>
 *
 * 依赖：onnxruntime 1.17.0
 */
public class InferenceHelper {

    // 在 InferenceHelper.java 的静态初始化块中使用这个版本

    static {
        boolean loaded = false;

        try {
            // 设置系统属性，禁止 ONNX Runtime 自动提取 DLL
            System.setProperty("onnxruntime.native.skip_load", "true");

            // 设置 UTF-8 编码
            System.setProperty("file.encoding", "UTF-8");
            System.setProperty("sun.jnu.encoding", "UTF-8");

            System.out.println("[InferenceHelper] Initializing ONNX Runtime...");
            System.out.println("Current directory: " + System.getProperty("user.dir"));
            System.out.println("Java library path: " + System.getProperty("java.library.path"));

            // 只尝试从当前目录加载（不要从 lib 目录）
            String currentDir = System.getProperty("user.dir");
            File dllFile = new File(currentDir, "onnxruntime.dll");

            if (dllFile.exists()) {
                try {
                    String absolutePath = dllFile.getAbsolutePath();
                    System.out.println("Loading ONNX Runtime from: " + absolutePath);

                    // 先加载 onnxruntime4j_jni.dll
                    File jniDll = new File(currentDir, "onnxruntime4j_jni.dll");
                    if (jniDll.exists()) {
                        System.load(jniDll.getAbsolutePath());
                        System.out.println("Loaded onnxruntime4j_jni.dll");
                    }

                    // 再加载主 DLL
                    System.load(absolutePath);
                    System.out.println("Successfully loaded onnxruntime.dll");
                    loaded = true;

                    // 设置标志，告诉 ONNX Runtime 不要再加载
                    System.setProperty("onnxruntime.native.loaded", "true");
                } catch (Throwable t) {
                    System.err.println("Failed to load DLL: " + t.getMessage());
                    t.printStackTrace();
                }
            }

            if (!loaded) {
                System.out.println("Warning: Could not manually load onnxruntime.dll");
                System.out.println("Letting ONNX Runtime handle the loading...");
                // 清除禁止加载的标志
                System.clearProperty("onnxruntime.native.skip_load");
            }

        } catch (Exception e) {
            System.err.println("Error in static initializer: " + e.getMessage());
            e.printStackTrace();
        }
    }
    /* ---------------------------- 常量 ---------------------------- */
    // YOLOv8-pose 模型输入尺寸
    private static final int YOLO_INPUT_SIZE = 640;   // YOLOv8 标准输入尺寸
    private static final float DETECTION_THRESHOLD = 0.1f;  // 检测阈值，降低以适应YOLO8

    /* ---- ST-GCN++ 输入维度 ---- */
    private static final int STGCN_BATCH = 1, STGCN_CHANNEL = 3,
            STGCN_TIME = 32, STGCN_VERTEX = 17, STGCN_M = 1;

    /* ---------------------------- ONNX Runtime ---------------------------- */
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession yoloPoseSession;
    private final OrtSession stgcnSession;

    /* ---------------------------- 构造 ---------------------------- */
    // 修改构造函数，不再需要 modelsDir 参数
    public InferenceHelper() throws OrtException, IOException {
        yoloPoseSession = createSession("models/yolov8-pose.onnx");
        stgcnSession    = createSession("models/stgcnpp.onnx");
    }

    // 修改 createSession 方法，从 classpath 加载资源
    private OrtSession createSession(String resourcePath) throws OrtException, IOException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        // 使用较低的优化级别以确保兼容性
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        
        // 设置执行提供程序为CPU
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        
        // 禁用一些可能导致问题的优化
        opts.setIntraOpNumThreads(1);
        opts.setInterOpNumThreads(1);

        // 从 classpath 资源加载模型
        byte[] modelBytes = loadModelBytes(resourcePath);
        System.out.println("[InferenceHelper] Loading model: " + resourcePath + " (" + modelBytes.length + " bytes)");
        
        try {
            return env.createSession(modelBytes, opts);
        } catch (OrtException e) {
            System.err.println("[InferenceHelper] Failed to create session for " + resourcePath + ": " + e.getMessage());
            throw e;
        }
    }


    // 新增辅助方法：从 classpath 加载模型字节
    private byte[] loadModelBytes(String resourcePath) throws IOException {
        try {
            // [模型加密] 统一走加密加载：如果存在同名 .enc 则自动解密，否则读明文资源
            return ModelCryptoUtil.loadModelBytes(resourcePath);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to decrypt model: " + resourcePath, e);
        }
        /*
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Model not found in resources: " + resourcePath);
            }
            return is.readAllBytes();
        }
         */
    }

    /* ======================================================================
                              YOLOv8 Pose 推理
       ====================================================================== */

    /**
     * 使用YOLOv8-pose模型进行人体关键点检测
     * @param image 一帧视频 (RGB BufferedImage)
     * @return 有人体 → 返回 [1,1,17,3] 的 COCO 关键点数组。
     *         每个点格式为 [x, y, confidence]；
     *         无人体 → null
     *
     * 维度说明：
     *   - 返回值 float[1][1][17][3]
     *     1: batch（本pipeline只处理单人）
     *     1: instance（单人）
     *     17: 关键点数（COCO顺序）
     *     3: 每个关键点的[x, y, conf]，x/y为原图坐标，conf为置信度
     *   - 该格式与ST-GCN++动作识别模型的输入完全兼容
     *
     * YOLOv8-pose模型输出：
     *   - float[1][N][56]，N为检测到的人数，每行：
     *     [x1, y1, x2, y2, conf, kpt1_x, kpt1_y, kpt1_conf, ..., kpt17_x, kpt17_y, kpt17_conf]
     *     坐标均为输入尺寸（如640x640）下的像素点
     */
    public float[][][][] detectPose(BufferedImage image) {
        try {
            // Letterbox到640x640
            LetterboxResult lb = letterbox(image, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE);
            float[] chw = toCHW(lb.image);
            long[] inputShape = {1, 3, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE};
            String inputName = yoloPoseSession.getInputNames().iterator().next();
            try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), inputShape);
                 OrtSession.Result res = yoloPoseSession.run(Collections.singletonMap(inputName, in))) {
                OnnxValue outputValue = res.get(0);
                Object val = outputValue.getValue();
                // 后处理
                List<BodyResult> results = onnxPosePostprocess(val, lb.scale, lb.padX, lb.padY, DETECTION_THRESHOLD, 0.45f, image.getWidth(), image.getHeight());
                // Suppress verbose debug output
                /*
                System.out.println("ONNX detected " + results.size() + " person(s)");
                for (int idx = 0; idx < results.size(); idx++) {
                    BodyResult br = results.get(idx);
                    System.out.printf("Person %d confidence: %.3f\n", idx + 1, br.conf);
                    System.out.print("  Box: [");
                    for (int i = 0; i < 4; i++) System.out.printf("%.1f%s", br.box[i], i < 3 ? ", " : "]\n");
                    for (int i = 0; i < 17; i++) {
                        float[] kpt = br.keypoints[i];
                        System.out.printf("    Keypoint%d: (x=%.1f, y=%.1f, conf=%.3f)\n", i, kpt[0], kpt[1], kpt[2]);
                    }
                }
                */
                if (results.isEmpty()) return null;
                // 只取第一个人体作为ST-GCN输入
                float[][][][] out = new float[1][1][17][3];
                for (int i = 0; i < 17; i++) {
                    out[0][0][i][0] = results.get(0).keypoints[i][0];
                    out[0][0][i][1] = results.get(0).keypoints[i][1];
                    out[0][0][i][2] = results.get(0).keypoints[i][2];
                }
                
                // 质量检查：检查置信度高于0.4的关键点是否超过40%（17个中至少7个）
                if (!isKeypointsQualityGood(out[0][0])) {
                    // Suppress - too verbose
                    // System.out.println("[YOLO8] Low keypoint quality, frame discarded (<40% keypoints with conf>0.4)");
                    return null;
                }
                
                return out;
            }
        } catch (Exception e) {
            System.err.println("[YOLO8] Error in detectPose: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 对YOLOv8-pose onnx输出做后处理，完全对齐test_yolo_model.py的onnx_pose_postprocess
     * @param output float[1][56][N] 或 float[56][N]
     * @param ratio letterbox缩放比例
     * @param dw letterbox X方向pad
     * @param dh letterbox Y方向pad
     * @param confThres 置信度阈值
     * @param iouThres NMS阈值
     * @param imageW 原图宽
     * @param imageH 原图高
     * @return List<BodyResult>，每个人体包含置信度、框、关键点
     */
    private static class BodyResult {
        float conf;
        float[] box; // [x1, y1, x2, y2]
        float[][] keypoints; // [17][3]
    }
    private static List<BodyResult> onnxPosePostprocess(Object outputRaw, float ratio, float dw, float dh, float confThres, float iouThres, int imageW, int imageH) {
        float[][] output;
        // 支持float[1][56][N] 或 float[56][N]
        if (outputRaw instanceof float[][][]) {
            float[][][] arr = (float[][][]) outputRaw;
            output = arr[0];
        } else if (outputRaw instanceof float[][]) {
            output = (float[][]) outputRaw;
        } else {
            throw new RuntimeException("未知的YOLO输出类型: " + outputRaw.getClass());
        }
        int N = output[0].length;
        // 1. 置信度过滤
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            if (output[4][i] > confThres) keep.add(i);
        }
        if (keep.isEmpty()) return new ArrayList<>();
        // 2. 还原框和关键点
        float[][] boxes = new float[keep.size()][4];
        float[] confs = new float[keep.size()];
        float[][][] kpts = new float[keep.size()][17][3];
        for (int idx = 0; idx < keep.size(); idx++) {
            int i = keep.get(idx);
            // 框: [cx, cy, w, h] -> [x1, y1, x2, y2]
            float cx = output[0][i], cy = output[1][i], w = output[2][i], h = output[3][i];
            float x1 = (cx - w / 2 - dw) / ratio;
            float y1 = (cy - h / 2 - dh) / ratio;
            float x2 = (cx + w / 2 - dw) / ratio;
            float y2 = (cy + h / 2 - dh) / ratio;
            // 限制在图像范围内
            x1 = Math.max(0, Math.min(x1, imageW - 1));
            y1 = Math.max(0, Math.min(y1, imageH - 1));
            x2 = Math.max(0, Math.min(x2, imageW - 1));
            y2 = Math.max(0, Math.min(y2, imageH - 1));
            boxes[idx][0] = x1; boxes[idx][1] = y1; boxes[idx][2] = x2; boxes[idx][3] = y2;
            confs[idx] = output[4][i];
            // 关键点
            for (int j = 0; j < 17; j++) {
                float kpt_x = (output[5 + j * 3][i] - dw) / ratio;
                float kpt_y = (output[5 + j * 3 + 1][i] - dh) / ratio;
                float kpt_c = output[5 + j * 3 + 2][i];
                kpt_x = Math.max(0, Math.min(kpt_x, imageW - 1));
                kpt_y = Math.max(0, Math.min(kpt_y, imageH - 1));
                kpts[idx][j][0] = kpt_x;
                kpts[idx][j][1] = kpt_y;
                kpts[idx][j][2] = kpt_c;
            }
        }
        // 3. NMS
        int[] keepIdx = nmsPose(boxes, confs, iouThres);
        List<BodyResult> results = new ArrayList<>();
        for (int idx : keepIdx) {
            BodyResult br = new BodyResult();
            br.conf = confs[idx];
            br.box = boxes[idx];
            br.keypoints = kpts[idx];
            results.add(br);
        }
        return results;
    }
    /**
     * NMS实现，返回保留的索引
     */
    private static int[] nmsPose(float[][] boxes, float[] scores, float iouThres) {
        int N = boxes.length;
        List<Integer> idxs = new ArrayList<>();
        for (int i = 0; i < N; i++) idxs.add(i);
        idxs.sort((a, b) -> Float.compare(scores[b], scores[a]));
        List<Integer> keep = new ArrayList<>();
        boolean[] removed = new boolean[N];
        for (int i = 0; i < N; i++) {
            int idx = idxs.get(i);
            if (removed[idx]) continue;
            keep.add(idx);
            for (int j = i + 1; j < N; j++) {
                int idx2 = idxs.get(j);
                if (removed[idx2]) continue;
                if (iou(boxes[idx], boxes[idx2]) > iouThres) {
                    removed[idx2] = true;
                }
            }
        }
        return keep.stream().mapToInt(i -> i).toArray();
    }

    /**
     * 计算两个box的IOU，box格式[x1, y1, x2, y2]
     */
    private static float iou(float[] box1, float[] box2) {
        float x1 = Math.max(box1[0], box2[0]);
        float y1 = Math.max(box1[1], box2[1]);
        float x2 = Math.min(box1[2], box2[2]);
        float y2 = Math.min(box1[3], box2[3]);
        float w = Math.max(0, x2 - x1);
        float h = Math.max(0, y2 - y1);
        float inter = w * h;
        float area1 = (box1[2] - box1[0]) * (box1[3] - box1[1]);
        float area2 = (box2[2] - box2[0]) * (box2[3] - box2[1]);
        return inter / (area1 + area2 - inter + 1e-6f);
    }

    /* ======================================================================
                             ST-GCN++ 32 帧推理
       ====================================================================== */
    public float[] runStgcnModel(float[][][] poseWindow) {
        try {
            float[] flat = new float[STGCN_BATCH * STGCN_CHANNEL * STGCN_TIME * STGCN_VERTEX * STGCN_M];
            // C=0:x, 1:y, 2:conf
            for (int c = 0; c < STGCN_CHANNEL; c++)
                for (int t = 0; t < STGCN_TIME; t++)
                    for (int v = 0; v < STGCN_VERTEX; v++)
                        flat[(c * STGCN_TIME + t) * STGCN_VERTEX + v] = poseWindow[t][v][c];

            long[] shape = {STGCN_BATCH, STGCN_CHANNEL, STGCN_TIME, STGCN_VERTEX, STGCN_M};
            System.out.println("[ST-GCN++] Input shape: " + Arrays.toString(shape));
            
            // 获取ST-GCN++模型的输入名称
            String stgcnInputName = stgcnSession.getInputNames().iterator().next();
            System.out.println("[ST-GCN++] Input name: " + stgcnInputName);
            
            OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape);
            OrtSession.Result res = stgcnSession.run(Collections.singletonMap(stgcnInputName, in));
            
            // 获取输出信息
            String stgcnOutputName = stgcnSession.getOutputNames().iterator().next();
            System.out.println("[ST-GCN++] Output name: " + stgcnOutputName);
            
            float[][] logits = (float[][]) res.get(0).getValue();
            System.out.println("[ST-GCN++] Output shape: [" + logits.length + ", " + logits[0].length + "]");
            System.out.println("[ST-GCN++] Raw logits: " + Arrays.toString(logits[0]));

            res.close(); in.close();
            return logits[0];
        } catch (Exception e) {
            System.err.println("[ST-GCN++] Error in runStgcnModel: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /* ====================================================================== */
    /*                               工具函数                                 */
    /* ====================================================================== */

    /**
     * 检查关键点质量是否足够好
     * @param keypoints float[17][3] - 17个COCO关键点，每个包含[x, y, confidence]
     * @return 如果置信度>0.4的关键点超过40%（至少7个），返回true，否则false
     */
    private static boolean isKeypointsQualityGood(float[][] keypoints) {
        if (keypoints == null || keypoints.length != 17) {
            return false;
        }
        
        int goodKeypointsCount = 0;
        final float CONFIDENCE_THRESHOLD = 0.4f;
        final int MIN_REQUIRED_KEYPOINTS = 7; // 40% of 17 = 6.8, 向上取整为7
        
        for (int i = 0; i < 17; i++) {
            if (keypoints[i] != null && keypoints[i].length >= 3 && keypoints[i][2] > CONFIDENCE_THRESHOLD) {
                goodKeypointsCount++;
            }
        }
        
        boolean isGood = goodKeypointsCount >= MIN_REQUIRED_KEYPOINTS;
        // Suppress verbose quality check output
        // System.out.printf("[YOLO8] Keypoint quality check: %d/17 keypoints with conf>%.1f, %s\n", 
        //                   goodKeypointsCount, CONFIDENCE_THRESHOLD, isGood ? "passed" : "failed");
        
        return isGood;
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /** 将 BGR BufferedImage → (H,W,C) Float[0-1]，顺序 RGBRGB… */
    private static float[] toHWC(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        float[] hwc = new float[3 * h * w];
        int p = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                hwc[p++] = ((rgb >> 16) & 0xFF) / 255f; // R
                hwc[p++] = ((rgb >> 8)  & 0xFF) / 255f; // G
                hwc[p++] = ( rgb        & 0xFF) / 255f; // B
            }
        return hwc;
    }


    /** 将 BGR BufferedImage → (C,H,W) Float[0-1] */
    private static float[] toCHW(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        float[] chw = new float[3 * h * w];
        int idxR = 0, idxG = h * w, idxB = 2 * h * w;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                chw[idxR++] = ((rgb >> 16) & 0xFF) / 255.0f;
                chw[idxG++] = ((rgb >> 8) & 0xFF) / 255.0f;
                chw[idxB++] = (rgb & 0xFF) / 255.0f;
            }
        return chw;
    }

    /** 裁剪正方形 ROI；若超出原图自动截断并在空白处补黑 */
    private static BufferedImage cropSquare(BufferedImage src, int x, int y, int size) {
        int srcW = src.getWidth(), srcH = src.getHeight();

        // ① 计算与原图的交集
        int sx = Math.max(0, x);
        int sy = Math.max(0, y);
        int ex = Math.min(srcW, x + size);
        int ey = Math.min(srcH, y + size);

        // 如果完全落在图外，直接返回黑图
        if (sx >= ex || sy >= ey) {
            BufferedImage black = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g0 = black.createGraphics();
            g0.setColor(Color.BLACK); g0.fillRect(0, 0, size, size); g0.dispose();
            return black;
        }

        BufferedImage roi = src.getSubimage(sx, sy, ex - sx, ey - sy);

        // ② 若 roi 已经是 size×size，直接返回
        if (roi.getWidth() == size && roi.getHeight() == size) return roi;

        // ③ 否则将其画到黑底画布中央
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = dst.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, size, size);

        int dx = sx - x;          // roi 在画布中的偏移
        int dy = sy - y;
        g.drawImage(roi, dx, dy, null);
        g.dispose();
        return dst;
    }


    /** 从 [numAnchors][12] 中找出 score(=col4) 最高的行索引 */
    private static int argMaxScore(float[][] anchors) {
        int best = 0;
        float bestScore = anchors[0][4];
        for (int i = 1; i < anchors.length; i++) {
            if (anchors[i][4] > bestScore) {
                bestScore = anchors[i][4];
                best = i;
            }
        }
        return best;
    }

    /**
     * 在图片上绘制COCO人体关键点和骨架
     * @param img BufferedImage
     * @param keypoints float[17][3]，每个关键点[x, y, conf]
     * @param color 颜色
     */
    public static void drawPoseOnImage(BufferedImage img, float[][] keypoints, Color color) {
        // COCO骨架连接定义
        int[][] COCO_CONNECTIONS = {
            {0, 1}, {1, 3}, {0, 2}, {2, 4}, // 头部
            {5, 7}, {7, 9}, {6, 8}, {8, 10}, // 手臂
            {5, 6}, {5, 11}, {6, 12}, // 躯干
            {11, 13}, {13, 15}, {12, 14}, {14, 16} // 腿部
        };
        Graphics2D g = img.createGraphics();
        g.setStroke(new java.awt.BasicStroke(2));
        g.setColor(color);
        // 绘制骨架连线
        for (int[] conn : COCO_CONNECTIONS) {
            int i = conn[0], j = conn[1];
            if (keypoints[i][2] > 0.1f && keypoints[j][2] > 0.1f) {
                int x1 = Math.round(keypoints[i][0]);
                int y1 = Math.round(keypoints[i][1]);
                int x2 = Math.round(keypoints[j][0]);
                int y2 = Math.round(keypoints[j][1]);
                g.drawLine(x1, y1, x2, y2);
            }
        }
        // 绘制关键点
        for (int i = 0; i < 17; i++) {
            if (keypoints[i][2] > 0.1f) {
                int x = Math.round(keypoints[i][0]);
                int y = Math.round(keypoints[i][1]);
                g.fillOval(x - 3, y - 3, 7, 7);
                g.drawString(String.valueOf(i), x + 5, y - 5);
            }
        }
        g.dispose();
    }

    /* -------------------------- 资源释放 -------------------------- */
    public void close() {
        try {
            yoloPoseSession.close();
            stgcnSession.close();
            env.close();
        } catch (OrtException ignored) {}
    }

    /**
     * Letterbox缩放并填充到目标尺寸
     * 返回：填充后的图片、缩放比例、填充偏移
     */
    public static class LetterboxResult {
        public BufferedImage image;
        public float scale;
        public int padX, padY;
        public LetterboxResult(BufferedImage image, float scale, int padX, int padY) {
            this.image = image; this.scale = scale; this.padX = padX; this.padY = padY;
        }
    }
    public static LetterboxResult letterbox(BufferedImage src, int targetW, int targetH) {
        float scale = Math.min(targetW / (float)src.getWidth(), targetH / (float)src.getHeight());
        int newW = Math.round(src.getWidth() * scale);
        int newH = Math.round(src.getHeight() * scale);
        int padX = (targetW - newW) / 2;
        int padY = (targetH - newH) / 2;
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK); g.fillRect(0, 0, targetW, targetH);
        g.drawImage(src, padX, padY, newW, newH, null);
        g.dispose();
        return new LetterboxResult(out, scale, padX, padY);
    }
}
