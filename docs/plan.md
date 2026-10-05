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

## 1. Firing and ADS in game ✓

Verified in game 2026-10-05 (Whiterun; Nazeem). Damage tuning lives in `fabric/.../mw2/Mw2Guns.java`
(`MW2_TO_MC_DAMAGE`, `HEAD_SHARE`).

## 2. Random water in Whiterun (fix built, verify in game)

**Cause:** `skse/src/Game.cpp` `WriteWaterGrid` asked each column's cell `GetWaterHeight`. That is one
flat level per cell, so a stream or pool anywhere in a cell made its lower dry ground read as water.

**Fix (commit after `06983f6`):** the grid now comes from Skyrim's placed water surfaces
(`RE::TESWaterSystem::waterObjects`).
- Each surface's height is its plane mesh's bound centre z.
- Its footprint is its multibound AABBs, or else its bounding circle.
- The cell height is used only when Skyrim reports no surfaces at all.
- `SkyCraft.log` logs `water: N placed water surfaces loaded` when the count changes.

**In game (2026-10-05): better, not fixed.** Random swimming is rarer but still happens in Whiterun.
Don't change the code until the diagnostics below say which surface floods.

**What the logs show** (`SkyCraft.log`, 00:17–00:19):
- **Many surfaces:** 77–103 placed water "surfaces" are counted around Whiterun. The log counts
  patches: one per multibound AABB, or one bounding circle per object without AABBs.
- **The count keeps changing:** it moves every few seconds, even within one area (for example
  78 → 80 → 79 → 78). Water objects come and go as the view moves (culling, LOD or distant water).
  A column's water can flicker, which reads as "random".
- **No location:** neither Skyrim's nor Minecraft's log says where the player was when swimming,
  so the offending surface can't be identified yet.

**Diagnosed (00:27–00:28 run, `bWaterDiagnostics`).** All 7 wet spots were in cell `0001A276`
`WhiterunPlainsDistrict04`, under one surface. Skyrim said `swimming false` every time, and the cell
has `hasWater false`.

```
1 of 78 placed surfaces cover it: h -3264 (+9..+141 vs feet) box centre (24811, -6335) half 428 x 564
  plane n=(0,0,1) c=-3008 | waterType 00085FC8 | flags 05 | shape 'Water'
```

- **Terraced water:** the plane says −3008, but the mesh's bound centre is −3264, 256 lower. This is
  the stream stepping down Whiterun's terraces, one mesh spanning several levels. Neither single
  height is right everywhere.
- **Box too big:** the multibound AABB (856 × 1128 units) is much bigger than the narrow channel, so
  the dry streets beside it count as covered.

**Fix to build:** per-column water from the actual water geometry.
- **Option A:** the shape's world-space triangles. Point-in-triangle for the column, then interpolate
  the z. Check `BSTriShape` keeps CPU vertex data (`BSGeometry::GetGeometryRuntimeData`, the
  vertex desc). If it's GPU-only, try B.
- **Option B:** Skyrim's own water. A Havok raycast down per column against water collision volumes,
  or `RE::Actor::GetSubmergedWaterLevel` style checks. Skyrim's `swimming` was right every time.
- **Interim mitigation:** require the column's water height to be at most a few units below
  `plane.constant` and above Skyrim's land height there. It doesn't solve terraces by itself.

**Earlier suspects, before the diagnosis:**
1. **Cell planes are back:** `TESWaterSystem::waterObjects` probably includes the flat water planes
   Skyrim builds from each cell's water height (exterior cells flagged `kHasWater`, and the
   `WhiterunWorld` default). That brings back the old one-level-per-cell flood through objects.
   Check each object's `waterType` (editor id) and whether its plane covers a whole cell (4096
   units).
2. **Circles reach too far:** objects without multibounds use the shape's bounding sphere, whose
   radius is half the plane's diagonal. For a cell-sized square that reaches about 40% past each
   edge, onto neighbouring dry cells.
3. **Waterfalls and sloped water:** the height is the bound centre's z. For a non-flat shape that's
   mid-height, so the water reads too high on its lower part. `TESWaterObject::plane` (normal,
   constant) would give the real level for flat ones.
4. **Flicker:** objects that drop out of the list for a frame leave a column alternately wet and dry.
   Holding a surface for about a second after it disappears would hide that, but find the cause
   first.

**Diagnostics built (2026-10-05):** `[Debug] bWaterDiagnostics = 1` in `SkyCraft.ini`. It's on in the
live MO2 ini and off in the repo's template; the code is in `skse/src/Game.cpp` `LogWetColumn`.

Whenever the water Minecraft is given rises over the player's feet, `SkyCraft.log` gets one line:

```
water diag: wet at (x, y, z) cell <id> '<edid>' hasWater <b> cellWaterHeight <h> swimming <b> | N of M placed surfaces cover it: ;; h <height> (<+/- vs feet>) box|circle centre (x, y) half WxH|r R | plane n=(...) c=<constant> | waterType <id> '<edid>' | flags <xx> | shape '<name>' ;; ...
```

It logs again after moving about 7 blocks while still wet, and logs `water diag: dry again` on
leaving the water.

**To diagnose:** walk Whiterun's dry streets until it swims you, then read those lines. The patch
with the highest `h` is the one flooding. Its kind (circle or box), `waterType`, shape name and
`plane` show which suspect below it is. Compare `swimming` (Skyrim's own idea) and
`cellWaterHeight`.

**Original plan for the diagnostics (now built as above):** when Minecraft's player is
in Skyrim water but Skyrim's player isn't, log the column once.
- **Minecraft side:** the player is in water when `SkyWater.depthIn` > 0 at the feet. Report it back
  through a Minecraft → Skyrim event, or have Skyrim compute it from the grid it just wrote.
- **Skyrim side:** "isn't" means `RE::Actor::IsSwimming()` is false, or the water height is below
  Skyrim's land height there.
- **The log line:**
  - the player's Skyrim position, the cell's editor id, its `kHasWater` flag and `GetWaterHeight`;
  - every patch covering the column: height, centre, extents, AABB or circle, the object's
    `waterType` editor id, `flags`, and the shape's name.

Then compare with where water visibly is. Expected fix: skip the cell-level planes (or require a
`waterType` that isn't the worldspace default), use the shape's triangles instead of its bounding
circle, and use the plane's level for flat water.

**Separately, firing in the same run looked right:**
- A sniper headshot on a level-20 Whiterun Guard: 56 Minecraft → 560 Skyrim, critical, stagger.
- UMP/SPAS hits: 8–11 Minecraft → 80–112 Skyrim.

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

## 6b. Inventory character (fixed, plus a wish)

- **Fixed:** the soldier was missing from the inventory's character box after `d87aed6`. It was
  placed against the hidden window's size while the box is in the HUD's (image) size; fixed in the
  guest after `d87aed6`.
- **Wish:** show the *Minecraft* player there (skin and armour), not the MW2 soldier. The guest draws
  the soldier in that box.
  - **Option 1:** Minecraft renders its own player preview
    (`InventoryScreen.extractEntityInInventoryFollowsMouse`) into its overlay at the box's rectangle.
    The guest publishes `character_box` in the MW2 mapping, and `Mw2InventoryScreen` draws the
    entity there. It's in Minecraft's layer, which sits over MW2's except in the hotbar band, so it
    shows over the panel.
  - **Option 2:** the guest skips the puppet (`InventoryPuppet.active = false` in guest mode).

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
