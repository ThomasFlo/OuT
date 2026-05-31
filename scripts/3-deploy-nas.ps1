# =============================================================================
# 3-deploy-nas.ps1 - Deployer backend + APK sur le NAS et rebuilder homestock-api
# Usage : .\scripts\3-deploy-nas.ps1
# =============================================================================

$ErrorActionPreference = "Stop"

# ── Config ────────────────────────────────────────────────────────────────────
$NAS_HOST        = "tomflo@192.168.1.3"
$NAS_PORT        = "9222"
$NAS_BACKEND_DIR = "~/homestock-backend"
$DOCKER_COMPOSE  = "/Volume2/@apps/DockerEngine/dockerd/bin/docker-compose"

$REPO            = "C:\Users\thoma\OuT"
$ANDROID_PROJECT = "$REPO\homestock-android"
$APK_SRC         = "$ANDROID_PROJECT\app\build\outputs\apk\debug\app-debug.apk"
$TAR_TMP         = "$env:TEMP\homestock-backend.tar.gz"
# ─────────────────────────────────────────────────────────────────────────────

# 1. Lire versionCode / versionName depuis build.gradle.kts
$gradle      = Get-Content "$ANDROID_PROJECT\app\build.gradle.kts" -Raw
$versionCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
Write-Host "`n[deploy] versionCode=$versionCode  versionName=$versionName"

# 2. Verifier que l'APK existe
if (-not (Test-Path $APK_SRC)) {
    Write-Error "[deploy] APK introuvable - lance d'abord 2-build-android.ps1"
}

# 3. Archiver le backend (sans .env ni __pycache__)
Write-Host "[deploy] Archivage du backend ..."
tar -czf $TAR_TMP `
    --exclude='homestock-backend/.env' `
    --exclude='homestock-backend/**/__pycache__' `
    --exclude='homestock-backend/apk/*.apk' `
    -C $REPO homestock-backend

# 4. Envoyer l'archive sur le NAS et extraire
Write-Host "[deploy] Transfert du backend ..."
scp -O -P $NAS_PORT $TAR_TMP "${NAS_HOST}:/tmp/homestock-backend.tar.gz"
ssh -p $NAS_PORT $NAS_HOST "tar -xzf /tmp/homestock-backend.tar.gz -C ~/ && rm /tmp/homestock-backend.tar.gz"

# 5. Envoyer l'APK
Write-Host "[deploy] Transfert de l'APK ..."
ssh -p $NAS_PORT $NAS_HOST "mkdir -p ${NAS_BACKEND_DIR}/apk"
scp -O -P $NAS_PORT $APK_SRC "${NAS_HOST}:${NAS_BACKEND_DIR}/apk/homestock-latest.apk"

# 6. Créer version.json (via base64 pour éviter les problèmes de quoting SSH)
Write-Host "[deploy] Mise à jour de version.json ..."
$json    = "{`"version_code`": $versionCode, `"version_name`": `"$versionName`", `"notes`": `"versionCode=$versionCode`"}"
$b64     = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
ssh -p $NAS_PORT $NAS_HOST "echo $b64 | base64 -d > ${NAS_BACKEND_DIR}/apk/version.json && cat ${NAS_BACKEND_DIR}/apk/version.json"

# 7. Rebuilder homestock-api
Write-Host "`n[deploy] Rebuild homestock-api ..."
ssh -p $NAS_PORT $NAS_HOST "$DOCKER_COMPOSE -f ${NAS_BACKEND_DIR}/docker-compose.yml up -d --build homestock-api"

# 8. Nettoyage local
Remove-Item $TAR_TMP -Force

Write-Host "`n[deploy] Termine - versionCode=$versionCode deploye sur $NAS_HOST"
