# Installer & launcher internals (maintainer notes)

Everything we learned wiring up the Windows install/launch path, so future edits don't
re-introduce the same bugs. **Read the "Golden rules" before touching any `.bat`.** Most of
these were paid for the hard way.

---

## The moving parts

```
QuPath (extension)                         Windows / MSYS2
  ServerLauncher.ensureReachable()
     ping 127.0.0.1:5101
        down? ──ProcessBuilder──▶  tmequant_server.bat   (cmd.exe)
                                      │  finds/installs MSYS2 (asks first)
                                      │  cd /d "%~dp0."
                                      ▼
                                   msys2_shell.cmd -ucrt64 -defterm -no-start -here -c
                                      "./tmequant_boot.sh <port>"      (MSYS2 UCRT64 bash)
                                      │  marker-gated first-run setup
                                      ├─▶ pacman -Syu / -Su (first run)
                                      ├─▶ ./setup_fire_server.sh  (venv + deps + backend)
                                      └─▶ ./start_fire_server.sh  (runs fiber_socket_server.py)
                                                                      binds 127.0.0.1:5101
```

The split matters: **cmd does as little as possible**, then hands off to **bash** as early as
possible, because cmd's quoting is the main source of breakage.

---

## Files and roles

| File | Runs in | Role |
|---|---|---|
| `server/tmequant_server.bat` | cmd.exe | Entry point. Locate/ask-to-install MSYS2, then launch the UCRT64 shell. **Keep cmd logic minimal.** |
| `server/tmequant_boot.sh` | MSYS2 bash | First-run setup (marker-gated) then launch. **All real logic lives here, not in the `.bat`.** |
| `server/setup_fire_server.sh` | MSYS2 bash | One-time: pacman packages, create `venv_fire`, validate/rebuild the FIRE `.pyd`. Checks for `../tme-quant`. |
| `server/start_fire_server.sh` | MSYS2 bash | Per-session launch of `fiber_socket_server.py` (port arg). Checks UCRT64 + venv + repo. |
| `server/fiber_socket_server.py` | venv python | The socket server. Binds `127.0.0.1:5101`; graceful synthetic fallback. |
| `server/build_backend.sh` / `diagnose_backend.sh` | MSYS2 bash | Rebuild / debug the compiled backend. |
| `ServerLauncher.java` | QuPath JVM | Ping, auto-launch the `.bat`, poll-until-ready, locate-launcher prompt, synthetic warning. |
| `.gitattributes` | git | Enforces CRLF for `*.bat/*.cmd/*.ps1`, LF for `*.sh`. **Critical.** |

---

## Golden rules (do not break these)

1. **`.bat`/`.cmd`/`.ps1` must be CRLF; `.sh` must be LF.** Authoring on Linux/Mac defaults to LF.
   An LF `.bat` makes cmd misparse `if (...)` blocks → the window **closes instantly with no
   readable error**. An MSYS2 `.sh` with CRLF fails with `\r`-mangled "command not found".
   `.gitattributes` enforces this, but **verify after any scripted edit** (`grep -q $'\r' file`).
2. **Keep the `msys2_shell.cmd -c "..."` string trivially simple.** Currently just
   `"./tmequant_boot.sh %PORT%"`. **Never** put `$(...)`, escaped quotes (`\"`), braces `{}`,
   semicolons `;`, or parentheses inside it — cmd mangles them (see gotchas). Put logic in
   `tmequant_boot.sh` instead.
3. **Don't `cd` to a path that ends in a backslash inside quotes.** `%~dp0`/`%HERE%` end in `\`,
   so `cd /d "%HERE%"` becomes `cd /d "...\""` where `\"` escapes the quote → syntax error. Use
   **`cd /d "%~dp0."`** (trailing dot).
4. **Never silently install to `C:` or assume write access.** Always ask; honor `MSYS2_ROOT`.
5. **Every error path must `pause` before `exit`** so a double-clicked window stays open.
6. **Test the cmd-side changes on real Windows.** They cannot be tested from the Linux dev box
   (no cmd.exe/winget/MSYS2). The Python and bash logic can be `bash -n`/`ast.parse`-checked.

