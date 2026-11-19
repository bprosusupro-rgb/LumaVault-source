$ErrorActionPreference = 'Stop'
$shortTemp = Join-Path $env:SystemRoot 'Temp'
if (Test-Path $shortTemp) { $env:TEMP = $shortTemp; $env:TMP = $shortTemp }
$javaCandidates = @('C:\Program Files\Java\jdk-17', 'C:\Program Files\Java\jdk-21', 'C:\Program Files\Java\jdk-22')
$javaHome = $javaCandidates | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
if ($javaHome) { $env:JAVA_HOME = $javaHome; $env:Path = "$javaHome\bin;" + $env:Path }
$root = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $root
$volumeApkRoot = Join-Path (Split-Path -Qualifier $PSScriptRoot) 'apk-vr'
$sdkCandidates = @(
    (Join-Path $root '.android-sdk'),
    (Join-Path $workspaceRoot '.android-sdk'),
    (Join-Path $workspaceRoot '.tools\android-sdk'),
    (Join-Path $workspaceRoot 'apk-forge\.tools\android-sdk'),
    (Join-Path $volumeApkRoot 'android-sdk')
)
$sdk = $sdkCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($sdk) { $env:ANDROID_HOME = (Resolve-Path $sdk).Path }
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'gradlew.bat') --no-daemon assembleDebug
} finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
$output = Join-Path $volumeApkRoot 'LumaVault-debug.apk'
if (Test-Path $apk) { Copy-Item $apk $output -Force; Write-Host "Finished APK: $output" }
