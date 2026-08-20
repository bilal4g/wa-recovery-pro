$ErrorActionPreference = "Continue"

$PROJECT_ROOT = "c:\Users\bilal\OneDrive\Desktop\whatsapp app"
$ANDROID_DIR = "c:\Users\bilal\OneDrive\Desktop\whatsapp app\android"
$APK_OUTPUT = "c:\Users\bilal\OneDrive\Desktop\whatsapp app\android\app\build\outputs\apk\debug\app-debug.apk"
$FINAL_APK = "c:\Users\bilal\OneDrive\Desktop\whatsapp app\WA-Recovery-Pro.apk"

Write-Host '==========================================' -ForegroundColor Green
Write-Host '   WA Recovery Pro -- APK Builder' -ForegroundColor Green
Write-Host '==========================================' -ForegroundColor Green

$jdkFound = Get-ChildItem "$env:ProgramFiles\Microsoft\jdk-17*" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdkFound) {
    $env:JAVA_HOME = $jdkFound.FullName
    $env:PATH = $jdkFound.FullName + '\bin;' + $env:PATH
    Write-Host 'Using JDK 17:' $jdkFound.FullName -ForegroundColor Cyan
}

$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT

# Step 0: Auto-Sync Version from version.json (Single Source of Truth)
if (Test-Path "$PROJECT_ROOT\version.json") {
    $verJson = Get-Content "$PROJECT_ROOT\version.json" -Raw | ConvertFrom-Json
    $vName = $verJson.version
    $vCode = $verJson.build
    Write-Host "Syncing Version: $vName (Build $vCode) from version.json..." -ForegroundColor Cyan

    $gradlePath = "$PROJECT_ROOT\android\app\build.gradle"
    if (Test-Path $gradlePath) {
        $gText = Get-Content $gradlePath -Raw
        $gText = $gText -replace 'versionCode \d+', "versionCode $vCode"
        $gText = $gText -replace 'versionName "[^"]+"', "versionName `"$vName`""
        Set-Content -Path $gradlePath -Value $gText -NoNewline
    }
}

Write-Host 'Step 1: Building web assets with Vite...' -ForegroundColor Cyan
Push-Location $PROJECT_ROOT
npm run build
Pop-Location

Write-Host 'Step 2: Syncing assets to Capacitor Android...' -ForegroundColor Cyan
Push-Location $PROJECT_ROOT
npx cap sync android
Pop-Location

Write-Host 'Step 3: Building Android APK with Gradle...' -ForegroundColor Cyan
Push-Location $ANDROID_DIR
.\gradlew.bat assembleDebug
Pop-Location

if (Test-Path $APK_OUTPUT) {
    Copy-Item -Path $APK_OUTPUT -Destination $FINAL_APK -Force
    $size = [math]::Round((Get-Item $FINAL_APK).Length / 1MB, 2)
    Write-Host '==========================================' -ForegroundColor Green
    Write-Host 'BUILD SUCCESSFUL!' -ForegroundColor Green
    Write-Host 'APK Location:' $FINAL_APK -ForegroundColor Yellow
    Write-Host 'APK Size:' $size 'MB' -ForegroundColor Yellow
    Write-Host '==========================================' -ForegroundColor Green
}
