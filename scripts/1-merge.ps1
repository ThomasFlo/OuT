# =============================================================================
# 1-merge.ps1 - Merger la branche Claude dans la branche courante
# Usage : .\scripts\1-merge.ps1
# =============================================================================

$ErrorActionPreference = "Stop"

$REPO         = "C:\Users\thoma\OuT"
$CLAUDE_BRANCH = "claude/homestock-android-app-6zoXD"

Set-Location $REPO

$currentBranch = git rev-parse --abbrev-ref HEAD
Write-Host "`n[merge] Branche courante : $currentBranch"
Write-Host "[merge] Pull depuis origin/$CLAUDE_BRANCH ..."

git pull origin $CLAUDE_BRANCH

Write-Host "`n[merge] OK - commits recents :"
git log --oneline -5
