# SkyCraft plan: what's next

Written 2026-10-04 at the end of the MW2-guns session. Read [MW2.md](MW2.md) (the design) first.
[HANDOFF-MW2-SKYRIM.md](HANDOFF-MW2-SKYRIM.md) is history now: all of its S1–S5 are done.

## Where things stand

MW2's guns work in SkyCraft, end to end, built and deployed on this machine:

| Piece | State |
|---|---|
| **Guest:** IW4L (`../2010-rust-rewrite-mashup`, branch `skycraft-guest`) runs hidden, started by Skyrim | ✓ in game |
| **Layer:** the gun and MW2's HUD composited into Skyrim | ✓ in game |
| **Inventory:** the hotbar and inventory are the mashup's MW2-style ones, drawn from Minecraft's real inventory, with the soldier in the inventory | ✓ in game |
| **Hotbar sizing:** sized like Minecraft's; Minecraft's health, hunger and XP sit on it | ✓ in game |
| **Firing:** shots → Minecraft's server traces them → Skyrim NPCs take MW2 damage → hitmarker | ✓ fake host; **in game untested** |
| **ADS:** Minecraft's FOV (and so Skyrim's camera) zooms with MW2's ADS; MW2's scope overlay | ✓ fake host; **in game untested** |
| **F5:** no first-person gun in third person | built; **in game untested** |

### Controls with a gun in hand

- **Fire:** left click.
- **Aim down sights:** right click.
- **Hold breath:** Shift (scoped).
- **Reload:** R, a new Minecraft key.

### Commits

Local only; both remotes are chasmlol's, so don't push.

- **SkyCraft `mw2`:** `13cd20e`..`188b468`
- **Mashup `skycraft-guest`:** `eb97a1d`..`d87aed6`

### Protocol

`protocol/skycraft_mw2.h`, v5, is the source of truth. Mirrors:
- `fabric/src/main/java/dev/skycraft/link/Mw2Proto.java`
- `crates/skycraft_guest/src/proto.rs`

Bump `kVersion` on all three together.

## Build, deploy, test

```powershell
# Skyrim plugin (deploys the DLL to the MO2 mod; Skyrim must be closed)
cd SkyCraft\skse; cmake --build --preset release

# Fabric mod: Java 25 is Prism's; copy the jar into the Prism instance (Minecraft closed)
cd SkyCraft\fabric; $env:JAVA_HOME = "$env:APPDATA\PrismLauncher\java\java-runtime-epsilon"; .\gradlew build -x test
Copy-Item build\libs\skycraft-0.1.2.jar "$env:APPDATA\PrismLauncher\instances\SkyCraft\minecraft\mods\" -Force

# Guest (about 1.5 min incremental)
cd 2010-rust-rewrite-mashup; $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"; cargo build --profile play -p launcher

# Guest against the fake host: no Skyrim or Minecraft needed. It walks, sprints, picks each hotbar
# slot, opens the inventory and clicks, aims and quickscopes, and saves a PNG per step.
powershell -ExecutionPolicy Bypass -File SkyCraft\tools\test_mw2_guest.ps1 -Iw4l <iw4l.exe> -Out <dir>\x.png -Seconds 140 -Width 2880 -Height 1800
```

### Seeing what the player sees

Minecraft's F2 screenshots miss the MW2 layer. Create
`Documents\My Games\Skyrim Special Edition\SKSE\skycraft_capture.request`. Skyrim then saves its next
final frame, with both layers, as `skycraft_capture.png` next to it.

### Logs

- **Skyrim:** `SkyCraft.log` in that SKSE folder.
- **Guest:** `2010-rust-rewrite-mashup\target\play\iw4l-artifacts\logs\latest.log`. It logs its frame
  rate every 600 frames.
- **Minecraft:** the Prism instance's `logs\latest.log`.

### Backups and settings

- **Release builds:** kept beside the live files, under names the loaders ignore:
  - `SkyCraft.dll.release-0.1.2` (MO2 mod `SKSE\Plugins`)
  - `skycraft-0.1.2.jar.release` (Prism `mods`)
