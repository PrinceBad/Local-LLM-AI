<#
.SYNOPSIS
    Aura Local AI - Physical Device Silicon, RAM & Acceleration Diagnostics.

.DESCRIPTION
    Inspects a connected physical Android device over ADB to verify hardware readiness
    for on-device LLM inference (LiteRT-LM, Vulkan, Qualcomm QNN Hexagon NPU).
    Checks CPU ABI (arm64-v8a), total RAM against ModelSafetyValidator tier thresholds,
    Vulkan GPU extensions, and Qualcomm Hexagon DSP runtime libraries.

.PARAMETER DryRun
    Runs a simulated diagnostic verification without requiring an active ADB device.

.PARAMETER Install
    Installs app/build/outputs/apk/debug/app-debug.apk to the device after diagnostics.

.PARAMETER FollowLogs
    Streams logcat filtered for Aura Local AI inference, memory guards, and security events.

.PARAMETER DeviceId
    Specific ADB device serial if multiple devices are attached.
#>

param (
    [switch]$DryRun,
    [switch]$Install,
    [switch]$FollowLogs,
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Continue"

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "       Aura Local AI: Physical Hardware Diagnostic Suite         " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# Locate ADB
$adbCmd = "adb"
if (-not (Get-Command "adb" -ErrorAction SilentlyContinue)) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path -LiteralPath $sdkAdb) {
        $adbCmd = $sdkAdb
    } elseif (-not $DryRun) {
        Write-Error "ADB not found in PATH or Android SDK location. Install Android platform-tools or run with -DryRun."
        exit 1
    }
}

if ($DryRun) {
    Write-Host "`n[MODE] Running Simulated Hardware Validation (-DryRun)`n" -ForegroundColor Yellow
    
    $model = "Samsung Galaxy S24 Ultra (SM-S928B) [Simulated]"
    $abi = "arm64-v8a"
    $totalKb = 12150000
    $availKb = 7800000
    $vulkanSupported = $true
    $qnnFound = $true
} else {
    $adbArgs = if ($DeviceId) { @("-s", $DeviceId) } else { @() }
    
    # Check attached devices
    $devices = & $adbCmd @adbArgs devices | Select-String -Pattern "\sdevice$"
    if (-not $devices) {
        Write-Warning "No active ADB device detected. Connect a physical phone with USB debugging enabled, or test with -DryRun."
        exit 1
    }
    
    Write-Host "`n[ADB] Probing connected target device..." -ForegroundColor Green
    $model = (& $adbCmd @adbArgs shell getprop ro.product.model).Trim()
    $abi = (& $adbCmd @adbArgs shell getprop ro.product.cpu.abi).Trim()
    
    # Extract MemTotal & MemAvailable from /proc/meminfo
    $meminfo = & $adbCmd @adbArgs shell cat /proc/meminfo
    $totalKb = ($meminfo | Select-String "MemTotal" | ForEach-Object { ($_ -split "\s+")[1] }) -as [long]
    $availKb = ($meminfo | Select-String "MemAvailable" | ForEach-Object { ($_ -split "\s+")[1] }) -as [long]
    
    # Check Vulkan feature
    $vulkanCheck = & $adbCmd @adbArgs shell pm list features | Select-String "feature:android.hardware.vulkan"
    $vulkanSupported = [bool]$vulkanCheck
    
    # Check Qualcomm QNN libraries
    $qnnCheck = & $adbCmd @adbArgs shell "ls /vendor/lib64/libQnn* /system/lib64/libQnn* 2>/dev/null"
    $qnnFound = [bool]($qnnCheck | Select-String "libQnnHtp.so")
}

$totalGiB = [math]::Round($totalKb / (1024 * 1024), 2)
$availGiB = [math]::Round($availKb / (1024 * 1024), 2)

Write-Host "Device Model        : $model" -ForegroundColor White
Write-Host "CPU Architecture    : $abi" -NoNewline
if ($abi -eq "arm64-v8a") {
    Write-Host " [SUPPORTED (arm64-v8a)]" -ForegroundColor Green
} else {
    Write-Host " [UNSUPPORTED (Requires 64-bit ARM)]" -ForegroundColor Red
}

