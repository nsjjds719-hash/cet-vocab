param(
    [string]$SdkRoot,
    [string]$BuildToolsVersion = '36.0.0'
)

$ErrorActionPreference = 'Stop'
if (-not $SdkRoot) {
    $SdkRoot = $env:ANDROID_HOME
}
if (-not $SdkRoot) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

$root = $PSScriptRoot
$sdkTools = Join-Path $SdkRoot "build-tools\$BuildToolsVersion"
$androidJar = Join-Path $SdkRoot 'platforms\android-36\android.jar'
$aapt = Join-Path $sdkTools 'aapt2.exe'
$d8 = Join-Path $sdkTools 'd8.bat'
$align = Join-Path $sdkTools 'zipalign.exe'
$sign = Join-Path $sdkTools 'apksigner.bat'
$main = Join-Path $root 'app\src\main'
$manifest = Join-Path $main 'AndroidManifest.xml'
[xml]$appManifest = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8
$version = $appManifest.DocumentElement.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$build = Join-Path $root 'build'
$apk = Join-Path $build "cijian-$version.apk"
$work = Join-Path $build ('apk-' + [guid]::NewGuid().ToString('N'))
$keystore = Join-Path $build 'local-debug.jks'
foreach ($required in @($androidJar, $aapt, $d8, $align, $sign)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing Android SDK component: $required"
    }
}

function Invoke-BuildTool {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed (exit $LASTEXITCODE)."
    }
}

try {
    foreach ($folder in @('generated', 'classes', 'dex', 'assets')) {
        New-Item -ItemType Directory -Path (Join-Path $work $folder) -Force | Out-Null
    }
    $compiled = Join-Path $work 'resources.zip'
    $resources = Join-Path $work 'resources.apk'
    $classes = Join-Path $work 'classes.jar'
    $aligned = Join-Path $work 'aligned.apk'
    $signed = Join-Path $work 'signed.apk'

    $assets = Join-Path $work 'assets'
    foreach ($asset in @('catalog.json', 'dictionary.json', 'ECDICT-LICENSE.txt')) {
        Copy-Item -LiteralPath (Join-Path $root "data\study\$asset") -Destination $assets
    }
    Invoke-BuildTool $aapt @('compile', '--dir', (Join-Path $main 'res'), '-o', $compiled)
    Invoke-BuildTool $aapt @('link', '-o', $resources, '-I', $androidJar,
        '--manifest', $manifest, '--java', (Join-Path $work 'generated'),
        '-A', $assets, $compiled)

    $sourceFolders = @((Join-Path $main 'java'), (Join-Path $work 'generated'))
    $sources = @(Get-ChildItem -LiteralPath $sourceFolders -Filter '*.java' -File -Recurse |
        ForEach-Object FullName)
    Invoke-BuildTool 'javac.exe' (@('--release', '8', '-encoding', 'UTF-8',
        '-classpath', $androidJar, '-d', (Join-Path $work 'classes')) + $sources)
    Invoke-BuildTool 'jar.exe' @('cf', $classes, '-C', (Join-Path $work 'classes'), '.')
    Invoke-BuildTool $d8 @('--release', '--min-api', '26', '--lib', $androidJar,
        '--output', (Join-Path $work 'dex'), $classes)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open($resources, 'Update')
    try {
        foreach ($file in Get-ChildItem -LiteralPath (Join-Path $work 'dex') -Filter 'classes*.dex' -File) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $file.FullName, $file.Name) | Out-Null
        }
    } finally {
        $archive.Dispose()
    }

    Invoke-BuildTool $align @('-f', '4', $resources, $aligned)
    if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
        Invoke-BuildTool 'keytool.exe' @('-genkeypair', '-keystore', $keystore,
            '-alias', 'androiddebugkey', '-storepass', 'android', '-keypass', 'android',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
            '-dname', 'CN=Android Debug,O=Android,C=US')
    }
    Invoke-BuildTool $sign @('sign', '--ks', $keystore, '--ks-key-alias', 'androiddebugkey',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--v4-signing-enabled', 'false',
        '--out', $signed, $aligned)
    Invoke-BuildTool $sign @('verify', '--verbose', $signed)
    Move-Item -LiteralPath $signed -Destination $apk -Force
    Write-Output $apk
} finally {
    if (Test-Path -LiteralPath $work) {
        $resolvedWork = (Resolve-Path -LiteralPath $work).Path
        $resolvedBuild = (Resolve-Path -LiteralPath $build).Path
        if ([System.IO.Path]::GetDirectoryName($resolvedWork) -eq $resolvedBuild -and
                [System.IO.Path]::GetFileName($resolvedWork) -match '^apk-[0-9a-f]{32}$') {
            Remove-Item -LiteralPath $resolvedWork -Recurse -Force
        }
    }
}