- **The live `SkyCraft.ini`:** has `[MW2] bEnable = 1`. The original is `SkyCraft.ini.before-mw2`.

## 1. Verify firing and ADS in game (next session, first)

Not yet seen in game. Whiterun works; Nazeem is a fine target. Check:

- **Left click:** fires without swinging Minecraft's arm.
- **Right click:** zooms Skyrim's actual view, and the scope overlay covers the whole screen. It
  covered only the top ⅔ before `d87aed6`: the HUD was laid out at the hidden window's size, now
  at the image's.
- **Hits:** the target takes damage, staggers on heavy shots, and the hitmarker and its sound play.
  Crime and aggro come through Skyrim's normal hit path.
- **F5:** no first-person gun.

If damage doesn't land:
- Minecraft's log should show `SkyCraft: hit <name> for <n>` per tick of hits.
- In the guest's log, look for `shot ring is full`.

Damage: an Intervention torso shot is 105 in MW2 terms ÷ 5 = 21 Minecraft, which comes to about
160 Skyrim damage on a level-10 NPC. All in `fabric/.../mw2/Mw2Guns.java` (`MW2_TO_MC_DAMAGE`).

## 2. Random water in Whiterun (bug)

**Symptom:** the player swims in places with no water.

**Likely cause:** `skse/src/Game.cpp` `WriteWaterGrid` asks each column's cell
`TESObjectCELL::GetWaterHeight`. That is one flat water level per cell. A cell with any water in it
(a stream, the Gildergreen pool) reports that level across the whole cell, so dry ground lower in
that cell reads as under water. Minecraft (`fabric/.../world/SkyWater.java`) then swims the player.

**Fix:**
- **Quick:** sample only where Skyrim's own water is. Query the water objects of
  `RE::TESWaterSystem` (their placed planes and bounds) instead of the cell height.
- **Or:** ray down from the water height and require the water plane's reference near the column.
- **Before that, confirm:** log the cell, its water height and `cellFlags` (`kHasWater`) where it
  happens.

## 3. Skyrim saves ↔ Minecraft state (feature)

**Today:** one Minecraft world (`SkyCraft`) serves every Skyrim save. Loading an older save keeps
Minecraft's current inventory, blocks and health.

**Design:**
- **SKSE:** use the co-save (`SKSE::GetSerializationInterface`). Store a SkyCraft save id (a GUID
  made the first time) in each Skyrim save.
- **Protocol:** Skyrim tells Minecraft the id on `kPostLoadGame`/`kNewGame` (`Game::OnGameLoaded`).
  It's a new field in `SkyState`, or an input event.
- **Minecraft:** keep per-id player data and per-id block storage.
  - **Simplest:** one world folder per id, `SkyCraft-<id>`. `MirrorWorld` opens it, so switching
    saves switches worlds, like leaving to the title screen.
  - **Lighter:** one world, with the player's NBT and the placed-block sections stored per id.
- **Saving:** when Skyrim saves, Minecraft saves too (`server.saveEverything`), so the two stay in
  step. Hook Skyrim's save, `BGSSaveLoadManager::Save`, or the `kSaveGame` message.

## 4. One inventory (feature, large)

**Recommendation:** keep Minecraft's inventory as the master. It is already the one shown (MW2's
style), and its rules (stacks, crafting) are the engine's.

**Map Skyrim items into it as Minecraft items:**
- **Registry:** one `skycraft:skyrim_item` item with a data component carrying the Skyrim form id.
  The guest's icons need a renderer for these, or Skyrim's own icon or 3D thumbnail.
- **Weapons and armour:** equipping one sets Skyrim's equipped item, for the visible body and NPC
  reactions. Damage goes through SkyCraft's existing combat.
- **Gold:** an item stack, or a counter on the HUD.
- **Pickups:** Skyrim's activation of a container or item → Minecraft adds the item.
- **Dropping:** Minecraft dropping one → Skyrim places the reference.

**Plan for it:** a big job, with save correlation (§3) first. Skyrim's own inventory menu stays
hidden; DESIGN.md §13 keeps only the journal and compass.

