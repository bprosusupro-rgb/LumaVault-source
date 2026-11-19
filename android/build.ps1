$ErrorActionPreference = 'Stop'
$shortTemp = Join-Path $env:SystemRoot 'Temp'
if (Test-Path $shortTemp) { $env:TEMP = $shortTemp; $env:TMP = $shortTemp }
$javaCandidates = @('C:\Program Files\Java\jdk-17', 'C:\Program Files\Java\jdk-21', 'C:\Program Files\Java\jdk-22')
$javaHome = $javaCandidates | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
if ($javaHome) { $env:JAVA_HOME = $javaHome; $env:Path = "$javaHome\bin;" + $env:Path }
$root = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $root
$volumeApkRoot = Join-Path (Split-Path -Qualifier $PSScriptRoot) 'apk-vr'
$assets = Join-Path $PSScriptRoot 'app\src\main\assets\www'
New-Item -ItemType Directory -Force $assets | Out-Null
Copy-Item (Join-Path $root 'gallery\index.html') (Join-Path $assets 'index.html') -Force
$sdkCandidates = @(
    (Join-Path $root '.android-sdk'),
    (Join-Path $workspaceRoot '.android-sdk'),
    (Join-Path $workspaceRoot '.tools\android-sdk'),
    (Join-Path $workspaceRoot 'apk-forge\.tools\android-sdk'),
    (Join-Path $volumeApkRoot 'android-sdk')
)
$sdk = $sdkCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
$gradleCandidates = @(
    (Join-Path $workspaceRoot '.tools\gradle\gradle-8.10.2\bin\gradle.bat'),
    (Join-Path $workspaceRoot 'apk-forge\.tools\gradle\gradle-8.10.2\bin\gradle.bat'),
    (Join-Path $volumeApkRoot 'gradle-8.10.2\bin\gradle.bat')
)
$gradle = $gradleCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (Test-Path $sdk) { $env:ANDROID_HOME = (Resolve-Path $sdk).Path; $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME; "sdk.dir=$($env:ANDROID_HOME.Replace('\','/'))" | Set-Content (Join-Path $PSScriptRoot 'local.properties') }
Push-Location $PSScriptRoot
try {
    if (Test-Path (Join-Path $PSScriptRoot 'gradlew.bat')) { & (Join-Path $PSScriptRoot 'gradlew.bat') --no-daemon assembleDebug }
    elseif (Test-Path $gradle) { & $gradle --no-daemon assembleDebug }
    else { throw 'Gradle was not found. Install the local Android toolchain with apk-forge/setup_android_tools.ps1, then run this script again.' }
} finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
$output = Join-Path $volumeApkRoot 'LumaVault-debug.apk'
if (Test-Path $apk) { Copy-Item $apk $output -Force; Write-Host "Finished APK: $output" }
