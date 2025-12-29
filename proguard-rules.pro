# ---------- 基本开关（起步保守） ----------
-dontshrink                   # NEW: 先不收缩，避免被误删类导致运行期崩
-dontoptimize                    # NEW: 先不开优化，避免 JavaFX/Kotlin 反射坑
-overloadaggressively
-allowaccessmodification
-keeppackagenames               # 可选：保留包名层级，利于崩溃回溯
-verbose                      # NEW: 打印详细日志，便于定位
-ignorewarnings       # NEW: 先忽略警告，不因 unresolved 直接终止（只用于先跑通）

# ---------- 常见属性（保留注解/泛型/内联信息） ----------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,Record,StackMap,StackMapTable,SourceFile,LineNumberTable,LocalVariableTable,LocalVariableTypeTable
-renamesourcefileattribute SourceFile

# ---------- Kotlin 反射/元数据 ----------
-keep class kotlin.** { *; }                    # Kotlin 标准库
-keep class kotlinx.** { *; }
-keep class kotlin.Metadata { *; }              # 保留元数据
-dontwarn kotlin.**

# ---------- JavaFX / FXML 反射 ----------
-keep class javafx.** { *; }
-keep class com.sun.javafx.** { *; }
-keepclassmembers class * {
    @javafx.fxml.FXML *;
}
# 常见：Application / Controller 不要被混淆掉（按需加类名）
-keep class ** extends javafx.application.Application { *; }
-keep class *Controller { *; }

# ---------- ONNX Runtime（Java/JNI） ----------
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---------- JNA/JNI（若你有 JNA 或本地代码） ----------
-keep class com.sun.jna.** { *; }
-keep class * {
    native <methods>;
}

# ---------- SLF4J/Logback（按需） ----------
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ---------- ServiceLoader（若使用） ----------
-keep class META-INF.services.**  # 保留服务文件（配合 shadowJar 的 mergeServiceFiles）

# ---------- 你的入口类（必须保留类名+main签名，否则 bat/jpackage 启动不了） ----------
-keep public class org.example.Launcher {
    public static void main(java.lang.String[]);
}

# （可选但建议）如果 Launcher 内部直接调用了 Main.main(args)，也一并保留 main 签名：
-keep class org.example.Main {
    public static void main(java.lang.String[]);
}

# 如果你的 Main 同时是 JavaFX Application（很常见），建议再加一条：
# NEW: Main 继承 JavaFX Application（如不是可删）
-keep class org.example.Main extends javafx.application.Application { *; }   # NEW

# ---------- 你的 FXML 控制器（核心分析控制组件） ----------
# 你提到核心控制器为 org.example.VideoProcessController.java
# 它通常会被 FXML/反射加载，类名与带 @FXML 的字段/方法都要保留：
-keep class org.example.VideoProcessController { *; }  # NEW: 保留控制器本身
-keepclassmembers class org.example.VideoProcessController {  # NEW: 保留 @FXML 成员
    @javafx.fxml.FXML *;
}

# 如果你的项目里还有其它 *Controller 命名的控制器（建议一并保留）：
# （可选）保留所有以 Controller 结尾的类，避免 FXML 反射找不到：
-keep class **.*Controller { *; }                      # NEW(可选)

# ---------- 入口/服务加载（按需）
# 如你使用了 ServiceLoader/META-INF/services，请确保服务实现类不被移除：
# -keep class com.yourcompany.service.impl.** { *; }   # MODIFY: 如有，改成你的包名

# ---------- 报告、映射（保持不变或按你的输出路径） ----------
-printmapping build/obf/mapping.txt