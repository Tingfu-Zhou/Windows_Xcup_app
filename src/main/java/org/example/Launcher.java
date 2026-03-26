package org.example;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

public class Launcher {
    public static void main(String[] args) {
        // 将日志重定向到文件，避免控制台泄露信息
        try {
            String logDir = System.getProperty("user.home")
                + File.separator + "AppData"
                + File.separator + "Local"
                + File.separator + "Xcup"
                + File.separator + "logs";
            new File(logDir).mkdirs();

            String logFile = logDir + File.separator + "app.log";
            PrintStream fileOut = new PrintStream(
                new FileOutputStream(logFile, false), true, "UTF-8");
            System.setOut(fileOut);
            System.setErr(fileOut);
        } catch (Exception e) {
            // 如果日志文件创建失败，静默忽略
        }

        // 调用真正的主类
        Main.main(args);
    }
}
