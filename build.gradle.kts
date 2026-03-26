import org.gradle.internal.os.OperatingSystem

plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.0.14"
}

group = "org.example"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenLocal()  // 优先使用本地缓存
    maven { url = uri("https://maven.aliyun.com/repository/central") }  // 阿里云镜像更快
    mavenCentral()
}

// -------------------- 代码混淆（ProGuard） --------------------
// [ADD] ProGuard 依赖（用于 JVM 字节码混淆）
val proguardVersion = "7.5.0"
configurations.create("proguard")

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")
    implementation("org.bytedeco:javacv-platform:1.5.10")
    
    // JNA 用于 WASAPI 音频捕获
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("net.java.dev.jna:jna-platform:5.13.0")
    
    // Gson 用于 BLE Python 进程通信
    implementation("com.google.code.gson:gson:2.10.1")
    
    // [保留旧的蓝牙库，以防需要回退]
    // implementation("net.sf.bluecove:bluecove:2.1.0")
    // runtimeOnly("net.sf.bluecove:bluecove-gpl:2.1.0")
    
    implementation("org.slf4j:slf4j-simple:2.0.13")
    implementation("org.openjfx:javafx-controls:17.0.12")
    implementation("org.openjfx:javafx-fxml:17.0.12")
    implementation("org.openjfx:javafx-swing:17.0.12")
    "proguard"("com.guardsquare:proguard-base:$proguardVersion")  // [ADD] ProGuard
}

javafx {
    version = "17.0.12"
    modules = listOf("javafx.controls", "javafx.media", "javafx.swing")
}

val appVersionOverride = (findProperty("appVersionOverride") as String?)
    ?.takeUnless { it.isBlank() }

application {
    mainClass.set("org.example.Launcher")
    applicationName = "Xcup"
    
    // 设置运行时编码为UTF-8
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dconsole.encoding=UTF-8",
        "-Dapp.version=${appVersionOverride ?: version}"
    )
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Class-Path" to configurations.runtimeClasspath.get().joinToString(" ") { it.name }
        )
    }
}

// 添加任务：准备本地库
tasks.create<Copy>("copyNativeLibs") {
    doFirst {
        // 创建目标目录
        file("${layout.buildDirectory.get()}/native-libs").mkdirs()
    }

    // 从依赖的 JAR 中提取 DLL 文件
    from(configurations.runtimeClasspath.get().map { zipTree(it) }) {
        include("**/*.dll")
        include("**/*.so")
        include("**/*.dylib")
    }

    into("${layout.buildDirectory.get()}/native-libs")

    // 扁平化目录结构
    eachFile {
        path = name
    }

    // 去重
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    doLast {
        println("Extracted native libraries:")
        fileTree("${layout.buildDirectory.get()}/native-libs").forEach {
            println(" - ${it.name}")
        }
    }
}

