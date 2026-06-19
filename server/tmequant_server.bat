@echo off
rem ===========================================================================
rem  tmequant_server.bat -- ONE smart installer + launcher for the TME Quant
rem  FIRE socket server. This is the single file QuPath points at (and that you
rem  can double-click). It:
rem    1. Finds MSYS2 -- installs it via winget if missing (browser fallback).
rem    2. On first run, does the one-time setup (system update + setup_fire_server.sh)
rem       and writes a .tmequant_setup_ok marker so later runs skip straight to launch.
rem    3. Starts the server in the MSYS2 UCRT64 console (shows the ~30-40 s load
rem       and the "listening on 127.0.0.1:5101" banner).
rem
rem  USAGE
rem    * Double-click this file, OR
rem    * run it from a terminal:  tmequant_server.bat [port]
rem
rem  It must live in ...\CTFireTest\fiber_socket_bridge\ (next to the .sh scripts
rem  and tmequant_boot.sh), with ..\tme-quant alongside. It finds everything
rem  relative to itself, so you can move the CTFireTest folder anywhere.
rem
rem  If MSYS2 is not at C:\msys64, set MSYS2_ROOT (env var) before running.
rem ===========================================================================

setlocal enableextensions

rem --- where is MSYS2? (override by setting MSYS2_ROOT before running) --------
if not defined MSYS2_ROOT set "MSYS2_ROOT=C:\msys64"

rem --- optional port argument (default 5101) ---------------------------------
set "PORT=%~1"
if "%PORT%"=="" set "PORT=5101"

rem --- this script's own folder (with trailing backslash) --------------------
set "HERE=%~dp0"

rem --- the code must be laid out as expected ---------------------------------
if not exist "%HERE%tmequant_boot.sh" (
    echo.
    echo ERROR: tmequant_boot.sh not found next to this .bat
    echo        ^(expected in "%HERE%"^). Re-extract the server scripts zip.
    echo.
    pause
    exit /b 1
)
if not exist "%HERE%..\tme-quant" (
    echo.
    echo WARNING: ..\tme-quant not found next to fiber_socket_bridge.
    echo          The server needs the tme-quant pipeline folder as a sibling
    echo          ^(e.g. D:\...\tmequant\tme-quant^). Setup will stop if it is missing.
    echo.
)

rem ===========================================================================
rem  Step 1: ensure MSYS2 is installed
rem ===========================================================================
if not exist "%MSYS2_ROOT%\msys2_shell.cmd" (
    echo MSYS2 not found at "%MSYS2_ROOT%". Trying to install it with winget...
    where winget >nul 2>nul
    if errorlevel 1 (
        echo.
        echo winget is not available on this machine. Opening the MSYS2 download
        echo page in your browser -- install MSYS2 ^(accept the defaults so it
        echo lands in C:\msys64^), then re-run this file.
        echo.
        start "" "https://www.msys2.org/"
        pause
        exit /b 1
    )
    winget install --id MSYS2.MSYS2 -e --accept-package-agreements --accept-source-agreements
    if not exist "%MSYS2_ROOT%\msys2_shell.cmd" (
        echo.
        echo winget finished but MSYS2 is still not at "%MSYS2_ROOT%".
        echo If it installed elsewhere, set MSYS2_ROOT to that folder and re-run,
        echo e.g.:   set "MSYS2_ROOT=D:\msys64"
        echo Opening the MSYS2 download page as a fallback...
        start "" "https://www.msys2.org/"
        pause
        exit /b 1
    )
    echo MSYS2 installed at "%MSYS2_ROOT%".
)

echo.
if exist "%HERE%.tmequant_setup_ok" (
    echo Launching TME Quant FIRE server in MSYS2 UCRT64 ^(port %PORT%^)...
) else (
    echo First run detected -- ONE-TIME setup, then it launches.
    echo.
    echo   *** This downloads several HUNDRED MB of packages and builds the
    echo   *** FIRE engine, so expect 5-15 minutes -- longer on a slow network.
    echo   *** It is NOT frozen. Leave this window OPEN until you see:
    echo   ***     Fiber socket server listening on 127.0.0.1:5101
    echo.
)
echo MSYS2 : %MSYS2_ROOT%
echo Folder: %HERE%
echo.

rem ===========================================================================
rem  Step 2 + 3: (first run) setup, then launch -- inside the UCRT64 shell.
rem    -ucrt64   : select the UCRT64 toolchain (so the compiled .pyd loads)
rem    -defterm  : use THIS console window (no separate mintty)
rem    -no-start : don't relaunch via `start`
rem    -here     : run in the CURRENT directory -- so we cd into this folder
rem                first (below) and keep the -c string trivially simple.
rem  Earlier versions passed cd "$(cygpath ...)" inside -c; cmd.exe mis-parses
rem  the $() / escaped quotes ("The syntax of the command is incorrect."), so we
rem  avoid that entirely: cd here, then -here lands MSYS2 in the same folder.
rem ===========================================================================
cd /d "%~dp0."
echo Starting MSYS2 UCRT64 environment...
call "%MSYS2_ROOT%\msys2_shell.cmd" -ucrt64 -defterm -no-start -here -c "./tmequant_boot.sh %PORT%"

echo.
echo Server stopped. Press any key to close this window.
pause >nul
endlocal
