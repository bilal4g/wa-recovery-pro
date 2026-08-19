# WA Recovery Pro — Android SDK Setup Script
# This script installs the Android SDK command-line tools and required packages

$ErrorActionPreference = "Stop"

$ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$CMDLINE_TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$CMDLINE_TOOLS_DIR = "$ANDROID_SDK_ROOT\cmdline-tools"

Write-Host "=== WA Recovery Pro — Android SDK Setup ===" -ForegroundColor Green
Write-Host ""

# Step 1: Create SDK directory
if (-not (Test-Path $ANDROID_SDK_ROOT)) {
    Write-Host "Creating Android SDK directory at $ANDROID_SDK_ROOT..."
    New-Item -ItemType Directory -Path $ANDROID_SDK_ROOT -Force | Out-Null
}

# Step 2: Download command-line tools
$zipPath = "$env:TEMP\android-cmdline-tools.zip"
if (-not (Test-Path "$CMDLINE_TOOLS_DIR\latest\bin\sdkmanager.bat")) {
    Write-Host "Downloading Android command-line tools..."
    Invoke-WebRequest -Uri $CMDLINE_TOOLS_URL -OutFile $zipPath -UseBasicParsing
    
    Write-Host "Extracting command-line tools..."
    $extractPath = "$env:TEMP\android-cmdline-extract"
    Expand-Archive -Path $zipPath -DestinationPath $extractPath -Force
    
    # Move to correct location
    New-Item -ItemType Directory -Path "$CMDLINE_TOOLS_DIR\latest" -Force | Out-Null
    Copy-Item -Path "$extractPath\cmdline-tools\*" -Destination "$CMDLINE_TOOLS_DIR\latest" -Recurse -Force
    
    # Cleanup
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $extractPath -Recurse -Force -ErrorAction SilentlyContinue
    
    Write-Host "Command-line tools installed!" -ForegroundColor Green
} else {
    Write-Host "Command-line tools already installed." -ForegroundColor Yellow
}

# Step 3: Set environment variables for current session
$env:ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT
$env:ANDROID_HOME = $ANDROID_SDK_ROOT
$env:PATH = "$CMDLINE_TOOLS_DIR\latest\bin;$ANDROID_SDK_ROOT\platform-tools;$env:PATH"

# Step 4: Accept licenses
Write-Host ""
Write-Host "Accepting SDK licenses..."
$yesInput = "y`ny`ny`ny`ny`ny`ny`ny`n"
$yesInput | & "$CMDLINE_TOOLS_DIR\latest\bin\sdkmanager.bat" --licenses 2>&1 | Out-Null

# Step 5: Install required SDK packages
Write-Host ""
Write-Host "Installing SDK packages (this may take a few minutes)..."

$packages = @(
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0"
)

foreach ($pkg in $packages) {
    Write-Host "  Installing $pkg..."
    & "$CMDLINE_TOOLS_DIR\latest\bin\sdkmanager.bat" --install $pkg 2>&1 | Out-Null
}

Write-Host ""
Write-Host "=== Android SDK setup complete! ===" -ForegroundColor Green
Write-Host "SDK Location: $ANDROID_SDK_ROOT"
Write-Host ""

# Set persistent user environment variables
[System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $ANDROID_SDK_ROOT, "User")
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $ANDROID_SDK_ROOT, "User")
Write-Host "Environment variables set (ANDROID_SDK_ROOT, ANDROID_HOME)" -ForegroundColor Cyan
