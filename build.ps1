# Build and install wlantron (wifi-login, lan-login) for Windows.
#
# From a checkout:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
# Curl build command (cmd or PowerShell):
#   curl -fsSL -o %TEMP%\wlantron-build.ps1 https://raw.githubusercontent.com/Arcioth/wlantron/main/build.ps1 && powershell -NoProfile -ExecutionPolicy Bypass -File %TEMP%\wlantron-build.ps1
#
# WLANTRON_PREFIX  install directory (default: %LOCALAPPDATA%\wlantron)
# WLANTRON_REF     git branch when downloading (default: main)
$ErrorActionPreference = 'Stop'

$Repo = 'https://github.com/Arcioth/wlantron'
$Ref = if ($env:WLANTRON_REF) { $env:WLANTRON_REF } else { 'main' }
$DestRoot = if ($env:WLANTRON_PREFIX) { $env:WLANTRON_PREFIX } else { Join-Path $env:LOCALAPPDATA 'wlantron' }

function Test-PythonVersion {
    param([Parameter(Mandatory)][string[]]$Command)
    $code = 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)'
    if ($Command.Length -gt 1) {
        & $Command[0] @($Command[1..($Command.Length - 1)]) -c $code
    } else {
        & $Command[0] -c $code
    }
    return $LASTEXITCODE -eq 0
}

function Find-Python {
    # The leading comma stops PowerShell from unwrapping the array on return.
    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($py -and (Test-PythonVersion -Command @('py', '-3'))) {
        return ,@('py', '-3')
    }
    foreach ($name in @('python', 'python3')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd -and (Test-PythonVersion -Command @($cmd.Source))) {
            return ,@($cmd.Source)
        }
    }
    throw 'Python 3.10+ was not found. Install it from https://www.python.org/downloads/ and tick the py launcher.'
}

function Invoke-Python {
    param([Parameter(Mandatory)][string[]]$Command, [Parameter(Mandatory)][string[]]$Arguments)
    if ($Command.Length -gt 1) {
        & $Command[0] @($Command[1..($Command.Length - 1)] + $Arguments)
    } else {
        & $Command[0] @Arguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw "python failed ($LASTEXITCODE)"
    }
}

$python = Find-Python
$scriptDir = $PSScriptRoot
$target = $null

if ($scriptDir -and (Test-Path (Join-Path $scriptDir 'wlantron\__main__.py'))) {
    $target = $scriptDir
} else {
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
        throw 'curl.exe was not found. Windows 10 and later include it.'
    }
    $tmp = Join-Path $env:TEMP ("wlantron-" + [guid]::NewGuid().ToString('n'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        $zip = Join-Path $tmp 'src.zip'
        Write-Host "Downloading $Repo ($Ref)..."
        & curl.exe -fsSL -o $zip "$Repo/archive/refs/heads/$Ref.zip"
        if ($LASTEXITCODE -ne 0) { throw 'curl download failed' }
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $extracted = Get-ChildItem -Path $tmp -Directory | Where-Object {
            Test-Path (Join-Path $_.FullName 'wlantron\__main__.py')
        } | Select-Object -First 1
        if (-not $extracted) { throw 'download did not contain the wlantron package' }
        $share = Join-Path $DestRoot 'pkg'
        if (Test-Path $share) { Remove-Item -Recurse -Force $share }
        New-Item -ItemType Directory -Path (Join-Path $share 'wlantron') -Force | Out-Null
        Copy-Item -Recurse (Join-Path $extracted.FullName 'wlantron\*') (Join-Path $share 'wlantron')
        $target = $share
    } finally {
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    }
}

$main = Join-Path $target 'wlantron\__main__.py'
Invoke-Python -Command $python -Arguments @('-m', 'compileall', '-q', (Join-Path $target 'wlantron'))

$bin = Join-Path $DestRoot 'bin'
New-Item -ItemType Directory -Force -Path $bin | Out-Null

$pyLine = if ($python.Length -gt 1) {
    "$($python[0]) $($python[1])"
} else {
    '"' + $python[0] + '"'
}

$enc = New-Object System.Text.UTF8Encoding $false
foreach ($name in @('wifi-login', 'lan-login', 'wlantron')) {
    $path = Join-Path $bin "$name.cmd"
    $text = @"
@echo off
set WLANTRON_AS=$name
$pyLine "$main" %*
exit /b %ERRORLEVEL%
"@
    [System.IO.File]::WriteAllText($path, $text.Replace("`n", "`r`n"), $enc)
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not $userPath) { $userPath = '' }
$parts = @($userPath.Split(';') | Where-Object { $_ -ne '' })
if ($parts -notcontains $bin) {
    $joined = if ($userPath.Trim().Length -gt 0) { "$userPath;$bin" } else { $bin }
    [Environment]::SetEnvironmentVariable('Path', $joined, 'User')
    Write-Host "Added $bin to the user Path. Open a new terminal so wifi-login is found."
}

Write-Host "Installed wifi-login, lan-login, and wlantron into $bin"
Write-Host "Next: wifi-login --configure"
