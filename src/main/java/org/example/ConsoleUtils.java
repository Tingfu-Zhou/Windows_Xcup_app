package org.example;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 控制台编码处理工具类
 * 解决Windows下Java控制台中文乱码问题
 */
public class ConsoleUtils {
    private static boolean encodingFixed = false;
    
    static {
        fixConsoleEncoding();
    }
    
    /**
     * 修复控制台编码
     */
    public static void fixConsoleEncoding() {
        if (encodingFixed) return;
        
        try {
            // 设置系统属性
            System.setProperty("file.encoding", "UTF-8");
            System.setProperty("sun.jnu.encoding", "UTF-8");
            System.setProperty("console.encoding", "UTF-8");
            
            // 对于Windows系统，尝试设置控制台编码
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("windows")) {
                try {
                    // 尝试执行chcp命令设置控制台为UTF-8
                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "chcp", "65001");
                    pb.start().waitFor();
                } catch (Exception e) {
                    // 忽略失败，使用其他方法
                }
                
                // 重新设置System.out和System.err的编码
                try {
                    System.setOut(new PrintStream(System.out, true, "UTF-8"));
                    System.setErr(new PrintStream(System.err, true, "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    // 使用标准UTF-8
                    System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
                    System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
                }
            }
            
            encodingFixed = true;
            System.out.println("✅ 控制台编码已修复为UTF-8");
            
        } catch (Exception e) {
            System.err.println("⚠️ 控制台编码修复失败: " + e.getMessage());
        }
    }
    
    /**
     * 安全打印中文字符串
     */
    public static void println(String message) {
        try {
            // 确保以UTF-8编码输出
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            String utf8String = new String(bytes, StandardCharsets.UTF_8);
            System.out.println(utf8String);
        } catch (Exception e) {
            // 如果UTF-8失败，直接输出原始字符串
            System.out.println(message);
        }
    }
    
    /**
     * 安全打印中文字符串（不换行）
     */
    public static void print(String message) {
        try {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            String utf8String = new String(bytes, StandardCharsets.UTF_8);
            System.out.print(utf8String);
        } catch (Exception e) {
            System.out.print(message);
        }
    }
    
    /**
     * 格式化安全打印
     */
    public static void printf(String format, Object... args) {
        try {
            String message = String.format(format, args);
            println(message);
        } catch (Exception e) {
            System.out.printf(format, args);
        }
    }
    
    /**
     * 获取当前控制台编码信息
     */
    public static void printEncodingInfo() {
        println("\n" + "=".repeat(50));
        println("🔧 当前编码信息");
        println("=".repeat(50));
        println("文件编码: " + System.getProperty("file.encoding"));
        println("控制台编码: " + System.getProperty("console.encoding", "未设置"));
        println("JNU编码: " + System.getProperty("sun.jnu.encoding", "未设置"));
        println("默认字符集: " + java.nio.charset.Charset.defaultCharset());
        println("系统语言: " + System.getProperty("user.language"));
        println("系统国家: " + System.getProperty("user.country"));
        println("操作系统: " + System.getProperty("os.name"));
        println("=".repeat(50) + "\n");
    }
    
    /**
     * 测试中文显示
     */
    public static void testChineseDisplay() {
        println("\n🧪 中文显示测试:");
        println("简体中文: 你好世界！音频分析正常");
        println("特殊符号: ✅ ❌ ⚠️ 🎵 🎯 🔧");
        println("数字中文: 一二三四五六七八九十");
        println("英文数字: Hello World 123456");
        println("如果以上内容显示正常，说明编码已修复\n");
    }
}