// 生成并执行本地DLL构建脚本（仅Windows）
val writeWasapiBuildScript = tasks.register("writeWasapiBuildScript") {
    onlyIf { OperatingSystem.current().isWindows }
    doLast {
        val scriptDir = file("build-scripts")
        scriptDir.mkdirs()
        val bat = file("build-scripts/build_wasapi.bat")
        bat.writeText(
            """
            @echo off
            setlocal enableextensions enabledelayedexpansion
            chcp 65001 > nul
            echo ===== 构建 wasapi_loopback.dll =====
            
            if "%JAVA_HOME%"=="" (
              echo [ERROR] 未检测到 JAVA_HOME，请先安装JDK并设置环境变量。
              exit /b 1
            )
            
            set SRC=native\wasapi_loopback.cpp
            if not exist %SRC% (
              echo [ERROR] 找不到 %SRC%
              exit /b 1
            )
            
            set OUT=wasapi_loopback.dll
            
            rem 优先尝试 MSVC (vcvars64)
            set VC_VCVARS=
            for %%P in ("C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\VC\\Auxiliary\\Build\\vcvars64.bat" \
                        "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Auxiliary\\Build\\vcvars64.bat" \
                        "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Community\\VC\\Auxiliary\\Build\\vcvars64.bat" \
                        "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\VC\\Auxiliary\\Build\\vcvars64.bat") do (
              if exist %%~fP (
                set VC_VCVARS=%%~fP
                goto :found_vc
              )
            )
            :found_vc
            if not "%VC_VCVARS%"=="" (
              echo [INFO] 使用 MSVC 编译: %VC_VCVARS%
              call "%VC_VCVARS%" > nul
              cl /nologo /EHsc /O2 /LD %SRC% /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" /link /OUT:%OUT% ole32.lib avrt.lib
              if exist %OUT% (
                echo [OK] 生成 %OUT%
                exit /b 0
              ) else (
                echo [WARN] MSVC 编译失败，尝试 MinGW-w64
              )
            ) else (
              echo [INFO] 未找到MSVC，将尝试 MinGW-w64 (g++)
            )
            
            rem 尝试 MinGW-w64 g++
            where g++ > nul 2> nul
            if %errorlevel%==0 (
              g++ -std=gnu++17 -O2 -shared -o %OUT% %SRC% -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" -lole32 -lavrt
              if exist %OUT% (
                echo [OK] 生成 %OUT%
                exit /b 0
              ) else (
                echo [ERROR] g++ 编译失败
                exit /b 1
              )
            ) else (
              echo [ERROR] 未检测到编译器。请安装 Visual Studio Build Tools 或 MinGW-w64。
              exit /b 1
            )
            
            endlocal
            """.trimIndent()
        )
    }
}

// [已废弃] 原 C# 音频捕获 CLI - 现已改用 FFmpeg
// 构建 C# 音频捕获 CLI（仅 Windows 且安装了 .NET SDK）
// val buildAudioCaptureCli = tasks.register("buildAudioCaptureCli") {
//     onlyIf { OperatingSystem.current().isWindows }
//     doLast {
//         try {
//             exec {
//                 commandLine("dotnet", "publish", "tools/audio-capture-cli/AudioCaptureCli.csproj",
//                     "-c", "Release",
//                     "-r", "win-x64",
//                     "--self-contained", "false",
//                     "-o", "${layout.buildDirectory.get()}/audio-cli")
//             }
//             println("[AudioCLI] Published to ${layout.buildDirectory.get()}/audio-cli")
//         } catch (e: Exception) {
//             println("[AudioCLI] dotnet publish failed: ${e.message}")
//             throw e
//         }
//     }
// }

val buildWasapiDll = tasks.register("buildWasapiDll") {
    onlyIf { OperatingSystem.current().isWindows }
    dependsOn(writeWasapiBuildScript)
    doLast {
        exec {
            commandLine("cmd", "/c", "build-scripts\\build_wasapi.bat")
        }
        // 将生成的DLL复制到构建输出和项目根，便于运行
        val dll = file("wasapi_loopback.dll")
        if (dll.exists()) {
            copy {
                from(dll)
                into(layout.buildDirectory.dir("dist/Xcup").get())
            }
        }
    }
}

// Python 相关配置（用于 BLE 服务）
val pyWorkDir = layout.buildDirectory.dir("py")
val venvPython = pyWorkDir.map { it.file("venv/Scripts/python.exe") }

// 创建 Python venv
tasks.register<Exec>("pySetupVenv") {
    group = "python"
    description = "Create Python venv and bootstrap pip"
    workingDir = pyWorkDir.get().asFile
    doFirst { workingDir.mkdirs() }
    commandLine("cmd", "/c", """
        python -m venv venv --clear ^
        && venv\Scripts\python -m pip config set global.index-url https://pypi.org/simple ^
        && venv\Scripts\python -m pip install -U pip wheel setuptools
    """.trimIndent())
}

// 安装 Python 依赖
tasks.register<Exec>("pyInstallDeps") {
    group = "python"
    description = "Install Python dependencies for BLE service"
    dependsOn("pySetupVenv")
    workingDir = pyWorkDir.get().asFile

    // 使用阿里云镜像
    commandLine(
        venvPython.get().asFile.absolutePath,
        "-m", "pip", "install",
        "--index-url", "https://mirrors.aliyun.com/pypi/simple/",
        "--trusted-host", "mirrors.aliyun.com",
        "bleak", "pyinstaller", "winsdk"
    )
}

