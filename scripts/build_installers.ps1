param(
    [Parameter(Mandatory)][string]$Compiler,
    [Parameter(Mandatory)][string]$WebViewBootstrapper,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '0.1.8'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root 'build'
$payload = Join-Path $build "cijian-windows-x64-$Version"
$android = Join-Path $build "cijian-$Version.apk"
$definition = Join-Path $root 'windows\Cijian.iss'
$windowsName = "Cijian-Windows-x64-$Version-Setup"
$windows = Join-Path $build "$windowsName.exe"
$bundle = Join-Path $build "Cijian-Android-Windows-$Version.zip"

foreach ($file in @($Compiler, $WebViewBootstrapper, $android,
        (Join-Path $payload 'Cijian.exe'), (Join-Path $payload 'web\index.html'))) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Missing packaging input: $file"
    }
}
$bootstrapper = Get-Item -LiteralPath $WebViewBootstrapper
if ($bootstrapper.Name -cne 'MicrosoftEdgeWebview2Setup.exe') {
    throw 'Expected the MicrosoftEdgeWebview2Setup.exe download.'
}
$signature = Get-AuthenticodeSignature -LiteralPath $bootstrapper.FullName
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') {
    throw 'WebView2 bootstrapper signature verification failed.'
}

$compileOptions = @("/DAppVersion=$Version", "/DAppPayload=$payload",
    "/DWebViewBootstrapper=$($bootstrapper.FullName)", "/O$build", "/F$windowsName", $definition)
& $Compiler @compileOptions
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $windows -PathType Leaf)) {
    throw 'Windows setup compilation failed.'
}

$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('cijian-installer-' + [guid]::NewGuid().ToString('N'))
New-Item -Path $scratch -ItemType Directory | Out-Null
$instructions = Join-Path $scratch 'README.txt'
try {
    @"
词间 $Version · Android 和 Windows

Android 8.0 及以上
将 cijian-$Version.apk 复制到手机，用文件管理打开并安装。更新已有版本时直接覆盖安装。

Windows x64
打开 $windowsName.exe，点击安装。安装后从开始菜单打开「词间」。
已经使用便携版的用户可以继续使用同一个本机学习进度。
只有 Windows 缺少 WebView2 时，安装程序会联网下载该系统组件。词典与练习在本机运行。
"@ | Set-Content -LiteralPath $instructions -Encoding UTF8
    $archiveOptions = @{LiteralPath = @($android, $windows, $instructions); DestinationPath = $bundle;
        CompressionLevel = 'Fastest'; Force = $true}
    Compress-Archive @archiveOptions
} finally {
    if (Test-Path -LiteralPath $instructions -PathType Leaf) {
        Remove-Item -LiteralPath $instructions
    }
    Remove-Item -LiteralPath $scratch
}
Write-Output $windows
Write-Output $bundle
