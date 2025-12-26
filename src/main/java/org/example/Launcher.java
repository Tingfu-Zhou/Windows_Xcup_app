package org.example;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

public class Launcher {
    public static void main(String[] args) {
        // [关键修复] 在任何其他代码执行前，强制重置标准输出流为 UTF-8
        // 这能解决打包后 Windows 控制台中文乱码问题
        try {
            // 尝试让 Windows 控制台切换到 UTF-8 页 (65001)
            // 注意：这需要 Windows 10/11 且可能需要特定权限，失败也不影响后续流的重置
            new ProcessBuilder("cmd", "/c", "chcp", "65001").inheritIO().start().waitFor();
        } catch (Exception e) {
            // 忽略 chcp 失败
        }

        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
            System.setErr(new PrintStream(System.err, true, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        // 调用真正的主类
        Main.main(args);
    }
}