---

## Gotchas, with symptom → cause → fix

### "The syntax of the command is incorrect."
- **Inline bash one-liner in `-c`.** Symptom: error right after the banner echoes. Cause: cmd
  mis-parses `{ ... }; ... ( ... )` / `&&` / `;` inside the `-c "..."`. Fix: move it into
  `tmequant_boot.sh`; keep `-c` to a single script call.
- **`cd "$(cygpath -u '%HERE%')"` inside `-c`.** Same symptom. Cause: the `\"` toggles cmd's
  quote state off, exposing `$(` to cmd. Fix: don't `cd` inside `-c` at all — `cd /d "%~dp0."`
  in cmd first, then rely on `msys2_shell.cmd -here`.
- **`cd /d "%HERE%"` (trailing backslash).** Same symptom. Cause: `"...\""`. Fix: `cd /d "%~dp0."`.

### Window opens then closes instantly (no text)
- Cause: LF line endings in the `.bat` (rule 1), **or** an `exit /b` reached before a `pause`.
  Fix: CRLF; ensure every error branch pauses. Diagnostic for the user: run the `.bat` from an
  **already-open** Command Prompt so output persists.

### Isolating a cmd-vs-MSYS2 failure (user-run diagnostic)
Have the user run the bare MSYS2 call from their shell:
```
C:\msys64\msys2_shell.cmd -ucrt64 -defterm -no-start -here -c "echo hello"
```
Prints `hello` → MSYS2 invocation is fine, the bug is in the surrounding cmd. Errors → the call
form itself is the problem on their machine.

