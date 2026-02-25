package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * BLE管理器 - 整合真实BLE功能到Xcup
 * 通过Python子进程实现BLE通信（使用Bleak库）
 * 替代原有的BluetoothHelper和BluetoothConnectDialog
 */
public class BLEManager {
    
    private static final Logger LOGGER = Logger.getLogger(BLEManager.class.getName());
    
    // ====== 协议常量（BLE通信协议 v1.0）======
    private static final byte VER = 0x01;
    private static final byte CMD_SET_PATTERN = 0x04;
    private static final byte CMD_STOP_ALL = 0x02;
    private static final byte CMD_QUERY_STATE = 0x03;
    private static final byte CMD_STATE_RPT = (byte)0x83;
    private static final byte CMD_HEARTBEAT = 0x06;
    private static final byte CMD_RESUME_APP = 0x12;  // 恢复App控制
    
    // StateReport扩展解析常量
    private static final int SRC_FW = 0, SRC_APP = 1, SRC_BUTTON = 2, SRC_SAFETY = 3;
    private static final int OWNER_IDLE = 0, OWNER_APP = 1, OWNER_LOCAL = 2;
    private static final int HOLD_NONE = 0, HOLD_TIMED = 1, HOLD_MANUAL = 2;
    
    // 模式定义
    private static final byte PATTERN_1 = 1;
    private static final byte PATTERN_2 = 2;
    private static final byte PATTERN_3 = 3;
    
    // LEVEL 档位定义（0..10）
    private static final int LEVEL_MIN = 0;
    private static final int LEVEL_MAX = 10;
    // LEVEL 保存最近一次发往设备的马达强度（finalFreq）
    private static byte LEVEL = LEVEL_MIN;
    
    // ====== 全局单例 ======
    public static BLEManager globalManager = null;
    
    // ====== Python进程管理 ======
    private Process pythonProcess;
    private BufferedReader pythonReader;
    private BufferedWriter pythonWriter;
    private final Gson gson = new Gson();
    private Thread pythonReaderThread;
    
    // ====== 状态管理 ======
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isPythonReady = new AtomicBoolean(false);
    private volatile boolean pausedByLocal = false;  // 本地按键暂停标志
    private byte seq = 0;
    
    // ====== 回调接口 ======
    private Consumer<Boolean> connectionCallback;  // 连接状态变化回调
    private Consumer<Boolean> pauseCallback;       // 暂停状态变化回调
    private Consumer<String> logCallback;          // 日志回调
    
    // ====== 接收数据缓冲 ======
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    
    // ====== 执行器 ======
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    /**
     * 构造函数
     */
    public BLEManager() {
        // 初始化时启动Python进程
        startPythonProcess();
    }
    
    /**
     * 设置连接状态变化回调
     */
    public void setConnectionCallback(Consumer<Boolean> callback) {
        this.connectionCallback = callback;
    }
    
    /**
     * 设置暂停状态变化回调
     */
    public void setPauseCallback(Consumer<Boolean> callback) {
        this.pauseCallback = callback;
    }
    