// 构建 Python EXE（使用 PyInstaller）
tasks.register<Exec>("pyBuild") {
    group = "python"
    description = "Build Python BLE service as standalone EXE"
    dependsOn("pyInstallDeps")
    workingDir = pyWorkDir.get().asFile

    val scriptPath = project.file("src/main/resources/ble_service.py").absolutePath
    val exeOutput = layout.buildDirectory.file("py/dist/ble_service.exe")

    inputs.file(scriptPath)
    outputs.file(exeOutput)

    commandLine(
        venvPython.get().asFile.absolutePath,
        "-m", "PyInstaller",
        "--onefile",
        "--noconsole",
        "--name", "ble_service",
        "--hidden-import", "winsdk.windows.devices.bluetooth",
        "--hidden-import", "winsdk.windows.devices.enumeration",
        "--hidden-import", "winsdk.windows.devices.bluetooth.genericattributeprofile",
        scriptPath
    )
}

// 创建分发任务
// -------------------- 代码混淆（ProGuard）任务定义 --------------------
// [ADD] 说明：将 build/libs/Xcup-{version}.jar 混淆输出到 build/obf/，并在分发阶段使用混淆产物
val appNameForObf = "Xcup"  // 如需改应用名，请与 applicationName 保持一致
val obfDir = layout.buildDirectory.dir("obf")
val obfJar = obfDir.map { it.file("${appNameForObf}-${version}-obf.jar") }
val mappingFile = obfDir.map { it.file("mapping.txt") }

val runtimeCpForObf = configurations.getByName("runtimeClasspath")  // [ADD]

// JDK jmods（给 ProGuard 作为 libraryjars，避免误删 JDK 类）
val jmodsDirForObf = file("${System.getProperty("java.home")}/jmods")
val jmodFilesForObf = jmodsDirForObf.listFiles { f -> f.extension == "jmod" }?.toList() ?: emptyList()

tasks.register<JavaExec>("proguardRelease") {
    dependsOn(tasks.jar)  // [ADD] 先产出未混淆的主 jar
    classpath = configurations.getByName("proguard")
    mainClass.set("proguard.ProGuard")

    doFirst {
        obfDir.get().asFile.mkdirs()

        val inJar = tasks.jar.get().archiveFile.get().asFile
        val outJar = obfJar.get().asFile
        val mapFile = mappingFile.get().asFile

        // 生成 ProGuard 参数文件（避免命令行过长/转义问题）
        val argFile = file("${obfDir.get().asFile}/proguard-args.txt")
        val lines = mutableListOf<String>()

        lines += "-verbose"
        lines += "-injars ${inJar.absolutePath}"
        lines += "-outjars ${outJar.absolutePath}"

        // 规则文件（请确保项目根目录存在 proguard-rules.pro）
        lines += "@${file("proguard-rules.pro").absolutePath}"

        // 输出 mapping
        lines += "-printmapping ${mapFile.absolutePath}"

        // 作为 libraryjars：运行时依赖 + JDK jmods
        runtimeCpForObf.files.forEach { f ->
            lines += "-libraryjars ${f.absolutePath}"
        }
        jmodFilesForObf.forEach { f ->
            lines += "-libraryjars ${f.absolutePath}"
        }

        // 写入参数文件
        argFile.writeText(lines.joinToString(System.lineSeparator()), Charsets.UTF_8)

        // ProGuard 以 @argsfile 形式读取
        args("@${argFile.absolutePath}")

        println("[ProGuard] in : ${inJar.absolutePath}")
        println("[ProGuard] out: ${outJar.absolutePath}")
        println("[ProGuard] map: ${mapFile.absolutePath}")
    }

    // 给混淆更大的堆，避免 OOM（可按需调大）
    jvmArgs("-Xms256m", "-Xmx2g")
}