### `msys2_shell.cmd` flags
`-ucrt64` (sets `MSYSTEM=UCRT64`, required for the compiled `.pyd` to load), `-defterm` (use the
current console, no mintty), `-no-start` (don't relaunch), `-here` (use the current directory),
`-c "<cmd>"`. The wrong subsystem (MSYS/MINGW64) → backend falls back to synthetic.

### echo and parentheses
Inside a parenthesized block (`if (...)`/`else (...)`) you must escape literal parens as `^(`
`^)`. At top level (goto/label style) you don't. The MSYS2-choice section is goto-based on
purpose so `choice`/labels parse cleanly and parens in echo are safe.

### `choice` + `goto`
We use `choice /c IMQ` + `if errorlevel 3 goto ...` (check **highest first**: 3 then 2 then fall
through to 1). Labels (`:have_msys2`, `:install_manual`, `:user_cancel`, `:msys2_ok`) must each be
defined once and each path must `goto`/`exit` so control doesn't fall into the next label.

---

## MSYS2 location

- Default `C:\msys64`; override with the **`MSYS2_ROOT`** env var. This is the **only** thing that
  depends on the install location — the `.sh` scripts and server are location-agnostic (relative
  to the repo).
- The `.bat` **asks** before installing: `[I]` winget→`MSYS2_ROOT`, `[M]` user installs anywhere,
  `[Q]` quit.
- **`winget --location` is unreliable for the MSYS2 package** — it may install to `C:\msys64`
  regardless. The `.bat` attempts `--location`, then falls back to a default install and detects
  `C:\msys64`. For a guaranteed non-`C:` install, the **`[M]` GUI-installer path is dependable**.
- **Blocked write / un-grantable UAC**: winget fails; the `.bat` does **not** crash — it explains
  (permissions / admin) and points to `[M]` + a user-writable folder (no admin needed) + `setx`.

---

## QuPath auto-launch integration

- `ServerLauncher.launch()` (Windows): `new ProcessBuilder("cmd","/c","start","","<bat>")` so the
  server gets its **own console** (visible startup banner) and is detached. Non-Windows fallback:
  `bash <script>` so the dev box compiles/behaves.
- `ensureReachable()` pings first (short timeout); if down + launcher configured + auto-launch on,
  it launches and `pollUntilReady()` pings every 2 s up to **120 s** (the first backend load is
  ~30–40 s). No launcher set → prompt "Locate launcher…" (saves the pref).
- **Env-var inheritance gotcha:** the spawned `.bat` inherits **QuPath's** environment, captured
  when **QuPath started**. So a non-default `MSYS2_ROOT` must be a *persistent* user var (`setx`)
  set **before** launching QuPath, and QuPath must be **restarted**. A per-session `set` won't
  reach the auto-launch. (Alternative: hardcode the `set "MSYS2_ROOT=..."` default in the `.bat`.)
- Prefs involved: `tmequant.serverLauncher` (path), `tmequant.autoLaunchServer` (bool), surfaced
  in QuPath *Preferences ▸ TME Quant* and the *Configure fiber server launcher…* menu item.

---

## First-run setup & idempotency

- Gated by the marker file **`.tmequant_setup_ok`** in `fiber_socket_bridge/` (gitignored). Absent
  → run `pacman -Syu` + `pacman -Su` (two passes; first MSYS2 update may need it) + `setup_fire_server.sh`;
  on success `touch` the marker. Present → skip straight to launch.
- `setup_fire_server.sh` is self-healing: if the prebuilt `.pyd` doesn't load it rebuilds from
  source (`build_backend.sh`). It hard-checks for `../tme-quant/src/ctfire_py` and stops with a
  clear message if the pipeline folder is missing.

---

## Server runtime guardrails (`fiber_socket_server.py`)

- **Port in use:** `bind()` is wrapped — `OSError` → actionable message ("another server likely
  running / change the port") + `SystemExit(3)`, not a traceback.
- **Backend load failure:** degrades to a synthetic OpenCV detector (`_BACKEND="synthetic"`),
  doesn't crash. `ServerLauncher` warns the user when a connected server reports `synthetic`
  (FIRE params have no effect → results meaningless) and points at `diagnose_backend.sh`.
- Loud "PLEASE WAIT … loading the FIRE pipeline" banner before the slow import.

---

## Release packaging

- Two assets on the GitHub Release: `qupath-extension-tme-quant-<ver>-all.jar` and
  `tmequant-server-<ver>.zip`.
- The server zip is built from `server/` but staged under a top-level **`fiber_socket_bridge/`**
  folder so it extracts to the documented layout (`CTFireTest\fiber_socket_bridge\` next to
  `tme-quant\`). It **excludes** the compiled `.pyd`/`.so`, the `tme-quant` pipeline, venvs
  (gitignore + not copied). The internal dev `CHANGELOG.md` is intentionally **not** shipped.
- Rebuild + republish:
  ```bash
  ./gradlew shadowJar                        # if Java changed
  rm -rf /tmp/pkg && mkdir -p /tmp/pkg/fiber_socket_bridge && cp server/* /tmp/pkg/fiber_socket_bridge/
  ( cd /tmp/pkg && zip -rq tmequant-server-<ver>.zip fiber_socket_bridge/ )
  gh release upload v<ver> build/libs/...-all.jar /tmp/pkg/tmequant-server-<ver>.zip --clobber
  ```

---

## Pre-edit checklist (before changing a `.bat` or a `.sh`)

- [ ] Did I keep the `msys2_shell.cmd -c` string to a single simple script call? (rule 2)
- [ ] Any `cd` to a `%~dp0`/`%HERE%` path → use `"%~dp0."`, not `"%HERE%"`. (rule 3)
- [ ] Line endings: `.bat` CRLF, `.sh` LF — re-verify after scripted edits. (rule 1)
- [ ] Every error branch `pause`s before `exit`. (rule 5)
- [ ] `bash -n tmequant_boot.sh` / `python3 -c "import ast; ast.parse(open('fiber_socket_server.py').read())"`.
- [ ] Rebuilt the server zip and re-uploaded to the Release (the repo `server/` is the source).
- [ ] Flag that cmd/winget/MSYS2 behavior is **untested from the dev box** — verify on Windows.