## 5. Guns in third person (feature)

**Today:**
- **Gun gone from first person:** in F5 the guest now hides its first-person gun (`d87aed6`).
- **What Steve holds:** a vanilla crossbow model (`188b468`) instead of the missing-model cube.

**Proper fix:** real gun item models.
- **Approach:** convert MW2's world models (`weapon_cheytac`, `weapon_spas12`, `weapon_ump45`) into
  Minecraft item models (JSON cuboids or a baked OBJ with Fabric's model API), textured from MW2's
  images. IW4L's `xmodel_runtime` and `asset_material` can read them.
  - **Licence:** the result must not ship. Generate it on the player's machine from their own MW2,
    like the guest's assets.
  - **Generator:** a guest-side `--export-gun-models <dir>` writing a resource pack into the Prism
    instance's `resourcepacks`.
- **Hand pose:** third-person body animation, in Minecraft's `ItemInHandLayer`. Point the gun the
  way the player aims; crossbow-style arms already do that for crossbows.

## 6. General Shepherd and the Ender Dragon (feature, large)

See MW2.md §8. A dungeon instead of the Throat of the World is simpler: an interior cell, so the
dragon's flight path is bounded.

- **Dragon:** Minecraft's own entity, drawn by SkyCraft's entity capture.
  - Needs a fight origin and height probes that see Skyrim's geometry; the mirror world has no
    blocks.
  - Needs a longer draw range than `AvatarExporter.SCENE_RANGE`.
  - Its attacks reach Skyrim NPCs through the proxy → `HitActor` path.
  - End crystals are blocks.
- **Shepherd:** a Skyrim NPC owns his AI and body.
  - The guest draws his MW2 body at the NPC. That needs a second guest layer, depth-tested against
    Skyrim's depth, or a world-space quad.
  - It fires his .44 Magnum through MW2's weapon code, with shots from the guest's second soldier
    into Minecraft.
  - It plays his lines from the player's own singleplayer files (zone `common`, aliases
    `shep_*`/`gen_shepherd_*`).
- **Trigger:** a Skyrim quest stage or a placed activator in the dungeon. Start with a console
  command.

## 7. Smaller follow-ups

- **Effects in Skyrim:** no muzzle flash, tracers or impacts there.
  - Hits come back with points and kinds (`Mw2Hit`). Skyrim could play an impact (`BGSImpactDataSet`)
    there.
  - The guest could draw tracers. Its FX are suppressed with the bullet trace.
- **Hit sounds:** Skyrim plays NPC hits as arrow hits (`Proto.WEAPON_ARROW`). Add a bullet weapon
  class with its own impact sound set.
- **Ammo:** the guest keeps clips full (the Minecraft map's rule, `sim::item::sustain_block_world_arsenal`
  via `carry_block_world_guns`), so reloads rarely happen. For real ammo, skip the clip refill when
  `CARRY_BLOCK_WORLD_GUNS` is set.
- **Ammo counter:** while a block is held it shows the last gun's ammo.
- **Hold breath:** sneak is hold breath, but it also crouches Minecraft's player.
- **Melee:** no knife yet. `BTN_MELEE` exists; bind it.
- **Offhand shield:** hidden in first person while an MW2 gun or empty hand is up
  (`Mw2HandMixin` cancels the whole hand render).
- **Guest lifetime:** the guest dies with Skyrim through a job object. It should also exit when
  `hostHeartbeatMs` goes stale, because a guest started before Skyrim crashed outlives it.
- **Performance:**
  - Each frame is a CPU copy of up to 20 MB (2880×1800).
  - `fRenderScale` in `[MW2]` shrinks it.
  - The real fix is a shared D3D11/Vulkan texture.
  - The guest renders one frame per Skyrim frame (paced on `hostFrame`).
- **Skyrim's `HitActor` and kills:** Minecraft can't tell a kill (Skyrim owns health), so no kill
  confirm. The actor table could carry health after the hit.
- **Multiplayer:** guns can't hit other players (friendly fire off). A friend's guest needs their
  own Skyrim plugin.