Write-Host "Total Physical RAM  : $totalGiB GiB visible" -ForegroundColor White
Write-Host "Current Free RAM    : $availGiB GiB available" -ForegroundColor White
Write-Host "  [NOTE] MemAvailable via ADB represents idle headroom. Real-world OEM skins" -ForegroundColor DarkGray
Write-Host "         and background apps reduce available RAM by ~1.0-2.0 GiB during active usage." -ForegroundColor DarkGray

# Compare against ModelSafetyValidator Tier Thresholds
$tierFloor4Gb  = 3.22 # (4 * 1024^3 - 800 MiB)
$tierFloor6Gb  = 5.22 # (6 * 1024^3 - 800 MiB)
$tierFloor8Gb  = 7.22 # (8 * 1024^3 - 800 MiB)
$tierFloor12Gb = 11.22 # (12 * 1024^3 - 800 MiB)

Write-Host "`nHardware RAM Tier Classification:" -ForegroundColor Yellow
if ($totalGiB -ge $tierFloor12Gb) {
    Write-Host "  -> Tier: 12 GB+ (Tier 3) - Flagship headroom. Supports all models including Gemma 4 E4B (3.4 GB) with Multimodal Vision." -ForegroundColor Green
} elseif ($totalGiB -ge $tierFloor8Gb) {
    Write-Host "  -> Tier: 8 GB (Tier 2) - Supports Qwen 3 4B (2.5 GB), Coder 3B (2.9 GB), Gemma 4 E2B (2.4 GB)." -ForegroundColor Green
    Write-Host "     Note: Gemma 4 E4B requires 12 GB+ RAM for safe multimodal execution." -ForegroundColor DarkGray
} elseif ($totalGiB -ge $tierFloor6Gb) {
    Write-Host "  -> Tier: 6 GB (Tier 1) - Supports DeepSeek-R1 1.5B (2.0 GB), Qwen 2.5 1.5B (1.8 GB)." -ForegroundColor Green
    Write-Host "     Note: Models requiring 8 GB+ or 12 GB+ are safely locked out." -ForegroundColor DarkGray
} elseif ($totalGiB -ge $tierFloor4Gb) {
    Write-Host "  -> Tier: 4 GB - Constrained memory. Suitable for sub-2B models with 4-turn minimal context." -ForegroundColor Yellow
} else {
    Write-Host "  -> Tier: <4 GB - Insufficient RAM for safe on-device LLM inference." -ForegroundColor Red
}

Write-Host "`nHardware Acceleration Checks:" -ForegroundColor Yellow
Write-Host "  Vulkan GPU Engine : " -NoNewline
if ($vulkanSupported) {
    Write-Host "[DETECTED / VULKAN ACCELERATION READY]" -ForegroundColor Green
} else {
    Write-Host "[NOT FOUND / CPU FALLBACK ONLY]" -ForegroundColor Yellow
}

Write-Host "  Qualcomm QNN HTP  : " -NoNewline
if ($qnnFound) {
    Write-Host "[FOUND (Hexagon Tensor Processor Runtime Available)]" -ForegroundColor Green
} else {
    Write-Host "[NOT DETECTED (GPU/CPU Execution Path)]" -ForegroundColor Gray
}

# Optional Installation
if ($Install) {
    $apkPath = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
    if (Test-Path $apkPath) {
        Write-Host "`n[INSTALL] Deploying app-debug.apk..." -ForegroundColor Cyan
        & $adbCmd @adbArgs install -r $apkPath
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[SUCCESS] Installed successfully! Launching Aura Local AI..." -ForegroundColor Green
            & $adbCmd @adbArgs shell am start -n "com.example.auralocalai/com.example.auralocalai.MainActivity"
        } else {
            Write-Error "Installation failed with exit code $LASTEXITCODE"
        }
    } else {
        Write-Warning "APK not found at $apkPath. Run './gradlew assembleDebug' first."
    }
}

# Optional Log Following
if ($FollowLogs) {
    Write-Host "`n[LOGCAT] Streaming filtered logs (Ctrl+C to exit)..." -ForegroundColor Cyan
    & $adbCmd @adbArgs logcat -v time -s "LlmInferenceEngine:V" "ModelSafetyValidator:V" "ModelDownloader:V" "TokenStorage:V" "LlmViewModel:V"
}

Write-Host "`nDiagnostics Complete." -ForegroundColor Cyan
