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

# Windows PowerShell treats "~" in an 8.3 short name (YT28B4~1) as a wildcard.
# Remove-Item then looks for C:\Users\YT28B4~1 and aborts the install.
# .NET path APIs do not do that. The script stays ASCII so Windows PowerShell
# 5.1 does not misread it.
if (-not ('Wlantron.Win32.Kernel' -as [type])) {
    Add-Type -Namespace 'Wlantron.Win32' -Name 'Kernel' -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode, SetLastError = true)]
public static extern uint GetLongPathName(string shortPath, System.Text.StringBuilder longPath, uint buffer);
'@
}

function ConvertTo-LongPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $Path }
    $full = [System.IO.Path]::GetFullPath($Path)
    $buf = New-Object System.Text.StringBuilder 4096
    $n = [Wlantron.Win32.Kernel]::GetLongPathName($full, $buf, [uint32]$buf.Capacity)
    if ($n -gt 0 -and $n -lt $buf.Capacity) { return $buf.ToString() }
    $parent = [System.IO.Path]::GetDirectoryName($full)
    $leaf = [System.IO.Path]::GetFileName($full)
    if (-not $parent) { return $full }
    $buf2 = New-Object System.Text.StringBuilder 4096
    $n2 = [Wlantron.Win32.Kernel]::GetLongPathName($parent, $buf2, [uint32]$buf2.Capacity)
    if ($n2 -gt 0 -and $n2 -lt $buf2.Capacity) {
        return [System.IO.Path]::Combine($buf2.ToString(), $leaf)
    }
    return $full
}

function Remove-Tree([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $long = ConvertTo-LongPath $Path
    if (-not [System.IO.Directory]::Exists($long)) { return }
    try {
        [System.IO.Directory]::Delete($long, $true)
    } catch {
        Write-Warning "Could not remove $long ($($_.Exception.Message))"
    }
}

function Copy-Tree([string]$Source, [string]$Dest) {
    $src = ConvertTo-LongPath $Source
    $dst = ConvertTo-LongPath $Dest
    [System.IO.Directory]::CreateDirectory($dst) | Out-Null
    foreach ($file in [System.IO.Directory]::GetFiles($src)) {
        $name = [System.IO.Path]::GetFileName($file)
        [System.IO.File]::Copy($file, [System.IO.Path]::Combine($dst, $name), $true)
    }
    foreach ($dir in [System.IO.Directory]::GetDirectories($src)) {
        $name = [System.IO.Path]::GetFileName($dir)
        Copy-Tree $dir ([System.IO.Path]::Combine($dst, $name))
    }
}

$Repo = 'https://github.com/Arcioth/wlantron'
$Ref = if ($env:WLANTRON_REF) { $env:WLANTRON_REF } else { 'main' }
$DestRoot = ConvertTo-LongPath $(if ($env:WLANTRON_PREFIX) { $env:WLANTRON_PREFIX } else { Join-Path $env:LOCALAPPDATA 'wlantron' })

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

if ($scriptDir -and [System.IO.File]::Exists((Join-Path $scriptDir 'wlantron\__main__.py'))) {
    $target = ConvertTo-LongPath $scriptDir
} else {
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
        throw 'curl.exe was not found. Windows 10 and later include it.'
    }
    $tmp = ConvertTo-LongPath (Join-Path $env:TEMP ("wlantron-" + [guid]::NewGuid().ToString('n')))
    [System.IO.Directory]::CreateDirectory($tmp) | Out-Null
    try {
        $zip = Join-Path $tmp 'src.zip'
        Write-Host "Downloading $Repo ($Ref)..."
        & curl.exe -fsSL -o $zip "$Repo/archive/refs/heads/$Ref.zip"
        if ($LASTEXITCODE -ne 0) { throw 'curl download failed' }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $unpack = Join-Path $tmp 'src'
        [System.IO.Directory]::CreateDirectory($unpack) | Out-Null
        [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $unpack)
        $extracted = $null
        foreach ($dir in [System.IO.Directory]::GetDirectories($unpack)) {
            if ([System.IO.File]::Exists((Join-Path $dir 'wlantron\__main__.py'))) {
                $extracted = $dir
                break
            }
        }
        if (-not $extracted) { throw 'download did not contain the wlantron package' }
        $share = Join-Path $DestRoot 'pkg'
        $pkg = Join-Path $share 'wlantron'
        if ([System.IO.Directory]::Exists($pkg)) { Remove-Tree $pkg }
        Copy-Tree (Join-Path $extracted 'wlantron') $pkg
        $target = $share
    } finally {
        Remove-Tree $tmp
    }
}

$main = Join-Path $target 'wlantron\__main__.py'
Invoke-Python -Command $python -Arguments @('-m', 'compileall', '-q', (Join-Path $target 'wlantron'))

$bin = Join-Path $DestRoot 'bin'
[System.IO.Directory]::CreateDirectory($bin) | Out-Null

$pyLine = if ($python.Length -gt 1) {
    "$($python[0]) $($python[1])"
} else {
    '"' + $python[0] + '"'
}

# BOM so cmd.exe keeps a non-ASCII user name inside the launcher path.
$enc = New-Object System.Text.UTF8Encoding $true
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
Write-Host "Later: wifi-login --update"
