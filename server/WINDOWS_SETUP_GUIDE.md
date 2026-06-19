# TME Quant — step-by-step Windows 11 setup (for first-timers)

## Quickest path (try this first)

Most of the setup is now automated by a single file:

1. Put the release folder somewhere (e.g. `F:\CTFireTest`) so you have
   `F:\CTFireTest\fiber_socket_bridge\` and `F:\CTFireTest\tme-quant\` side by side.
2. Double-click **`fiber_socket_bridge\tmequant_server.bat`**. The first run installs
   MSYS2 if it's missing (or opens the download page if it can't), does the one-time
   setup, then starts the server. Later runs just start the server (fast). Leave its
   console window open while you work.
3. In QuPath, install the extension `.jar` (drag-and-drop), then open
   *Extensions ▸ TME Quant ▸ Analyze fibers…*. The first time, QuPath will offer to
   **locate the launcher** — point it at `tmequant_server.bat` and it'll start the
   server for you automatically from then on (set it any time via
   *Extensions ▸ TME Quant ▸ Configure fiber server launcher…* or QuPath *Preferences*).

If the quick path works, you're done. The detailed walkthrough below is the fallback
if a step fails or you want to understand what's happening.

---

This guide assumes you have **not** used MSYS2 or Python-from-a-terminal before.
Follow it top to bottom. Where a step says "type this", you type it and press
**Enter**.

By the end you'll have:
- a small **server** program running (it does the fiber math), and
- a **QuPath menu** that sends a region to the server and draws the fibers back.

---

## 0. The pieces, in plain language

- **QuPath** — the image software you already use. The extension (a `.jar` file)
  is already installed; it adds an *Extensions ▸ TME Quant* menu.
- **The server** — a Python program (`fiber_socket_server.py`) that listens for
  requests from QuPath, runs the FIRE fiber-finding code, and sends fibers back.
- **MSYS2 / "the UCRT64 shell"** — a special terminal window on Windows. The
  fiber code is compiled in a way that *only* runs from this particular terminal,
  so we start the server from there. "Run this in the MSYS2 UCRT64 shell" simply
  means: open that black terminal window (Part 1, step 3) and type the command in
  it. It is **not** PowerShell and **not** Command Prompt.

You will mostly do two things:
1. **Once:** install prerequisites + run a setup script.
2. **Each session:** start the server, then use the QuPath menu.

---

## TL;DR (condensed — full details below)

Already comfortable with terminals? The whole setup is:

1. Install **MSYS2** (https://www.msys2.org) and **QuPath 0.7.0**. Open the
   **MSYS2 UCRT64** shell and run `pacman -Syu` (twice if it closes).
2. Put `fiber_socket_bridge\` and `tme-quant\` together in a folder, e.g.
   `F:\CTFireTest\`.
3. In the **MSYS2 UCRT64** shell:
   ```bash
   cd /f/CTFireTest/fiber_socket_bridge
   ./setup_fire_server.sh     # installs packages; auto-rebuilds the engine if needed
   ./start_fire_server.sh     # leave running
   ```
4. In QuPath: *Extensions ▸ TME Quant ▸ Ping fiber server* (expect
   `backend = real`), then select a region/annotation and *Analyze fibers…*.

That's it — `setup_fire_server.sh` is self-healing (if the prebuilt engine
doesn't match your machine it rebuilds it from source automatically). The rest of
this document explains each step for first-time users.

---

## What a tester needs (checklist)

- [ ] **Windows 10/11 x64** (not ARM).
- [ ] **MSYS2** installed at `C:\msys64` (https://www.msys2.org), updated with
      `pacman -Syu` from the **MSYS2 UCRT64** shell.
- [ ] **QuPath 0.7.0** with the **`qupath-extension-tme-quant` jar** installed
      (drag the jar onto QuPath).
- [ ] A folder (e.g. `F:\CTFireTest\`) containing **both**:
      `fiber_socket_bridge\` (these scripts) and `tme-quant\` (the pipeline +
      C++ source).
- [ ] Internet access the first time (so `pacman`/`pip` can download packages).

No WSL, no Visual Studio, no Python from python.org. Everything comes from MSYS2.

---

## Part 1 — Install the prerequisites (one time)

### 1a. Install QuPath 0.7.0
If you don't already have it, get QuPath **0.7.0** from https://qupath.github.io
and install it normally. (You said the extension is already installed, so you
likely have this.)

### 1b. Install MSYS2
1. Go to **https://www.msys2.org**.
2. Download the installer (`msys2-x86_64-*.exe`) and run it.
3. Accept the default install location: **`C:\msys64`**. Click through to finish.

> If you let `tmequant_server.bat` install MSYS2 for you, it **asks first** —
> `[I]` install to the default, `[M]` install it yourself anywhere, or `[Q]` quit.
> It does **not** silently write to `C:`.

### 1b-2. Installing MSYS2 somewhere other than `C:\msys64`

Use this if you **can't write to `C:`** (locked-down/managed machine), or simply
prefer another drive. **The only thing that has to change is one setting,
`MSYS2_ROOT`** — nothing inside the MSYS2 shell (the `setup_*`/`start_*`/`*.sh`
scripts and the Python server) depends on where MSYS2 lives; they work relative to
the repo. So adapting to a custom location is just:

1. **Install MSYS2 to a folder you can write to.** Run the installer from
   https://www.msys2.org and pick your folder (e.g. `D:\msys64`, or a folder under
   your user profile). A user-writable folder needs **no admin rights**. *(With
   `tmequant_server.bat`, choose `[M]` for this — `winget`'s `--location` is
   unreliable for the MSYS2 package, so the GUI installer's folder picker is the
   dependable way to land on a non-`C:` drive.)*
2. **Point everything at it by setting `MSYS2_ROOT` permanently.** Open *Command
   Prompt* and run (using your actual path):
   ```
   setx MSYS2_ROOT "D:\msys64"
   ```
   `setx` makes it a **persistent user environment variable**. **Then fully restart
   QuPath** (and close any Command Prompt you'll launch the `.bat` from) — programs
   only read environment variables when they start.
   - **Why the restart matters for QuPath:** when QuPath auto-launches the server, it
     runs `tmequant_server.bat` as a child process that **inherits QuPath's
     environment**. If `MSYS2_ROOT` was set with `setx` *before* QuPath started,
     QuPath (and the `.bat` it spawns) will use your custom path. A one-off
     `set MSYS2_ROOT=...` in a single Command Prompt only affects that window, **not**
     QuPath's auto-launch.
   - **Alternative (no env var):** edit `tmequant_server.bat` and change the line
     `set "MSYS2_ROOT=C:\msys64"` to your path. This is bulletproof but you must
     re-apply it if you re-download the server zip.
3. **Other references to `C:\msys64`** in this guide (the manual diagnostic commands,
   and the `DLL load failed` PATH tip in Troubleshooting) are examples — substitute
   your folder (e.g. `D:\msys64\ucrt64\bin`).

**If the install location is blocked *during* the `.bat` run** (no write permission,
or a UAC/admin prompt you can't approve for `C:\`): `winget` fails and the batch does
**not** crash — it reports that MSYS2 still isn't where expected and tells you to set
`MSYS2_ROOT` and re-run, or to choose `[M]`. The fix is the same as above: re-run,
pick `[M]`, and install MSYS2 into a folder you own (no `C:` write, no admin needed),
then `setx MSYS2_ROOT` to it and restart QuPath.

### 1c. Open the "MSYS2 UCRT64" terminal
1. Click the Windows **Start** button.
2. Type `MSYS2 UCRT64`.
3. Click the entry that says **"MSYS2 UCRT64"** (it has a blue-ish icon).
   - ⚠️ There are several similarly named entries (MSYS2 MSYS, MINGW64, CLANG64).
     You must pick **UCRT64**. The wrong one will make the fiber code fail to load.
4. A black terminal window opens. The prompt text should contain **`UCRT64`**.

### 1d. Update MSYS2 (first launch only)
In that UCRT64 window, type:
```
pacman -Syu
```
Press Enter, and when asked `Proceed with installation? [Y/n]` type `Y` Enter.
If the window closes itself at the end, that's normal — reopen "MSYS2 UCRT64"
(step 1c) and run it once more:
```
pacman -Su
```

> **Tip — pasting into the terminal:** Ctrl+V often doesn't work here. Use
> **right-click** (or Shift+Insert) to paste.

---

## Part 2 — Put the files in place

You already copied `fiber_socket_bridge` to `F:\CTFireTest\fiber_socket_bridge`.
Two more things:

### 2a. Copy the pipeline code (`tme-quant`)
The server needs the FIRE pipeline, which lives in a folder called **`tme-quant`**.
- Find your `TMEQuant_fire_only.zip`, right-click ▸ **Extract All**.
- Inside the extracted files you'll find a folder named **`tme-quant`**
  (it contains `src`, `tests`, `examples`).
- Copy that **`tme-quant`** folder to **`F:\CTFireTest\tme-quant`**.

### 2b. Copy the new setup scripts
Copy these files (created alongside this guide) into
`F:\CTFireTest\fiber_socket_bridge\` if they aren't there already:
`setup_fire_server.sh`, `start_fire_server.sh`, `test_fire_server.sh`,
`Start-FireServer.ps1`, and this `WINDOWS_SETUP_GUIDE.md`.

When done, your folder looks like:
```
F:\CTFireTest\
├── fiber_socket_bridge\
│   ├── fiber_socket_server.py
│   ├── setup_fire_server.sh
│   ├── start_fire_server.sh
│   └── ... (the rest)
└── tme-quant\
    ├── src\
    └── tests\
