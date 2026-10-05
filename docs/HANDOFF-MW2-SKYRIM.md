# Handoff: MW2 guns in SkyCraft, the Skyrim side

For the agent picking up the **SKSE plugin** work. Read [MW2.md](MW2.md) (the design) first, then
this. Written 2026-10-04.

## Where things stand

The MW2 guest works on its own. **IW4L**, the Rust MW2 runtime in `../2010-rust-rewrite-mashup`,
runs hidden. It renders only the gun viewmodel and the MW2 HUD at the size Skyrim asks for, with a
transparent background, and publishes every frame into `Local\SkyCraft_MW2_v1`. The soldier follows
Minecraft's player. Nothing in Skyrim uses the mapping yet. **That is your job.**

| Milestone | State |
|---|---|
| M0: IW4L builds and runs natively on Windows with the user's MW2 | done |
| M1: guest renders the viewmodel and HUD into the shared overlay | done |
| M2, guest half: soldier follows Minecraft (look, walk bob, sprint pose), `State.ready` | done |
| **M2, Skyrim half: create the mapping, start the guest, composite the layer** | **yours** (S1–S5 below) |
| M3: firing (`Control` from Minecraft, `Shot` → Minecraft raycast → NPC damage, `Hit` back) | not started; guest + Fabric, not yours |