    /**
     * 设置日志回调
     */
    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }
    
    /**
     * 获取连接状态
     */
    public boolean isConnected() {
        return isConnected.get();
    }
    
    /**
     * 获取暂停状态
     */
    public boolean isPaused() {
        return pausedByLocal;
    }
    
    /**
     * 启动Python BLE服务进程
     */
    private void startPythonProcess() {
        try {
            log("启动Python BLE服务...");
            log("当前工作目录: " + System.getProperty("user.dir"));
            
            String exePath = getPythonExePath();
            ProcessBuilder pb;
            
            if (exePath != null) {
                log("使用打包的 BLE 服务: " + exePath);
                pb = new ProcessBuilder(exePath);
            } else {
                // 回退到 Python 脚本
                String scriptPath = getPythonScriptPath();
                log("未找到 ble_service.exe，尝试使用 Python 脚本: " + scriptPath);
                
                // 检查脚本文件是否存在
                File scriptFile = new File(scriptPath);
                if (!scriptFile.exists()) {
                    log("错误: 找不到 ble_service.py。请确认文件已正确打包。");
                }
                
                pb = new ProcessBuilder("python", scriptPath);
                
                // 设置环境变量
                Map<String, String> env = pb.environment();
                env.put("PYTHONUNBUFFERED", "1"); // 禁用Python缓冲
            }
            
            pb.redirectErrorStream(false);
            
            // 启动进程
            pythonProcess = pb.start();
            
            // 获取输入输出流
            pythonReader = new BufferedReader(new InputStreamReader(pythonProcess.getInputStream()));
            pythonWriter = new BufferedWriter(new OutputStreamWriter(pythonProcess.getOutputStream()));
            
            // 启动读取线程
            startPythonReaderThread();
            
            // 获取错误流
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(pythonProcess.getErrorStream()));
            Thread errorThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        final String errorLine = line;
                        log("[Python错误] " + errorLine);
                    }
                } catch (IOException e) {
                    // 忽略
                }
            });
            errorThread.setDaemon(true);
            errorThread.start();
            
        } catch (Exception e) {
            log("启动Python进程失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 获取Python可执行文件路径
     */
    private String getPythonExePath() {
        // 按照优先级查找
        String[] candidates = {
            "ble_service.exe",                      // 1. 当前目录（最理想）
            "app/ble_service.exe",                  // 2. jpackage app子目录
            "../ble_service.exe",                   // 3. 上级目录
            "build/py/dist/ble_service.exe"         // 4. 开发构建目录
        };
        
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * 获取Python脚本路径
     */
    private String getPythonScriptPath() {
        String[] candidates = {
            "ble_service.py",                       // 1. 当前目录
            "app/ble_service.py",                   // 2. app目录
            "resources/ble_service.py",             // 3. resources资源目录
            "app/resources/ble_service.py",         // 4. app/resources
            "src/main/resources/ble_service.py"     // 5. 源码目录
        };
        
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }
        
        log("警告: 未找到ble_service.py，将尝试直接使用文件名");
        return "ble_service.py";
    }
    
    /**
     * 启动Python输出读取线程
     */
    private void startPythonReaderThread() {
        pythonReaderThread = new Thread(() -> {
            try {
                String line;
                while ((line = pythonReader.readLine()) != null) {
                    handlePythonMessage(line);
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log("Python进程通信中断: " + e.getMessage());
                }
            }
        });
        pythonReaderThread.setDaemon(true);
        pythonReaderThread.start();
    }
    
    /**
     * 处理Python发来的消息
     */
    private void handlePythonMessage(String jsonMessage) {
        try {
            JsonObject message = JsonParser.parseString(jsonMessage).getAsJsonObject();
            String type = message.get("type").getAsString();
            
            switch (type) {
                case "ready":
                    log("Python BLE服务已就绪");
                    isPythonReady.set(true);
                    break;
                
                case "log":
                    String level = message.get("level").getAsString();
                    String msg = message.get("message").getAsString();
                    if ("TX".equals(level) || "RX".equals(level)) {
                        log("[" + level + "] " + msg);
                    } else {
                        log(msg);
                    }
                    break;
                
                case "scan_result":
                    if (message.has("error")) {
                        log("扫描失败: " + message.get("error").getAsString());
                    } else {
                        boolean targetFound = message.get("target_found").getAsBoolean();
                        if (targetFound) {
                            String targetName = message.get("target_name").getAsString();
                            log("找到目标设备: " + targetName);
                            // 自动连接
                            connectToDevice(null);
                        } else {
                            log("未找到目标设备");
                        }
                    }
                    break;
                
                case "connect_result":
                    boolean success = message.get("success").getAsBoolean();
                    if (success) {
                        isConnected.set(true);
                        log("设备连接成功");
                        if (connectionCallback != null) {
                            Platform.runLater(() -> connectionCallback.accept(true));
                        }
                    } else {
                        isConnected.set(false);
                        String error = message.has("error") ? message.get("error").getAsString() : "未知错误";
                        log("连接失败: " + error);
                        if (connectionCallback != null) {
                            Platform.runLater(() -> connectionCallback.accept(false));
                        }
                    }
                    break;
                
                case "disconnect_result":
                    isConnected.set(false);
                    setPaused(false);
                    log("已断开连接");
                    if (connectionCallback != null) {
                        Platform.runLater(() -> connectionCallback.accept(false));
                    }
                    break;
                
                case "notification":
                    String hexData = message.get("data").getAsString();
                    byte[] data = hexStringToBytes(hexData);
                    processReceivedData(data);
                    break;
                
                case "connection_lost":
                    isConnected.set(false);
                    setPaused(false);
                    log("设备连接已丢失");
                    if (connectionCallback != null) {
                        Platform.runLater(() -> connectionCallback.accept(false));
                    }
                    break;
                
                case "send_result":
                    boolean sendSuccess = message.get("success").getAsBoolean();
                    if (!sendSuccess && message.has("error")) {
                        log("发送失败: " + message.get("error").getAsString());
                    }
                    break;
                
                case "error":
                    log("错误: " + message.get("message").getAsString());
                    break;
            }
            
        } catch (Exception e) {
            log("解析Python消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送命令到Python进程
     */
    private void sendPythonCommand(JsonObject command) {
        if (!isPythonReady.get()) {
            log("Python服务未就绪");
            return;
        }
        
        try {
            String json = gson.toJson(command) + "\n";
            pythonWriter.write(json);
            pythonWriter.flush();
        } catch (IOException e) {
            log("发送命令失败: " + e.getMessage());
        }
    }
    
    /**
     * 扫描并连接BLE设备
     */
    public void scanAndConnect() {
        if (!isPythonReady.get()) {
            log("Python服务未就绪，无法扫描");
            return;
        }
        
        log("开始扫描BLE设备...请耐心等待");
        
        JsonObject command = new JsonObject();
        command.addProperty("action", "scan");
        command.addProperty("timeout", 10.0);
        sendPythonCommand(command);
    }
    
    /**
     * 连接到设备
     */
    private void connectToDevice(String address) {
        log("正在连接设备...");
        
        JsonObject command = new JsonObject();
        command.addProperty("action", "connect");
        if (address != null) {
            command.addProperty("address", address);
        }
        sendPythonCommand(command);
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        log("断开连接...");
        
        JsonObject command = new JsonObject();
        command.addProperty("action", "disconnect");
        sendPythonCommand(command);
        
        isConnected.set(false);
        setPaused(false);
    }
    
    /**
     * 发送恢复App控制命令
     */
    public void sendResumeAppControl() {
        if (!isConnected.get()) {
            log("设备未连接，无法发送恢复命令");
            return;
        }
        
        log("发送恢复App控制命令...");
        byte[] frame = buildResumeFrame();
        sendBLEData(frame);
    }
    
    /**
     * 设置暂停状态
     */
    private void setPaused(boolean paused) {
        pausedByLocal = paused;
        if (paused) {
            log("App操作已暂停（本地按键优先）");
        } else {
            log("App控制已恢复");
        }
        if (pauseCallback != null) {
            Platform.runLater(() -> pauseCallback.accept(paused));
        }
    }
    
    /**
     * 发送动作命令（从视频/音频分析结果映射）
     * @param action 动作名称 ("oral", "do", "Noise"等)
     */
    public void sendAction(String action) {
        logActionPreview(action, null);
        if (!isConnected.get() || pausedByLocal) {
            return;  // 未连接或已暂停时不发送
        }
        
        byte[] frame = buildFrameForAction(action);
        if (frame != null) {
            sendBLEData(frame);
        }
    }
    
    /**
     * 发送带档位的动作命令（0..10）。0 视为停止。
     * 说明：当前协议仅有三级强度(L/M/H)，此处将 1..10 映射为 L/M/H 三档：
     *   - 1..3 -> L
     *   - 4..7 -> M
     *   - 8..10 -> H
     * 若设备后续支持更细粒度强度，可在此处替换映射。
     */
    public void sendAction(String action, int level) {
        logActionPreview(action, level);
        if (!isConnected.get() || pausedByLocal) {
            return;
        }
        if (action == null || action.isEmpty()) {
            log("sendAction: 忽略空动作");
            return;
        }
        if (level <= LEVEL_MIN || "Noise".equals(action)) {
            // 0 档或噪声 -> 停止
            byte[] stop = buildStopAllFrame();
            sendBLEData(stop);
            return;
        }
        
        byte normalizedLevel = clampLevel(level);
        LEVEL = normalizedLevel;
        byte[] frame = buildFrameForActionWithLevel(action, normalizedLevel);
        if (frame != null) {
            sendBLEData(frame);
        }
    }
    
    private byte clampLevel(int level) {
        if (level <= LEVEL_MIN) {
            return (byte) LEVEL_MIN;
        }
        if (level >= LEVEL_MAX) {
            return (byte) LEVEL_MAX;
        }
        return (byte) level;
    }
    
    /**
     * 离线/调试模式下打印动作与档位，便于无设备时观察。
     */
    private void logActionPreview(String action, Integer levelOverride) {
        String levelInfo = (levelOverride != null)
                ? String.valueOf(levelOverride)
                : ((LEVEL & 0xFF) + " (缓存LEVEL)");
        log(String.format("BLE调试：action=%s level=%s (connected=%s paused=%s)",
                action, levelInfo, isConnected.get(), pausedByLocal));
    }
    
    /**
     * 发送数据到BLE设备
     */
    private void sendBLEData(byte[] data) {
        if (!isConnected.get()) {
            return;
        }
        
        String hexData = bytesToHexString(data);
        
        JsonObject command = new JsonObject();
        command.addProperty("action", "send");
        command.addProperty("data", hexData);
        sendPythonCommand(command);
    }
    
    /**
     * 处理接收到的数据
     */
    private void processReceivedData(byte[] data) {
        synchronized (receiveBuffer) {
            receiveBuffer.write(data, 0, data.length);
            
            // 尝试解析帧
            while (true) {
                byte[] buffer = receiveBuffer.toByteArray();
                if (buffer.length < 9) {
                    break;
                }
                
                // 查找帧头
                int frameStart = -1;
                for (int i = 0; i <= buffer.length - 2; i++) {
                    if (buffer[i] == (byte)0xAA && buffer[i + 1] == (byte)0x55) {
                        frameStart = i;
                        break;
                    }
                }
                
                if (frameStart == -1) {
                    receiveBuffer.reset();
                    break;
                }
                
                if (frameStart > 0) {
                    receiveBuffer.reset();
                    receiveBuffer.write(buffer, frameStart, buffer.length - frameStart);
                    buffer = receiveBuffer.toByteArray();
                }
                
                if (buffer.length < 9) {
                    break;
                }
                
                // 解析长度
                int len = (buffer[5] & 0xFF) | ((buffer[6] & 0xFF) << 8);
                int frameLength = 9 + len;
                
                if (buffer.length < frameLength) {
                    break;
                }
                
                // 提取完整帧
                byte[] frame = Arrays.copyOfRange(buffer, 0, frameLength);
                parseIncomingFrame(frame);
                
                // 移除已处理的帧
                receiveBuffer.reset();
                if (buffer.length > frameLength) {
                    receiveBuffer.write(buffer, frameLength, buffer.length - frameLength);
                }
            }
        }
    }
    
    /**
     * 解析接收到的帧
     */
    private void parseIncomingFrame(byte[] frame) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
            
            // SOF
            byte sof0 = bb.get(), sof1 = bb.get();
            if (sof0 != (byte)0xAA || sof1 != (byte)0x55) {
                return;
            }
            
            byte ver = bb.get();
            byte cmd = bb.get();
            byte rseq = bb.get();
            short len = bb.getShort();
            
            if (len < 0 || len > 512) {
                return;
            }
            
            byte[] payload = new byte[len];
            bb.get(payload);
            
            short receivedCrc = bb.getShort();
            
            // 验证CRC
            ByteBuffer crcData = ByteBuffer.allocate(5 + len).order(ByteOrder.LITTLE_ENDIAN);
            crcData.put(ver).put(cmd).put(rseq).putShort(len).put(payload);
            int calculatedCrc = calculateCRC16(crcData.array());
            
            if ((receivedCrc & 0xFFFF) != calculatedCrc) {
                log("CRC校验失败");
                return;
            }
            
            // 处理命令
            // [MOD] 处理命令：对齐 Android 的 ACK 处理结构
            if (cmd == (0x80 + CMD_SET_PATTERN) ||
                    cmd == (0x80 + CMD_STOP_ALL) ||
                    cmd == (0x80 + CMD_RESUME_APP)) {

                // ACK
                if (payload.length >= 2) {
                    int ackSeq = payload[0] & 0xFF;     // [MOD] 对齐 Android：无符号
                    int status = payload[1] & 0xFF;     // [ADD] 对齐 Android：无符号
                    log(String.format("收到ACK: CMD=0x%02X SEQ=%d STATUS=%d",
                            cmd & 0xFF, ackSeq, status));

                    // 锁定期：ACK=BUSY -> 进入暂停态（Android 对 RESUME_APP 也生效）
                    if (status == 1) { // BUSY
                        setPaused(true);
                    }

                    // 恢复命令成功：RESUME_APP 且 OK -> 解除暂停
                    if (cmd == (0x80 + CMD_RESUME_APP) && status == 0) { // OK
                        setPaused(false);
                    }
                }

            } else if (cmd == CMD_STATE_RPT) {
                log("收到状态报告，长度=" + payload.length);
                parseStateReport(payload);
            } else {
                log(String.format("收到命令: 0x%02X 长度=%d", cmd & 0xFF, len));
            }

        } catch (Exception e) {
            log("解析帧失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析StateReport扩展字段
     */
    private void parseStateReport(byte[] p) {
        try {
            int off = 0;
            if (p.length < 12) return;
            
            // 跳过基础字段
            off += 2; // FW_VER
            off += 2; // BAT_mV
            off += 2; // TEMP_dC
            off += 1; // CH_CNT
            off += 1; // CUR_PATTERN
            off += 1; // CUR_INTLVL
            off += 2; // RUN_REMAIN
            off += 1; // FLAGS
            
            // 扩展字段（至少9字节）
            if (p.length >= off + 9) {
                int rev = ((p[off] & 0xFF) | ((p[off+1] & 0xFF) << 8));
                off += 2;
                
                int src = (p[off++] & 0xFF);
                int chgMask = (p[off++] & 0xFF);
                int owner = (p[off++] & 0xFF);
                int holdMode = (p[off++] & 0xFF);
                int holdTtl = ((p[off] & 0xFF) | ((p[off+1] & 0xFF) << 8));
                off += 2;
                int btnCode = (p[off++] & 0xFF);
                
                log(String.format("StateReport扩展: src=%d owner=%d holdMode=%d holdTtl=%d",
                        src, owner, holdMode, holdTtl));
                
                // 根据状态更新暂停标志
                if (src == SRC_BUTTON && holdMode == HOLD_MANUAL) {
                    setPaused(true);
                }
                if (holdMode == HOLD_NONE) {
                    setPaused(false);
                }
            }
        } catch (Exception e) {
            log("解析StateReport失败: " + e.getMessage());
        }
    }
    
    /**
     * 构建动作对应的帧
     * 将视频/音频分析的动作映射到设备命令
     */
    private byte[] buildFrameForAction(String action) {
        switch (action) {
            case "oral":
                return buildSetPatternFrame(PATTERN_1, LEVEL, 2000, (byte)0);
            case "do":
                return buildSetPatternFrame(PATTERN_1, LEVEL, 2000, (byte)0);
            case "Noise":
                return buildStopAllFrame();
            default:
                log("未知动作: " + action);
                return null;
        }
    }
    
    /**
     * 与 buildFrameForAction 类似，但允许覆盖强度（档位）字节。
     * 
     */
    private byte[] buildFrameForActionWithLevel(String action, byte intLevel) {
        switch (action) {
            case "oral":
                return buildSetPatternFrame(PATTERN_1, intLevel, 0, (byte)1);
            case "do":
                return buildSetPatternFrame(PATTERN_1, intLevel, 0, (byte)1);
            case "Noise":
                return buildStopAllFrame();
            default:
                log("未知动作: " + action);
                return null;
        }
    }
    
    private byte[] buildSetPatternFrame(byte patternId, byte intLevel, int durationMs, byte flags) {
        ByteBuffer payload = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(patternId);
        payload.put(intLevel);
        payload.putShort((short)durationMs);
        payload.put(flags);
        
        return buildFrame(CMD_SET_PATTERN, payload.array());
    }
    

    
    private byte[] buildStopAllFrame() {
        return buildFrame(CMD_STOP_ALL, new byte[0]);
    }
    
    private byte[] buildResumeFrame() {
        return buildFrame(CMD_RESUME_APP, new byte[0]);
    }
    
    private byte[] buildFrame(byte cmd, byte[] payload) {
        int len = payload.length;
        ByteBuffer frame = ByteBuffer.allocate(9 + len).order(ByteOrder.LITTLE_ENDIAN);
        
        // SOF
        frame.put((byte)0xAA).put((byte)0x55);
        // VER
        frame.put(VER);
        // CMD
        frame.put(cmd);
        // SEQ
        frame.put(seq);
        // LEN
        frame.putShort((short)len);
        // PAYLOAD
        frame.put(payload);
        
        // CRC
        ByteBuffer crcData = ByteBuffer.allocate(5 + len).order(ByteOrder.LITTLE_ENDIAN);
        crcData.put(VER).put(cmd).put(seq).putShort((short)len).put(payload);
        int crc = calculateCRC16(crcData.array());
        frame.putShort((short)crc);
        
        seq++;
        
        return frame.array();
    }
    
    /**
     * CRC16-CCITT计算
     */
    private int calculateCRC16(byte[] data) {
        int crc = 0xFFFF;
        
        for (byte b : data) {
            crc ^= ((b & 0xFF) << 8);
            
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        
        return crc;
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
    
    /**
     * 十六进制字符串转字节数组
     */
    private byte[] hexStringToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
    
    /**
     * 日志输出
     */
    private void log(String message) {
        System.out.println("[BLEManager] " + message);
        if (logCallback != null) {
            Platform.runLater(() -> logCallback.accept(message));
        }
    }
    
    /**
     * 关闭并释放资源
     */
    public void close() {
        log("正在关闭BLE管理器...");
        
        // 断开BLE连接
        if (isConnected.get()) {
            disconnect();
        }
        
        // 发送退出命令给Python
        if (isPythonReady.get()) {
            JsonObject command = new JsonObject();
            command.addProperty("action", "exit");
            sendPythonCommand(command);
        }
        
        // 等待Python进程结束
        if (pythonProcess != null) {
            try {
                pythonProcess.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 忽略
            }
            pythonProcess.destroyForcibly();
        }
        
        // 关闭调度器
        scheduler.shutdownNow();
        
        log("BLE管理器已关闭");
    }
}

