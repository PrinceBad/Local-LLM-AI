Write-Host "Starting Git Push and Tag Release for v2.5..." -ForegroundColor Cyan

# 1. Push to main branch
git push origin main
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to push to git repository 'main' branch."
    Exit 1
}

# 2. Tag the release
Write-Host "Creating git tag v2.5..." -ForegroundColor Green
git tag -a v2.5 -m "v2.5 Release: Removed Qualcomm dependencies and disabled NPU hardware checks in favor of direct Vulkan GPU inference" -f
git push origin v2.5 --force
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to push tag v2.5 to origin."
    Exit 1
}

Write-Host "Tag v2.5 successfully pushed to GitHub!" -ForegroundColor Green

# 3. Create GitHub Release using gh CLI if available
if (Get-Command gh -ErrorAction SilentlyContinue) {
    Write-Host "GitHub CLI (gh) detected. Publishing the release and uploading the release APK..." -ForegroundColor Cyan
    gh release create v2.5 app/build/outputs/apk/release/app-release.apk --title "v2.5 Release" --notes "v2.5 update bringing a cleaner build by removing Qualcomm QNN dependencies, disabling Hexagon NPU checking/initialization, and routing all local model loading directly to Vulkan GPU / CPU backend fallback." --latest
} else {
    Write-Host "GitHub CLI (gh) not detected. Please upload the compiled APK manually to the GitHub release page:" -ForegroundColor Yellow
    Write-Host "https://github.com/PrinceBad/Local-LLM-AI/releases" -ForegroundColor Yellow
}

Write-Host "Git Push and Release Setup completed successfully!" -ForegroundColor Green