```

---

## Part 3 — One-time setup (installs Python packages, checks everything)

1. Open the **MSYS2 UCRT64** terminal (Part 1, step 1c) if it isn't open.
2. Go to the bridge folder. In MSYS2, the drive `F:\` is written `/f/`, and
   backslashes become forward slashes. Type:
   ```
   cd /f/CTFireTest/fiber_socket_bridge
   ```
3. Run the setup script:
   ```
   ./setup_fire_server.sh
   ```
   This will:
   - download the needed Python packages (numpy, scipy, OpenCV, etc.) — the first
     time this can take several minutes and print a lot of text; that's normal,
   - check that the **real FIRE backend loads**, and
   - check it can find your `tme-quant` folder.

4. **What you'll see:**
   - It downloads packages (first time: several minutes, lots of text — normal).
   - **The first run usually pauses to rebuild the fiber engine.** The project
     ships a pre-compiled engine (`...pyd`), but pre-compiled binaries often don't
     match a given machine's exact library versions. When that happens the script
     prints `The prebuilt FIRE engine did not load ... Rebuilding from source`,
     installs a compiler, builds it (~1 min), and continues — **fully automatic,
     nothing for you to do.** This needs only MSYS2 (no WSL, no other tools).
   - **Success looks like this** at the end:
     ```
     fiber_backend: .../fiber_backend.cp314-mingw_x86_64_ucrt_gnu.pyd
     ALL IMPORTS OK -- real FIRE backend loaded
     == Setup complete. ==
     ```
   If you see a hard error instead, jump to **Troubleshooting** below.

> **Manual rebuild (rarely needed):** if you ever want to rebuild the engine on
> its own, run `./build_backend.sh`. `setup_fire_server.sh` calls it for you, so
> normally you don't have to.

You only need to do Part 3 once.

---

## Part 4 — Start the server (each working session)

1. Open **MSYS2 UCRT64**.
2. ```
   cd /f/CTFireTest/fiber_socket_bridge
   ```
3. ```
   ./start_fire_server.sh
   ```
4. Leave this window open. You should see:
   ```
   Fiber socket server listening on 127.0.0.1:5101 (backend=real)
   ```
   - `backend=real` ✅ means the true FIRE algorithm is running.
   - `backend=synthetic` ⚠️ means the real code didn't load (see Troubleshooting);
     you'll still get *something*, but not real fibers.

To **stop** the server later: click that window and press **Ctrl+C**.

> **One-click shortcut (recommended):** Instead of steps 1-3, just **double-click
> `start_fire_server.bat`** (in the `fiber_socket_bridge` folder). It opens the
> UCRT64 environment and starts the server in one window — no typing, no MSYS2
> shell to find. The window stays open so you can read the log; close it (or
> Ctrl+C) to stop the server.
>
> - Different port? `start_fire_server.bat 5102`.
> - MSYS2 not at `C:\msys64`? Set it once: open *Command Prompt* and run
>   `setx MSYS2_ROOT "D:\msys64"`, then use the .bat normally.
> - Prefer PowerShell? `Start-FireServer.ps1` (right-click ▸ *Run with PowerShell*)
>   does the same thing.
>
> **Put it on your Desktop:** right-click `start_fire_server.bat` ▸ **Show more
> options** ▸ **Send to** ▸ **Desktop (create shortcut)**. Now the server is one
> double-click away. (The shortcut points back to the .bat, so don't move the
> `fiber_socket_bridge` folder after making it — if you do, just recreate the
> shortcut.)

---

## Part 5 — Use it in QuPath

With the server window still running:

1. Open QuPath and open an image (an SHG / collagen image works best).
2. Check the connection: **Extensions ▸ TME Quant ▸ Ping fiber server**.
   - A small notification should say `backend = real`. If it says it can't reach
     the server, the server window isn't running (redo Part 4).
3. Draw a **rectangle** (or any annotation) over the area to analyse, and click it
   so it's **selected** (highlighted).
4. **Extensions ▸ TME Quant ▸ Analyze fibers in selected region…** — this opens
   the **analysis dialog** where you tune settings and preview before committing:

   | Control | What it does |
   |---|---|
   | **Collagen channel** | For multi-channel/fluorescence images, pick the channel that holds the fiber signal. (Only one channel is sent to the engine.) |
   | **Downsample** | Resolution reduction. **Large regions: use 2-8** — the engine's defaults are tuned for ~500 px images, so full-res whole-slide regions give junk. Higher = faster + coarser. |
   | **Background threshold (%)** | Background cutoff as a percent of the brightness range (works for 8/16/32-bit alike). Raise it if you get noise fibers in dark areas. |
   | **Min fiber length** | Drops short specks. In **µm** if the image is calibrated, else px. |
   | **Seed spacing** | Raise to reduce fiber count / memory on big regions. |
   | **Fiber mode** | Merged fibers vs. raw segments. |
   | **Classify TACS** + **Boundary annotation** | Tick to colour fibers by TACS; pick the tumour outline (an annotation inside the region) as the boundary. |
   | **Advanced FIRE settings** (expandable) | Smoothing (denoise), seed sensitivity, gap-link distance, max fiber width. |

5. Click **Preview**. The region appears with the detected fibers drawn over it,
   and the status line shows the fiber count. **Adjust the settings and Preview
   again** until the fibers trace the real collagen well.
6. Click **Add to image** to commit those fibers to QuPath as annotations (or tick
   *Create detections* first). **Close** discards the preview.

> **Why a preview?** The engine's defaults suit small images; on a big or
> multi-channel region the first try often looks wrong. Preview lets you find the
> right channel + threshold + downsample *before* adding hundreds of objects.

### Auto-suggesting good settings ("Suggest parameters…")

If you're not sure what threshold / seed spacing / smoothing to use, let the
software search for you:

1. (Recommended) Over a **small** test region, **trace a handful of real fibers
   as line annotations** — QuPath's line/polyline tool, ~5–15 of them, following
   collagen you can clearly see. These are the "ground truth".
2. Select the region annotation, open the dialog, and click **Suggest parameters…**.
3. It runs a grid of settings on that region (a few seconds), scoring each by how
   well it recovers your traced fibers (right location *and* orientation), and
   shows a **ranked list**. Pick one and click **Apply to fields**, then **Preview**.

If you *don't* draw any lines, it still works — it falls back to scoring by how
much of the collagen gets covered by fibers and how straight they are — but a few
ground-truth lines give a much sharper suggestion. Keep the test region small so
the search stays fast.

### TACS classification (fibers coloured by tumour-boundary angle)
1. Draw/select an annotation that traces the **tumour boundary** (wand, polygon,
   brush — any shape).
2. **Extensions ▸ TME Quant ▸ Analyze fibers + TACS (boundary = selection)…**
   (same dialog, with **Classify TACS** already ticked), or just tick that box in
   the normal dialog.
   - Fibers are coloured by TACS relative to that boundary:
     **red = TACS-3** (perpendicular / invasive), **green = TACS-2** (parallel),
     **blue = TACS-1** (random); fibers too far from the boundary stay green.
     Angle-to-boundary and distance are stored as per-fiber measurements.

> The dialog reads the **bounding box** of your selection as the image region,
> and (for TACS) uses the selection's **outline** as the boundary.

---

## Part 6 — (Optional) test the server without QuPath

Useful to confirm the server works on its own. In a **second** MSYS2 UCRT64
window (leave the server running in the first):
```
cd /f/CTFireTest/fiber_socket_bridge
./test_fire_server.sh
```
It sends a built-in test image and prints how many fibers came back, and writes
`roundtrip_overlay.png` in the same folder that you can open to see the fibers
drawn on the image. Add TACS to the test with:
```
./test_fire_server.sh 5101    # then look for the "TACS distribution" line
```

---

## Troubleshooting

| What you see | What it means | Fix |
|---|---|---|
| Setup says `not in the UCRT64 shell` | You opened the wrong terminal | Close it, open **MSYS2 UCRT64** specifically (Part 1, step 1c) |
| Setup/server says `tme-quant repo not found` | The pipeline folder isn't where expected | Make sure it's at `F:\CTFireTest\tme-quant` (Part 2a) |
| Server prints `backend=synthetic` | The compiled FIRE backend didn't load | You're not in UCRT64, **or** the engine needs rebuilding — run `./build_backend.sh` (see Part 3b) |
| `DLL load failed ... procedure could not be found` | Prebuilt engine doesn't match this machine's libraries | Run `./build_backend.sh` to recompile it here (Part 3b) — the standard fix |
| `DLL load failed ... module could not be found` | A runtime DLL truly missing | Add `C:\msys64\ucrt64\bin` to the Windows **System PATH**, reopen the shell; if it persists, run `./build_backend.sh` |
| QuPath Ping: "Could not reach fiber server" | Server not running, or wrong port | Start it (Part 4). Default is `127.0.0.1:5101`; if you changed the port, set it in QuPath preferences (`tmequant.port`) |
| "TACS analysis needs a selected annotation" | No shape selected | Draw and click an annotation first |
| Analysis is very slow or runs out of memory | The region is large / too many fibers | Select a smaller rectangle |
| A `.sh` script gives weird `$'\r'` errors | The file got Windows line-endings | Re-copy the script fresh; don't save it from Notepad |

---

## Quick reference (after first-time setup)

```
# start the server (MSYS2 UCRT64)
cd /f/CTFireTest/fiber_socket_bridge
./start_fire_server.sh

# in QuPath: select a region/annotation, then
#   Extensions > TME Quant > Analyze fibers in selected region...
#   Extensions > TME Quant > Analyze fibers + TACS (boundary = selection)...
```
