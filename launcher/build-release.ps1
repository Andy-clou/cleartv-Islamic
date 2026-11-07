# Build a release APK using this checkout's local JDK/SDK tools.
# Run from an ordinary Windows PowerShell window, outside a restricted agent shell.
$ErrorActionPreference = 'Stop'

$toolsRoot = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Codex\.codex-build-tools'

$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$jdkCandidates += Join-Path $toolsRoot 'jdk-17'
$jdkCandidates += 'C:\Program Files\Android\Android Studio\jbr'
$javaHomePath = $null
foreach ($candidate in ($jdkCandidates | Select-Object -Unique)) {
    $javaExe = Join-Path $candidate 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExe)) { continue }
    $previousErrorAction = $ErrorActionPreference
    try {
        # java -version writes to stderr; with Stop that becomes a terminating PowerShell error.
        $ErrorActionPreference = 'Continue'
        $javaVersion = (& $javaExe -version 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($javaVersion -match '(?m)(?:openjdk|java) version "17\.') {
        $javaHomePath = $candidate
        break
    }
}
if (-not $javaHomePath) {
    throw "JDK 17 tidak ditemukan. Pasang di '$toolsRoot\jdk-17' atau set JAVA_HOME ke JDK 17."
}

$sdkCandidates = @()
if ($env:ANDROID_HOME) { $sdkCandidates += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $sdkCandidates += $env:ANDROID_SDK_ROOT }
$sdkCandidates += Join-Path $toolsRoot 'android-sdk'
$sdkCandidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$sdkPath = $null
foreach ($candidate in ($sdkCandidates | Select-Object -Unique)) {
    if ((Test-Path -LiteralPath (Join-Path $candidate 'platforms\android-34\android.jar')) -and
        (Test-Path -LiteralPath (Join-Path $candidate 'build-tools\34.0.0\aapt.exe'))) {
        $sdkPath = $candidate
        break
    }
}
if (-not $sdkPath) {
    throw "Android SDK Platform 34 dan Build Tools 34.0.0 tidak ditemukan. Periksa '$toolsRoot\android-sdk'."
}

$localRoot = Join-Path $PSScriptRoot '.build-local'
$androidUserHome = Join-Path $localRoot 'android-user'
$gradleUserHome = Join-Path $localRoot 'gradle-home'
New-Item -ItemType Directory -Force -Path $androidUserHome, $gradleUserHome | Out-Null

$env:JAVA_HOME = $javaHomePath
$env:PATH = "$javaHomePath\bin;$env:PATH"
$env:ANDROID_HOME = $sdkPath
$env:ANDROID_SDK_ROOT = $sdkPath
$env:ANDROID_USER_HOME = $androidUserHome
$env:GRADLE_USER_HOME = $gradleUserHome
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$androidUserHome"

Write-Host "JDK: $javaHomePath"
Write-Host "Android SDK: $sdkPath"
Write-Host 'Building release APK...'
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat assembleRelease --no-daemon --console plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
    $apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw 'Gradle completed without producing the release APK.' }
    $item = Get-Item -LiteralPath $apk
    Write-Host "Built $($item.FullName) ($($item.Length) bytes)"
} finally {
    Pop-Location
}