tasks.create<Copy>("prepareDistribution") {
    dependsOn("build", "copyNativeLibs", "pyBuild", "proguardRelease")  // [MOD] 增加混淆产物  // 添加 pyBuild 依赖

    val distDir = layout.buildDirectory.dir("dist/Xcup")

    // 复制主 JAR（使用混淆后的产物）
    from(obfJar) {  // [MOD]
        rename { "Xcup-${version}.jar" }
    }
    into(distDir)

    // 复制所有依赖（包括 JavaFX）
    from(configurations.runtimeClasspath) {
        into("lib")
    }

    // 复制本地库到根目录和 lib 目录
    from("${layout.buildDirectory.get()}/native-libs") {
        include("*.dll")
        into(".")  // 复制到根目录
    }
    from("${layout.buildDirectory.get()}/native-libs") {
        include("*.dll")
        into("lib")  // 也复制到 lib 目录
    }

    // 特别确保 JavaFX 被包含
    doFirst {
        // 打印所有依赖，用于调试
        println("Runtime classpath contains:")
        configurations.runtimeClasspath.get().files.forEach { file ->
            println(" - ${file.name}")
        }
    }

    // 复制资源文件（仅包含实际使用的模型与图标）
    from("src/main/resources") {
        include("icon.ico")
        //include("models/yolov8-pose.onnx")
        //include("models/stgcnpp.onnx")
        //include("models/yamnet.onnx")
        //include("models/yamnet_finetuned.onnx")
        // [模型加密] 打包加密后的模型
        include("models/yolov8-pose.onnx.enc")
        include("models/stgcnpp.onnx.enc")
        include("models/yamnet.onnx.enc")
        include("models/yamnet_finetuned.onnx.enc")
        include("ble_service.py")  // BLE Python 服务脚本
        into("resources")
    }

    // 复制项目根目录的必要文件
    from(".") {
        include("ffmpeg.exe")
        include("wasapi_loopback.dll")  // 添加 wasapi_loopback.dll
        into(".")
    }
    
    // 复制 BLE Python 脚本到根目录（方便查找）
    from("src/main/resources") {
        include("ble_service.py")
        into(".")
    }
    
    // 复制打包好的 Python EXE（优先使用）
    from(layout.buildDirectory.file("py/dist/ble_service.exe")) {
        into(".")
    }

    // 创建启动脚本
    doLast {
        val scriptDir = distDir.get().asFile

        // Windows 批处理文件 - 修正路径
        val batFile = File(scriptDir, "Xcup.bat")
        batFile.writeText("""
            @echo off
            set JAVA_HOME=%JAVA_HOME%
            set PATH=%JAVA_HOME%\bin;%PATH%
            
            REM 设置当前目录为库搜索路径
            set PATH=%~dp0;%~dp0\lib;%PATH%
            
            REM 获取 JavaFX 路径
            set MODULE_PATH=lib
            set CLASS_PATH=Xcup-${version}.jar;lib/*
            
            java -Xmx2g -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dconsole.encoding=UTF-8 -Dapp.version=${version} -Djava.library.path="%~dp0;%~dp0\lib" -cp "%CLASS_PATH%" org.example.Launcher %*
        """.trimIndent())

        // 创建 VBS 脚本用于隐藏控制台窗口
        val vbsFile = File(scriptDir, "Xcup.vbs")
        vbsFile.writeText("""
            Set WshShell = CreateObject("WScript.Shell")
            WshShell.Run chr(34) & "Xcup.bat" & Chr(34), 0
            Set WshShell = Nothing
        """.trimIndent())

        // 创建调试脚本
        val debugFile = File(scriptDir, "Xcup-debug.bat")
        // 使用 UTF-8 with BOM 写入，避免中文乱码
        debugFile.writeText("\uFEFF" + """
            @echo off
            chcp 65001 > nul
            echo ===== Xcup Debug Mode =====
            echo.
            echo Checking JavaFX modules in lib directory...
            dir lib\javafx*.jar
            echo.
            echo Checking native libraries...
            dir *.dll
            echo.
            echo Starting application...
            echo.
            
            set PATH=%~dp0;%~dp0\lib;C:\Windows\System32;%PATH%
            set MODULE_PATH=lib
            set CLASS_PATH=Xcup-${version}.jar;lib/*
            
            REM Fix Chinese system encoding
            set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dconsole.encoding=UTF-8 -Dapp.version=${version}
            
            java -Xmx2g -Djava.library.path="%~dp0;%~dp0\lib" -cp "%CLASS_PATH%" org.example.Launcher
            
            echo.
            pause
        """.trimIndent(), Charsets.UTF_8)
    }
}

