# =============================================================================
# release.ps1 - Pipeline complet : merge -> build -> deploy
# Usage : .\scripts\release.ps1
# =============================================================================

$ErrorActionPreference = "Stop"
$SCRIPTS = "$PSScriptRoot"

Write-Host "============================================================"
Write-Host " RELEASE PIPELINE"
Write-Host "============================================================"

& "$SCRIPTS\1-merge.ps1"
Write-Host ""

& "$SCRIPTS\2-build-android.ps1"
Write-Host ""

& "$SCRIPTS\3-deploy-nas.ps1"
Write-Host ""

Write-Host "============================================================"
Write-Host " RELEASE COMPLETE"
Write-Host "============================================================"
