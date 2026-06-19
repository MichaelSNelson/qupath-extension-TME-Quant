#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# tmequant_boot.sh -- first-run setup (if needed) then launch the FIRE socket
# server. Run INSIDE the MSYS2 UCRT64 shell (tmequant_server.bat does that).
#
# Keeping this logic in a script (instead of a long inline `bash -c "..."` in
# the .bat) avoids cmd.exe mis-parsing braces/semicolons/parentheses.
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE" || { echo "Cannot cd to $HERE"; exit 1; }

PORT="${1:-5101}"

pause_close() {
    echo
    echo "Press Enter to close this window."
    # shellcheck disable=SC2162
    read _ || true
}

if [ ! -f .tmequant_setup_ok ]; then
    echo "==================================================================="
    echo " First-time setup: downloading packages + building the FIRE engine."
    echo " This can take 5-15 minutes (longer on a slow network). It is NOT"
    echo " frozen -- please wait and do NOT close this window."
    echo "==================================================================="
    # First MSYS2 update may need two passes; don't abort the whole boot if a
    # pass is partial -- setup_fire_server.sh reports the real problems.
    pacman -Syu --noconfirm || true
    pacman -Su  --noconfirm || true
    if ./setup_fire_server.sh; then
        touch .tmequant_setup_ok
        echo "=== Setup complete. ==="
    else
        echo
        echo "*** Setup did not finish. Read the messages above for the cause"
        echo "    (common: the 'tme-quant' pipeline folder is missing, or no"
        echo "    network for pacman downloads). Fix it, then run this again."
        pause_close
        exit 1
    fi
fi

# Launch the server (runs until you close the window or Ctrl+C). If it can't
# start, hold the window so the user can read why.
./start_fire_server.sh "$PORT"
status=$?
if [ "$status" -ne 0 ]; then
    echo
    echo "*** The server exited with an error (code $status). See above."
    pause_close
fi
exit "$status"
