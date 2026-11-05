# Install wlantron on Windows. Re-run to update.
#   curl.exe -fsSL https://raw.githubusercontent.com/Arcioth/wlantron/main/build.ps1 | powershell -NoProfile -ExecutionPolicy Bypass -Command -
# WLANTRON_PREFIX  default %LOCALAPPDATA%\wlantron
# WLANTRON_REF     default main
# ASCII only, so Windows PowerShell 5.1 does not misread the file.
# Do not call Remove-Item. An 8.3 name such as YT28B4~1 contains "~",
# and Remove-Item treats that as a wildcard and aborts the install.
$ErrorActionPreference = 'Stop'
$Repo = 'https://github.com/Arcioth/wlantron'
$Ref = if ($env:WLANTRON_REF) { $env:WLANTRON_REF } else { 'main' }
$Dest = if ($env:WLANTRON_PREFIX) { $env:WLANTRON_PREFIX } else { Join-Path $env:LOCALAPPDATA 'wlantron' }

function Test-Py([string[]]$Cmd) {
    $code = 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)'
    if ($Cmd.Length -gt 1) { & $Cmd[0] @($Cmd[1..($Cmd.Length - 1)]) -c $code } else { & $Cmd[0] -c $code }
    $LASTEXITCODE -eq 0
}

function Find-Python {
    if ((Get-Command py -ErrorAction SilentlyContinue) -and (Test-Py @('py', '-3'))) { return ,@('py', '-3') }
    foreach ($name in @('python', 'python3')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd -and (Test-Py @($cmd.Source))) { return ,@($cmd.Source) }
    }
    throw 'Python 3.10+ was not found. Install it from https://www.python.org/downloads/ and tick the py launcher.'
}

function Invoke-Py([string[]]$Cmd, [string[]]$PyArgs) {
    if ($Cmd.Length -gt 1) { & $Cmd[0] @($Cmd[1..($Cmd.Length - 1)] + $PyArgs) } else { & $Cmd[0] @PyArgs }
    if ($LASTEXITCODE -ne 0) { throw "python failed ($LASTEXITCODE)" }
}

function Copy-Tree([string]$Source, [string]$DestDir) {
    [IO.Directory]::CreateDirectory($DestDir) | Out-Null
    foreach ($file in [IO.Directory]::GetFiles($Source)) {
        [IO.File]::Copy($file, (Join-Path $DestDir (Split-Path $file -Leaf)), $true)
    }
    foreach ($dir in [IO.Directory]::GetDirectories($Source)) {
        Copy-Tree $dir (Join-Path $DestDir (Split-Path $dir -Leaf))
    }
}

$py = Find-Python
$here = $PSScriptRoot
$marker = 'wlantron\__main__.py'
if ($here -and [IO.File]::Exists((Join-Path $here $marker))) {
    $target = $here
} else {
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { throw 'curl.exe was not found.' }
    $tmp = Join-Path $env:TEMP ("wlantron-" + [guid]::NewGuid().ToString('n'))
    [IO.Directory]::CreateDirectory($tmp) | Out-Null
    try {
        $zip = Join-Path $tmp 'src.zip'
        Write-Host "Downloading $Repo ($Ref)..."
        & curl.exe -fsSL -o $zip "$Repo/archive/refs/heads/$Ref.zip"
        if ($LASTEXITCODE -ne 0) { throw 'curl download failed' }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $unpack = Join-Path $tmp 'src'
        [IO.Compression.ZipFile]::ExtractToDirectory($zip, $unpack)
        $src = $null
        foreach ($dir in [IO.Directory]::GetDirectories($unpack)) {
            if ([IO.File]::Exists((Join-Path $dir $marker))) { $src = $dir; break }
        }
        if (-not $src) { throw 'download did not contain the wlantron package' }
        $pkg = Join-Path $Dest 'pkg\wlantron'
        if ([IO.Directory]::Exists($pkg)) { [IO.Directory]::Delete($pkg, $true) }
        Copy-Tree (Join-Path $src 'wlantron') $pkg
        $target = Join-Path $Dest 'pkg'
    } finally {
        if ([IO.Directory]::Exists($tmp)) {
            try { [IO.Directory]::Delete($tmp, $true) } catch { Write-Warning $_.Exception.Message }
        }
    }
}

$main = Join-Path $target $marker
Invoke-Py $py @('-m', 'compileall', '-q', (Join-Path $target 'wlantron'))
$bin = Join-Path $Dest 'bin'
[IO.Directory]::CreateDirectory($bin) | Out-Null
$pyLine = if ($py.Length -gt 1) { "$($py[0]) $($py[1])" } else { '"' + $py[0] + '"' }
$utf8 = New-Object System.Text.UTF8Encoding $true
foreach ($name in @('wifi-login', 'lan-login', 'wlantron')) {
    $cmd = "@echo off`r`nset WLANTRON_AS=$name`r`n$pyLine `"$main`" %*`r`nexit /b %ERRORLEVEL%`r`n"
    [IO.File]::WriteAllText((Join-Path $bin "$name.cmd"), $cmd, $utf8)
}

$path = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not $path) { $path = '' }
if (@($path.Split(';') | Where-Object { $_ }) -notcontains $bin) {
    $joined = if ($path.Trim()) { "$path;$bin" } else { $bin }
    [Environment]::SetEnvironmentVariable('Path', $joined, 'User')
    Write-Host "Added $bin to the user Path. Open a new terminal so wifi-login is found."
}
Write-Host "Installed wifi-login, lan-login, and wlantron into $bin"
Write-Host "Next: wifi-login --configure"
Write-Host "Later: wifi-login --update"
