@echo off
rem ===========================================================================
rem  tmequant_server.bat -- ONE smart installer + launcher for the TME Quant
rem  FIRE socket server. This is the single file QuPath points at (and that you
rem  can double-click). It:
rem    1. Finds MSYS2 -- and if it's missing, ASKS before installing (you can
rem       install it yourself to any drive, or set MSYS2_ROOT).
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
rem  MSYS2 location: defaults to C:\msys64. To use another drive/folder, set
rem  MSYS2_ROOT before running, e.g.  set "MSYS2_ROOT=D:\msys64"
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
rem  Step 1: ensure MSYS2 is installed. We ASK before installing -- it is a
rem  large download and the default C:\msys64 may not be writable for everyone.
rem ===========================================================================
if exist "%MSYS2_ROOT%\msys2_shell.cmd" goto :have_msys2

echo.
echo MSYS2 (the Unix toolchain the FIRE engine needs) was not found at:
echo     "%MSYS2_ROOT%"
echo.
echo   [I] Install it now with winget, to "%MSYS2_ROOT%"
echo   [M] I'll install MSYS2 myself  (opens msys2.org; the installer lets you
echo       pick ANY drive/folder -- choose this if you can't write to C:\msys64)
echo   [Q] Quit, do nothing
echo.
echo   Tip: to install to a different drive with [I], quit, then run e.g.
echo        set "MSYS2_ROOT=D:\msys64"   and re-run this file. (winget may still
echo        ignore a custom location for MSYS2; [M] is the reliable way.)
echo.
choice /c IMQ /n /m "Choose I, M, or Q: "
if errorlevel 3 goto :user_cancel
if errorlevel 2 goto :install_manual

rem --- [I] install with winget ----------------------------------------------
where winget >nul 2>nul
if errorlevel 1 (
    echo.
    echo winget is not available on this machine, so it can't auto-install.
    goto :install_manual
)
echo.
echo Installing MSYS2 via winget (target: "%MSYS2_ROOT%")...
winget install --id MSYS2.MSYS2 -e --accept-package-agreements --accept-source-agreements --location "%MSYS2_ROOT%"
if exist "%MSYS2_ROOT%\msys2_shell.cmd" goto :msys2_ok
rem winget often ignores --location for MSYS2; retry to its default and detect it.
winget install --id MSYS2.MSYS2 -e --accept-package-agreements --accept-source-agreements
if exist "%MSYS2_ROOT%\msys2_shell.cmd" goto :msys2_ok
if exist "C:\msys64\msys2_shell.cmd" set "MSYS2_ROOT=C:\msys64"
if exist "%MSYS2_ROOT%\msys2_shell.cmd" goto :msys2_ok
echo.
echo winget did not install MSYS2 where expected. Common causes:
echo   - no permission to write to "%MSYS2_ROOT%" (e.g. C: is locked down), or
echo   - winget ignored the custom location, or needed admin you could not grant.
echo.
echo What to do: re-run this file and choose [M] to install MSYS2 yourself into a
echo folder you OWN (no admin / no C: write needed). Then make it permanent:
echo     setx MSYS2_ROOT "D:\msys64"     (use your folder)
echo and RESTART QuPath so its auto-launch picks up the new location.
echo.
pause
exit /b 1

:msys2_ok
echo MSYS2 installed at "%MSYS2_ROOT%".
goto :have_msys2

:install_manual
echo.
echo Opening https://www.msys2.org/ -- run the installer and pick any folder you
echo like (e.g. D:\msys64). When it finishes:
echo   - if you installed to C:\msys64, just re-run this file;
echo   - otherwise set MSYS2_ROOT to your folder first, e.g.
echo         set "MSYS2_ROOT=D:\msys64"    then    tmequant_server.bat
echo.
start "" "https://www.msys2.org/"
pause
exit /b 1

:user_cancel
echo.
echo Cancelled -- nothing was installed.
pause
exit /b 1

:have_msys2

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
