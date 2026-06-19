@echo off
rem ===========================================================================
rem  start_fire_server.bat -- one double-click launcher for the TME Quant
rem  FIRE socket server. Opens the MSYS2 UCRT64 environment (where the compiled
rem  fiber_backend loads) and runs start_fire_server.sh.
rem
rem  USAGE
rem    * Double-click this file, OR
rem    * run it from a terminal:  start_fire_server.bat [port]
rem
rem  It lives in ...\CTFireTest\fiber_socket_bridge\ and finds everything else
rem  relative to itself, so you can move the CTFireTest folder anywhere.
rem
rem  If MSYS2 is not at C:\msys64, set MSYS2_ROOT below (or as an env var).
rem ===========================================================================

setlocal

rem --- where is MSYS2? (override by setting MSYS2_ROOT before running) --------
if not defined MSYS2_ROOT set "MSYS2_ROOT=C:\msys64"

rem --- optional port argument (default 5101) ---------------------------------
set "PORT=%~1"
if "%PORT%"=="" set "PORT=5101"

rem --- this script's own folder (with trailing backslash) --------------------
set "HERE=%~dp0"

rem --- sanity checks ---------------------------------------------------------
if not exist "%MSYS2_ROOT%\msys2_shell.cmd" (
    echo.
    echo ERROR: MSYS2 not found at "%MSYS2_ROOT%".
    echo        Install MSYS2 ^(https://www.msys2.org^) or set MSYS2_ROOT to its
    echo        folder, e.g.:  set "MSYS2_ROOT=D:\msys64"  then re-run.
    echo.
    pause
    exit /b 1
)
if not exist "%HERE%start_fire_server.sh" (
    echo.
    echo ERROR: start_fire_server.sh not found next to this .bat
    echo        ^(expected in "%HERE%"^).
    echo.
    pause
    exit /b 1
)

echo Launching TME Quant FIRE server in MSYS2 UCRT64 (port %PORT%)...
echo MSYS2 : %MSYS2_ROOT%
echo Folder: %HERE%
echo.

rem ---------------------------------------------------------------------------
rem Run inside the UCRT64 environment, in THIS console window:
rem   -ucrt64   : select the UCRT64 toolchain (sets MSYSTEM=UCRT64 so the
rem               compiled .pyd's GCC runtime DLLs resolve and the backend loads)
rem   -defterm  : use this Windows console instead of spawning a mintty window
rem   -no-start : don't relaunch via `start`
rem   -here     : keep the current directory
rem   -c        : the command to run -- cygpath turns the Windows .bat folder
rem               into an MSYS path so we cd to it regardless of drive letter.
rem ---------------------------------------------------------------------------
call "%MSYS2_ROOT%\msys2_shell.cmd" -ucrt64 -defterm -no-start -here -c ^
  "cd \"$(cygpath -u '%HERE%')\" && ./start_fire_server.sh %PORT%"

echo.
echo Server stopped. Press any key to close this window.
pause >nul
endlocal
