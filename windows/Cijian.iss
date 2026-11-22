#ifndef AppVersion
  #error AppVersion must be set by the packaging script
#endif
#ifndef AppPayload
  #error AppPayload must be set by the packaging script
#endif
#ifndef WebViewBootstrapper
  #error WebViewBootstrapper must be set by the packaging script
#endif

[Setup]
AppId=Cijian.Windows
AppName=词间
AppVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\Cijian
DefaultGroupName=词间
PrivilegesRequired=lowest
MinVersion=10.0
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableWelcomePage=yes
DisableDirPage=yes
DisableProgramGroupPage=yes
AllowNoIcons=yes
RestartApplications=no
UninstallDisplayIcon={app}\Cijian.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "zhcn"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Files]
Source: "{#AppPayload}\*"; DestDir: "{app}"; Excludes: "*.pdb"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#WebViewBootstrapper}"; Flags: dontcopy

[Icons]
Name: "{autoprograms}\词间"; Filename: "{app}\Cijian.exe"; WorkingDir: "{app}"; Check: not WizardNoIcons

[Run]
Filename: "{app}\Cijian.exe"; Description: "打开词间"; Flags: nowait postinstall skipifsilent

[Code]
const
  RuntimeKey = 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';
  Bootstrapper = 'MicrosoftEdgeWebview2Setup.exe';

function RuntimeVersionAvailable(Root: Integer): Boolean;
var
  Version: String;
begin
  Result := RegQueryStringValue(Root, RuntimeKey, 'pv', Version)
    and (Trim(Version) <> '') and (Trim(Version) <> '0.0.0.0');
end;

function HasRuntime(): Boolean;
begin
  Result := RuntimeVersionAvailable(HKLM32) or RuntimeVersionAvailable(HKLM64)
    or RuntimeVersionAvailable(HKCU32) or RuntimeVersionAvailable(HKCU64);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Status: Integer;
begin
  Result := '';
  if HasRuntime() then
    exit;

  WizardForm.PreparingLabel.Caption := '正在从 Microsoft 安装 WebView2…';
  ExtractTemporaryFile(Bootstrapper);
  if not Exec(ExpandConstant('{tmp}\' + Bootstrapper), '/silent /install', '',
    SW_HIDE, ewWaitUntilTerminated, Status) or (Status <> 0) or not HasRuntime() then
    Result := 'WebView2 尚未安装。请连接网络后重试，或先安装 Microsoft Edge WebView2 Runtime。';
end;