// 使用 jpackage 创建安装包的任务
tasks.create("createInstaller") {
    dependsOn("prepareDistribution")

    doLast {
        val distDir = layout.buildDirectory.dir("dist/Xcup").get().asFile
        val outputDir = layout.buildDirectory.dir("installer").get().asFile
        outputDir.mkdirs()

        val jpackageExe = if (OperatingSystem.current().isWindows) "jpackage.exe" else "jpackage"
        val javaHome = System.getProperty("java.home")
        val jpackagePath = "$javaHome/bin/$jpackageExe"

        // 检查 jpackage 是否存在
        if (!File(jpackagePath).exists()) {
            throw GradleException("jpackage not found. Make sure you're using JDK 14+")
        }

        // 创建 D 盘临时目录（避免 C 盘空间不足）
        val tempDir = File("D:/temp/jpackage")
        // 如果目录存在，先删除（jpackage 要求空目录）
        if (tempDir.exists()) {
            println("清理旧的临时文件...")
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()

        // 构建 jpackage 命令（添加 JavaFX 支持）
        val command = mutableListOf(
            jpackagePath,
            "--type", "msi",
            "--name", "Xcup",
            "--app-version", version.toString(),
            "--vendor", "XINGSE",
            "--description", "Audio Video Analysis System",
            "--input", distDir.absolutePath,
            "--main-jar", "Xcup-${version}.jar",
            "--main-class", "org.example.Launcher",
            "--dest", outputDir.absolutePath,
            "--temp", tempDir.absolutePath,
            "--win-menu",
            "--win-shortcut",
            "--win-dir-chooser",
            "--win-menu-group", "XINGSE"
        )

        // 添加图标（如果存在）
        val iconFile = file("src/main/resources/icon.ico")
        if (iconFile.exists()) {
            command.addAll(listOf("--icon", iconFile.absolutePath))
        }

        // 添加 JavaFX 模块和 JVM 参数
        // 关键修复：指定 module-path 指向 lib 目录（jpackage打包后的结构中，依赖会被放在 app/lib 或 runtime/lib 下）
        // 但 jpackage 构建时需要指向构建时的路径。
        // 这里最稳妥的方式是：不使用模块化启动，而是将 JavaFX 视为普通 jar 在 classpath 中运行。
        // 或者，我们需要显式指定 JavaFX 的 jmods 路径（但这要求用户下载 jmods，太麻烦）。
        // 
        // 方案 B：移除 --add-modules 参数，让应用作为非模块化应用运行（依靠 Class-Path）。
        //         Main 类继承自 Application，这在非模块化运行时需要一个"Launcher"代理类来避免 JavaFX 检查。
        
        command.addAll(listOf(
            "--java-options", "-Xmx2g",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dsun.jnu.encoding=UTF-8",
            "--java-options", "-Dconsole.encoding=UTF-8",
            "--java-options", "-Dapp.version=${version}",
            "--java-options", "-Djava.library.path=\$APPDIR"
        ))

        println("Executing jpackage with WiX v3.14...")
        println("临时文件将使用: ${tempDir.absolutePath}")
        println(command.joinToString(" "))

        // 执行命令
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("jpackage failed with exit code $exitCode. Make sure WiX Toolset v3.14 is installed and in PATH.")
        }

        println("=".repeat(60))
        println("MSI 安装包创建成功！")
        println("位置: ${outputDir.absolutePath}")
        println("=".repeat(60))
        
        // 清理临时文件
        println("清理临时文件...")
        tempDir.deleteRecursively()
    }
}

// 清理任务
tasks.create<Delete>("cleanDist") {
    delete(layout.buildDirectory.dir("dist"))
    delete(layout.buildDirectory.dir("installer"))
    delete(layout.buildDirectory.dir("native-libs"))
}

// 设置编译和运行时编码
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    systemProperty("file.encoding", "UTF-8")
}

tasks.withType<JavaExec> {
    systemProperty("file.encoding", "UTF-8")
    systemProperty("console.encoding", "UTF-8")
}

tasks.test {
    useJUnitPlatform()
}