<#
    Start-FireServer.ps1 -- convenience launcher for the fiber socket server.

    Opens the MSYS2 UCRT64 environment and runs start_fire_server.sh, so you can
    start the server from Windows (right-click > Run with PowerShell) without
    manually opening the MSYS2 shell.

    Usage:
        powershell -ExecutionPolicy Bypass -File .\Start-FireServer.ps1 [-Port 5101]

    Requires MSYS2 installed at C:\msys64 with the UCRT64 Python stack
    (run setup_fire_server.sh once first).
#>
param(
    [int]$Port = 5101,
    [string]$Msys2Root = "C:\msys64"
)

$ErrorActionPreference = "Stop"

$shellCmd = Join-Path $Msys2Root "msys2_shell.cmd"
if (-not (Test-Path $shellCmd)) {
    Write-Error "MSYS2 not found at $Msys2Root (expected $shellCmd). Install from https://www.msys2.org"
    exit 1
}

# Folder this script lives in (F:\CTFireTest\fiber_socket_bridge).
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# Convert a Windows path (F:\CTFireTest\...) to an MSYS path (/f/CTFireTest/...).
$drive = $here.Substring(0, 1).ToLower()
$rest  = $here.Substring(2) -replace '\\', '/'
$unix  = "/$drive$rest"

Write-Host "Launching fiber socket server in MSYS2 UCRT64 on port $Port ..."
Write-Host "  (folder: $unix)"

# -ucrt64     : select the UCRT64 environment (so the .pyd loads)
# -defterm    : use the default terminal (don't spawn mintty)
# -no-start   : run in this console
# -here       : start in the current working directory
# The trailing 'exec bash' keeps the window open if the server exits/errors.
Push-Location $here
try {
    & $shellCmd -ucrt64 -defterm -no-start -here -c "./start_fire_server.sh $Port; echo; echo 'Server exited. Press Enter to close.'; read"
}
finally {
    Pop-Location
}
