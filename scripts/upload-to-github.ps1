$ErrorActionPreference = 'Continue'

$PROJECT_ROOT = 'c:\Users\bilal\OneDrive\Desktop\whatsapp app'
$REPO_URL = 'https://github.com/bilal4g/wa-recovery-pro.git'
$BRANCH = 'main'

Write-Host '==========================================' -ForegroundColor Green
Write-Host '   WA Recovery Pro -- GitHub Uploader' -ForegroundColor Green
Write-Host '==========================================' -ForegroundColor Green
Write-Host ''

Push-Location $PROJECT_ROOT

# Step 1: Check for Git executable
$gitPath = 'C:\Program Files\Git\cmd\git.exe'
if (Test-Path $gitPath) {
    $env:PATH = 'C:\Program Files\Git\cmd;' + $env:PATH
}

$gitCmd = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitCmd) {
    Write-Host 'Git is not installed yet. Installing Git...' -ForegroundColor Yellow
    winget install --id Git.Git -e --source winget --accept-source-agreements --accept-package-agreements --silent
    if (Test-Path $gitPath) {
        $env:PATH = 'C:\Program Files\Git\cmd;' + $env:PATH
    }
}

# Step 2: Initialize Git
if (-not (Test-Path (Join-Path $PROJECT_ROOT '.git'))) {
    Write-Host 'Step 1: Initializing Git repository...' -ForegroundColor Cyan
    git init
    git branch -M $BRANCH
} else {
    Write-Host 'Step 1: Git repository ready.' -ForegroundColor Cyan
}

# Step 3: Stage all files
Write-Host 'Step 2: Staging files...' -ForegroundColor Cyan
git add .

# Step 4: Commit
Write-Host 'Step 3: Creating commit...' -ForegroundColor Cyan
git commit -m 'feat: WA Recovery Pro Android app with Voice Suite, Permission Wizard, and Auto-Updater'

# Step 5: Configure Remote
Write-Host 'Step 4: Setting remote repository to:' $REPO_URL -ForegroundColor Cyan
$existingRemote = git remote get-url origin 2>$null
if ($existingRemote) {
    git remote set-url origin $REPO_URL
} else {
    git remote add origin $REPO_URL
}

# Step 6: Push to GitHub
Write-Host 'Step 5: Pushing to GitHub (main branch)...' -ForegroundColor Cyan
git push -u origin $BRANCH

if ($LASTEXITCODE -eq 0) {
    Write-Host ''
    Write-Host '==========================================' -ForegroundColor Green
    Write-Host 'UPLOAD SUCCESSFUL!' -ForegroundColor Green
    Write-Host 'Repository: https://github.com/bilal4g/wa-recovery-pro' -ForegroundColor Yellow
    Write-Host '==========================================' -ForegroundColor Green
} else {
    Write-Host ''
    Write-Host 'Push completed or waiting for GitHub repository creation.' -ForegroundColor Yellow
    Write-Host 'If you have not created the repo yet, create it at: https://github.com/new (Name: wa-recovery-pro)' -ForegroundColor Cyan
}

Pop-Location