Proof that the guest half works, from `tools/test_mw2_guest.ps1` against the fake host:
- 83% of the overlay is fully transparent, and the rest is the gun, hands and HUD.
- The gun's top edge stays within rows 388–393 standing, moves within 389–398 walking (bob), and
  drops to 397–649 while sprinting (MW2's sprint pose).

### Branches and commits (local only)

Don't push. Both remotes belong to chasmlol, the upstream author.

- **`SkyCraft`, branch `mw2`:**
  - design doc `docs/MW2.md`
  - protocol `protocol/skycraft_mw2.h`
  - fake host `tools/fake_mw2_host.py` and the runner `tools/test_mw2_guest.ps1`
- **`2010-rust-rewrite-mashup`, branch `skycraft-guest`:**
  - `crates/skycraft_guest` (the guest plugin)
  - `crates/frame/src/skycraft.rs` (the mode switch)
  - `render_gpu`: `skycraft_alpha.{rs,wgsl}` and the map-hiding change
  - `sim` (`PinnedMotion`)
  - `launcher` (`--skycraft`, `--games`)

**Commit rule (the user's global instruction):** never add AI attribution or `Co-Authored-By`
lines. Commit in small, meaningful steps.

## The contract you implement against

`protocol/skycraft_mw2.h` is the source of truth. The Rust mirror is
`crates/skycraft_guest/src/proto.rs`. If you change the layout, change both and bump `kVersion`.

**Skyrim (you) owns:**
1. **Creating the mapping**, like `Link::Create` does for the main one (`Link.cpp`):
   - pagefile-backed, size `mw2::kMappingBytes`, about 99 MB
   - zero the header and control/state blocks on create, so a stale mapping from an earlier run
     starts clean
   - write `version`, then `magic` last, with release ordering
2. **The header every frame:**
   - `viewportW/H` = the swap chain's back buffer size, which the guest renders at
   - `hostHeartbeatMs` = `GetTickCount64()`
3. **Reading the overlay.** It's a triple buffer under the exact same rules as the main overlay:
   - `state` starts at 0 (middle = 0); the guest's back slot starts at 1, your front slot at 2
   - to read: if `state & kOverlayDirty`, then `old = xchg(state, front)` and `front = old & 3`
   - copy `Link::AcquireOverlayFrame` / `FrontPixels` / `FrontHeader`
4. **Compositing.** Pixels are RGBA8, rows top-down at `width * 4` bytes, and **premultiplied**
   alpha. Blend with `ONE, INV_SRC_ALPHA`, the same `blend` state `Overlay.cpp` already makes.

**The guest owns:**
- `guestPid`, `guestHeartbeatMs`
- `State` at 0x200, under a seqlock (odd while writing). Bit 0 of `flags` = **ready**: a match is
  up and the soldier is spawned.
- The overlay slots.

**Minecraft owns** (not you):
- `Control` at 0x100 (held weapon and buttons). Nothing writes it yet.
- The shot and hit rings.

## Tasks, in order

### S1. Build the plugin as it is (baseline)

Already done for you on this machine, as far as I could take it:
- `skse/extern/CommonLibSSE-NG` is checked out at v10.0.0, with its `openvr` submodule.
- `.tools/vcpkg` is cloned and bootstrapped.
- The baseline build succeeds and deploys (log at the end of this file). It is **not yet run
  in game**.

```powershell
cd SkyCraft\skse
cmake --preset default            # VS 2026 generator, vcpkg toolchain from ..\.tools\vcpkg
cmake --build --preset release    # RelWithDebInfo
```

- **Where the DLL goes:** the post-build step copies `SkyCraft.dll` and `.pdb` into
  `SKYCRAFT_DEPLOY_DIR` = `%LOCALAPPDATA%\ModOrganizer\Skyrim Special Edition\mods\SkyCraft\SKSE\Plugins`.
  That's the user's live MO2 mod.
- **The ini is safe:** the copy covers the DLL and PDB only, never `SkyCraft.ini`.
- **Release backup:** the release DLL is backed up beside it as `SkyCraft.dll.release-0.1.2`.
  SKSE ignores that name.
- **Skyrim must be closed**, or the copy only warns.

**Done when** the DLL builds and copies. Check that `SkyCraft.log` still shows "Minecraft
connected" in game.

### S2. `Mw2Link`: create and serve the mapping

Add new `skse/src/Mw2.cpp`, and declare it in `Game.h` the way the other modules are declared
there (`namespace Mw2 { void Install(); ... }`). Mirror `Link`. Create the mapping in `kDataLoaded`
next to `Link::Get().Create()` in `main.cpp`, gated on the ini (S3). Add these methods:
- `Create`
- `GuestAlive()`: `guestHeartbeatMs` is less than about 3 s old
- `Ready()`: seqlock-read `State.flags & 1`
- `AcquireOverlayFrame`, `FrontPixels`, `FrontHeader`, `ResetOverlay`

Write the header (viewport and heartbeat) from `Overlay.cpp`'s `PresentHook`. It already reads the
back-buffer size there (`DrawOverlay`, `st.viewportW/H`).

**Test without Skyrim first:** `tools/fake_mw2_host.py` plays this role. Read it to see exactly what
the host writes.

### S3. Start and stop the guest

Add an ini section. Suggested defaults:

```ini
[MW2]
bEnable = 0                 ; off unless the player has MW2 and a guest build
sGuestExe =                 ; full path of iw4l.exe
sMw2Path =                  ; the MW2 folder itself (the one with iw4mp.exe and zone\)
sMap = mp_rust              ; the stand-in map that supplies weapons, soldier and HUD
```

Launch it **through Explorer**, like Minecraft. Reuse `OpenFromDesktop(file, args, dir, show)` in
`Launcher.cpp`, which keeps the guest out of MO2's virtual file system and off MO2's
waited-for list. The exact command line:

```
iw4l.exe --skycraft --games "<sMw2Path>" map <sMap> --cmds "wait world; spawn 0; force_match_start"
```

What you need to know about the launch:
- **Flags, not environment variables.** Explorer passes no environment from Skyrim, so the guest
  takes `--skycraft` and `--games` (commit `e6c72bd`).
- **Working directory:** use the folder of `iw4l.exe`. It switches there itself anyway, and writes
  `iw4l-artifacts\` there.
- **No dialogs:** with `--skycraft` it skips its Windows first-run setup.
- **Hidden window:** the guest's window is hidden by itself; pass `SW_HIDE` anyway.
- **Only one guest:** don't start a second one. If `guestPid` is set and `GuestAlive()`, it's
  running.
- **Stopping:** when Skyrim exits, close the guest. Use `OpenProcess(guestPid)` +
  `TerminateProcess` in Skyrim's shutdown path; check how Minecraft is closed in `Launcher.cpp` and
  `Game.cpp` and follow that. The guest does **not** yet exit by itself when Skyrim's heartbeat
  stops (see open items).
- **Start time:** about 15–25 s to a ready soldier (map load and shader warm-up). Start it early,
  like Minecraft in `SKSEPluginLoad`, or at `kDataLoaded`.

`iw4l.exe` for now: `..\2010-rust-rewrite-mashup\target\play\iw4l.exe`. Build it with:

```powershell
cd ..\2010-rust-rewrite-mashup
$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"   # rustup was installed without touching PATH
cargo build --profile play -p launcher                  # about 12 min clean, under 1 min incremental
```

### S4. Composite the MW2 layer

In `Overlay.cpp`, give the MW2 layer its own texture and SRV: the `EnsureTexture` /
`UploadLatestFrame` pattern again, keyed to the MW2 link. Draw it in `PresentHook` **after**
`WorldRender::Draw` and **before** `DrawOverlay`, so Minecraft's hotbar and screens stay on top.

**Draw it only when all of these hold:**
- the guest is alive and `Ready()`
- not `st.skyrimMenuOpen`
- `st.mcInWorld`
- later (M3), Minecraft's `Control.weapon != 0`. Until Minecraft writes `Control`, show it whenever
  ready, so it can be seen.

**Shader details:**
- **Reuse `PSMain`** with `cursorOn = 0`, `flipY = 0` and an empty `invertRect`, or write a minimal
  pixel shader. Same `blend` state.
- **Sampler:** the overlay can be smaller than the back buffer if you later let the guest render
  at reduced size. Use a **linear** sampler for this layer (the MC layer uses point, deliberately,
  for crisp GUI pixels).
- **Save and restore** the pipeline state exactly as `DrawOverlay` does.

**Done when** the MG4 and heartbeat sensor sit in Skyrim's view, bob when walking, and drop into the
sprint pose when sprinting in Minecraft. Then take a screenshot.

### S5. Verify in game, then commit

- **Logs:**
  - Skyrim: `Documents\My Games\Skyrim Special Edition\SKSE\SkyCraft.log`
  - guest: `..\2010-rust-rewrite-mashup\target\play\iw4l-artifacts\logs\latest.log` (look for
    `SkyCraft: connected`, `overlay target WxH`, `following Minecraft's player`)
- **Launching Skyrim:** through MO2, profile `SkyCraft`, executable `SKSE`. **The user declined
  screen control for the Skyrim launcher once.** Ask before driving the game with computer use, or
  ask the user to play and report back.
- **Commit** S2–S4 as separate commits on `mw2`.

## Pitfalls already paid for

- **`IW4L_GAMES` / `--games` must be the MW2 folder itself**, not its parent. `iw4l.exe` does not
  read the `.env` beside it; that's the launcher shell's job.
- **The guest keeps the map's static models in the colour pass.** With neither world nor static
  model draws left, IW4L's colour pass also drops the viewmodel. They're masked out by depth in
  `skycraft_alpha.wgsl`, using reverse Z where the viewmodel band is above `1 - 0.015625`.
  Don't "fix" that by hiding static models again.
- **Faint red dots** near the screen centre are `mp_rust`'s glow-stick effects at the soldier's
  spawn. They're harmless, and will go once shots and effects are mapped.
- **Bandwidth:** the overlay is a CPU copy every frame. The user's back buffer is **2880×1800**, so
  that's about 20 MB per frame, and the guest renders at 140+ fps at 720p. If Skyrim's frame time
  suffers, the knobs are:
  - render the guest at a fraction of the viewport (write a smaller `viewportW/H`, sample with the
    linear sampler)
  - or cap the guest's frame rate
  - shared GPU textures (DXGI shared handle) are the real fix, later
- **Python on this machine is `python`**, never `python3`. The user's terminal is PowerShell.

## The user's setup

| | |
|---|---|
| Skyrim | Steam, 1.7.104, `C:\Steam\steamapps\common\Skyrim Special Edition`. SKSE 2.3.1 installed there; an old `skse64_1_6_1170.dll` is still lying beside it. |
| MO2 | `C:\Modding\MO2\ModOrganizer.exe`, instance `%LOCALAPPDATA%\ModOrganizer\Skyrim Special Edition`, profile **`SkyCraft`**: SkyCraft, Skyrim Unbound Reborn (alternate start), SkyUI, Address Library v13. PapyrusUtil is disabled (its version predates 1.7.104). The `Default` profile is the user's own modlist; leave it alone. |
| SkyCraft ini | Points `sLauncher` at the user's own Prism (`%LOCALAPPDATA%\Programs\PrismLauncher\prismlauncher.exe`, instance `SkyCraft`: MC 26.3, Fabric 0.19.5, Fabric API, skycraft-0.1.2.jar, Java 25). |
| MW2 | `C:\Steam\steamapps\common\Call of Duty Modern Warfare 2`, both singleplayer (10180) and multiplayer (10190). |
| Rust | 1.99 stable via rustup at `%USERPROFILE%\.cargo\bin` (not on PATH). |
| C++ | VS 2026 Community, CMake. |
| Skyrim result | SkyCraft 0.1.2 works: Minecraft connects and drives the player. One run logged 13 fps over its first 74 s, including a 35 s hitch while Minecraft loaded. Compare against that when you measure the MW2 layer's cost. |

## Open items, not yours unless you finish early

- **Guest:** exit when Skyrim's `hostHeartbeatMs` goes stale. The fake host's heartbeat must then
  use `GetTickCount64` via ctypes; it uses `time.monotonic` today.
- **M3 (guest + Fabric):**
  - Fabric MW2 items, which write `Control` and hide Minecraft's hand while held
  - guest presses `+attack`, `+speed_throw`, `+usereload` from `Control`, the way `drive_soldier`
    presses sprint
  - guest emits `Shot` from `advance_weapon_command`'s shots (`sim/src/step.rs`)
  - Fabric raycasts and applies damage through the existing `HitActor` path, then writes `Hit`
- **Later Skyrim-side work in MW2.md:**
  - §7 portal home: a `Takeover` field, so Skyrim pauses and hands focus to the Minecraft or MW2
    window
  - §8 Shepherd: a Skyrim NPC the guest dresses in MW2's model, which needs a depth-tested guest
    layer

## Baseline build log

2026-10-04, on branch `mw2` before any SKSE changes:
- vcpkg bootstrap, `cmake --preset default` and `cmake --build --preset release` all exit 0.
- **What was built:** `skse\build\RelWithDebInfo\SkyCraft.dll` (4,040,192 bytes) and its PDB.
- **Where it went:** both were deployed to the MO2 mod folder, replacing the release DLL, which is
  kept as `SkyCraft.dll.release-0.1.2`.
- **Not yet run in game.** The first in-game check of S1 is still to do. If it misbehaves, restore
  the release DLL and compare.
