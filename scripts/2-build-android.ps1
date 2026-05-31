# =============================================================================
# 2-build-android.ps1 - Builder l'APK debug Android
# Usage : .\scripts\2-build-android.ps1
# =============================================================================

$ErrorActionPreference = "Stop"

$env:JAVA_HOME   = "C:\Program Files\Java\jdk-21"
$ANDROID_PROJECT = "C:\Users\thoma\OuT\homestock-android"
$APK_OUT         = "$ANDROID_PROJECT\app\build\outputs\apk\debug\app-debug.apk"

Set-Location $ANDROID_PROJECT

# Lire et incrementer versionCode dans build.gradle.kts
$gradleFile  = "app\build.gradle.kts"
$gradle      = Get-Content $gradleFile -Raw
$versionCode = [int][regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value

$newVersionCode = $versionCode + 1
$gradle = $gradle -replace "versionCode\s*=\s*$versionCode", "versionCode = $newVersionCode"
Set-Content $gradleFile $gradle -NoNewline

Write-Host "`n[android] versionCode $versionCode -> $newVersionCode (versionName=$versionName)"

.\gradlew.bat assembleDebug

if (-not (Test-Path $APK_OUT)) {
    Write-Error "[android] APK introuvable : $APK_OUT"
}

$sizeMB = [math]::Round((Get-Item $APK_OUT).Length / 1MB, 1)
Write-Host "`n[android] OK - $APK_OUT ($sizeMB MB)"
