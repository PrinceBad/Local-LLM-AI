Write-Host "Starting Git Push and Tag Release for v1.1..." -ForegroundColor Cyan

# 1. Push to main branch
git push origin main
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to push to git repository 'main' branch."
    Exit 1
}

# 2. Tag the release
Write-Host "Creating git tag v1.1..." -ForegroundColor Green
git tag -a v1.1 -m "v1.1 Release: Voice Input, Gemma 4 native multimodal vision support, and newest LiteRT presets" -f
git push origin v1.1 --force
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to push tag v1.1 to origin."
    Exit 1
}

Write-Host "Tag v1.1 successfully pushed to GitHub!" -ForegroundColor Green

# 3. Create GitHub Release using gh CLI if available
if (Get-Command gh -ErrorAction SilentlyContinue) {
    Write-Host "GitHub CLI (gh) detected. Publishing the release and uploading the release APK..." -ForegroundColor Cyan
    gh release create v1.1 app/build/outputs/apk/release/app-release.apk --title "v1.1 Release" --notes "Phase 2 update bringing offline voice input (STT), Gemma 4 native multimodal vision routing, and newest LiteRT presets (Qwen3 0.6B, Gemma 4 E2B)." --latest
} else {
    Write-Host "GitHub CLI (gh) not detected. Please upload the compiled APK manually to the GitHub release page:" -ForegroundColor Yellow
    Write-Host "https://github.com/PrinceBad/Local-LLM-AI/releases" -ForegroundColor Yellow
}

Write-Host "Git Push and Release Setup completed successfully!" -ForegroundColor Green
