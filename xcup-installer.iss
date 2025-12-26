; Xcup 安装脚本 - Inno Setup 配置文件
; 使用方法: 右键点击此文件 -> "Compile"，或在 CMD 中运行:
; "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" xcup-installer.iss

[Setup]
; 应用基本信息
AppName=Xcup
AppVersion=1.0
AppPublisher=XINGSE
AppPublisherURL=https://github.com/yourusername/xcup
AppSupportURL=https://github.com/yourusername/xcup
AppUpdatesURL=https://github.com/yourusername/xcup

; 安装目录
DefaultDirName={autopf}\Xcup
DefaultGroupName=Xcup
DisableProgramGroupPage=yes

; 输出设置
OutputDir=build\installer
OutputBaseFilename=Xcup-Setup-1.0
SetupIconFile=src\main\resources\icon.ico
UninstallDisplayIcon={app}\icon.ico

; 压缩设置
Compression=lzma2
SolidCompression=yes

; 架构设置
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

; 权限要求
PrivilegesRequired=admin

; 许可和信息
;LicenseFile=LICENSE.txt
;InfoBeforeFile=README.txt

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; 复制所有文件（递归）
Source: "build\dist\Xcup\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; 开始菜单快捷方式
Name: "{group}\Xcup"; Filename: "{app}\Xcup.vbs"; IconFilename: "{app}\icon.ico"; Comment: "启动 Xcup"
Name: "{group}\Xcup (调试模式)"; Filename: "{app}\Xcup-debug.bat"; IconFilename: "{app}\icon.ico"; Comment: "以调试模式启动 Xcup"
Name: "{group}\卸载 Xcup"; Filename: "{uninstallexe}"

; 桌面快捷方式（如果用户选择）
Name: "{autodesktop}\Xcup"; Filename: "{app}\Xcup.vbs"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Run]
; 安装完成后询问是否启动
Filename: "{app}\Xcup.vbs"; Description: "立即启动 Xcup"; Flags: postinstall nowait skipifsilent

[UninstallDelete]
; 卸载时删除用户生成的文件（可选）
Type: filesandordirs; Name: "{app}\logs"
Type: filesandordirs; Name: "{app}\temp"

[Code]
// 检查 Java 是否安装（可选）
function InitializeSetup(): Boolean;
var
  JavaVersion: String;
  ResultCode: Integer;
begin
  Result := True;
  
  // 尝试运行 java -version
  if Exec('cmd.exe', '/c java -version 2>&1', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
  begin
    if ResultCode = 0 then
    begin
      Log('Java is installed');
    end
    else
    begin
      if MsgBox('警告: 未检测到 Java 运行环境 (JRE/JDK)。' + #13#10 + 
                'Xcup 需要 Java 17 或更高版本才能运行。' + #13#10#13#10 +
                '是否继续安装？', mbConfirmation, MB_YESNO) = IDNO then
      begin
        Result := False;
      end;
    end;
  end;
end;

