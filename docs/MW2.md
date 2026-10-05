# SkyCraft — MW2 guns (design)

> Modern Warfare 2's guns in SkyCraft: hold an Intervention in your Minecraft hotbar, and Skyrim
> shows MW2's own viewmodel, recoil, ADS, reload and HUD. The bullets hit Skyrim NPCs and your
> Minecraft blocks.

Status: draft v0.1 · 2026-10-04 · branch `mw2`

## 1. Principle

This follows the same rule as SkyCraft (DESIGN.md §1): **no game is rewritten, they only translate.**
MW2 is the third game. It is played by **IW4L**, the Rust MW2 runtime from
`2010-rust-rewrite-mashup`, which reads the player's own MW2 files. IW4L runs MW2's weapon code
(fire rate, spread, kick, sway, bob, ADS, reload, viewmodel animation, sounds, HUD). Minecraft stays
authoritative for the player's body and inventory, and Skyrim stays authoritative for the world.

```
 Skyrim.exe (SKSE)              javaw.exe (Minecraft + Fabric)        iw4l.exe --skycraft (guest)
 ─ creates both mappings        ─ player physics, inventory           ─ MW2 weapon sim (weapon_iw4)
 ─ launches MC and the guest    ─ MW2 guns are MC items               ─ soldier driven by MC's pose
 ─ composites the MW2 layer ◀───┼───────────────────────────────────── ─ offscreen: viewmodel + HUD + FX
                                ─ writes Mw2Control ─────────────────▶ ─ reads Mw2Control + McState
                                ─ raycasts each Mw2Shot ◀───────────── ─ emits Mw2Shot per bullet
                                ─ writes Mw2Hit ─────────────────────▶ ─ hitmarkers, impacts, kill feed
```

## 2. Why a third process

- IW4L renders with Bevy and wgpu. Putting a second GPU device inside Skyrim's process risks both
  renderers, and the overlay path SkyCraft already uses for Minecraft (CPU triple buffer, then GPU
  interop later) works unchanged for a third producer.
- The 2010 mashup already runs MW2's guns in a world MW2 doesn't draw: its Minecraft map loads
  `mp_rust` only for weapon, body and menu content, hides that world, and points traces at a
  voxel world (`assets::minecraft_map`, `sim::voxel`). The guest is the same trick with nothing
  drawn at all: a `skyrim:passthrough` zone, proxied by `mp_rust`, transparent clear.
- Skate mode already drives the soldier from outside (`set_external_motion`, `set_origin`). The
  guest drives it from Minecraft's pose the same way.

## 3. Coordinates

- The guest takes MC coordinates and uses the voxel mapping: map point = `(x, -z, y) * BLOCK`
  around a moving origin, with `BLOCK = 36` map units (sim/voxel.rs). So the 70-unit soldier is
  about two blocks tall, matching Steve.
- Camera: eye position, yaw, pitch and vertical FOV come straight from `McState` (eyeX/Y/Z, yaw,
  pitch, fovDeg). MW2's viewmodel FOV stays MW2's own (it is drawn in its own projection).

## 4. The MW2 mapping `Local\SkyCraft_MW2_v1`

Created by Skyrim, like the main mapping. Layout lives in `protocol/skycraft_mw2.h`, mirrored in
Java and Rust, with layout tests on all three sides.

| Region | Writer → reader | Contents |
|---|---|---|
| Header | all | magic `MW2G`, version, guest PID, heartbeats (guest, MC) |
| `Mw2Control` (seqlock) | MC → guest | held weapon id (0 = none), buttons (attack, ADS, reload, melee, sprint, swap), MC frame counter |
| `Mw2State` (seqlock) | guest → MC | ammo in clip / stock, ADS fraction, wanted FOV scale, flags (reloading, firing, ready), spread |
| Shot ring | guest → MC | `Mw2Shot {id, weapon, origin, dir, damage, minDamage, range, minDamageRange, headMult}` per bullet (a SPAS-12 blast is 8) |
| Hit ring | MC → guest | `Mw2Hit {shotId, kind (miss/world/block/actor/kill), point, normal, damage}` |
| Overlay | guest → Skyrim | triple buffer, same scheme as the main overlay (`OverlayCtl`, 3 slots) |

## 5. Who does what

**Minecraft (Fabric):**
- Registers one item per MW2 gun (`skycraft:mw2_intervention`, `mw2_spas12`, `mw2_ump45`) and puts
  them in the player's inventory the first time.
- While one is held, it hides Minecraft's hand, takes attack/use/reload away from Minecraft and
  writes them to `Mw2Control`, and applies the guest's ADS FOV scale to its camera (Skyrim's camera
  follows MC's FOV already).
- For each `Mw2Shot` it raycasts its own world: placed blocks, the Skyrim CollisionField, and actor
  proxies. Proxy hits go through the existing `HitActor` path, so Skyrim's hit pipeline does blood,
  stagger, aggro and crime. Damage uses MW2's falloff from the shot's fields, then SkyCraft's NPC
  scaling (DESIGN.md §13). This is the one formula re-done in Java, and it's two lines.
- Writes the result to the hit ring.

**Guest (IW4L, new `skycraft_guest` crate + `--skycraft` flag):**
- Loads `skyrim:passthrough` (mp_rust proxy), with the voxel world active and empty so MW2's own
  traces hit nothing. Every bullet becomes an `Mw2Shot` instead.
- Each frame it sets the soldier's origin, view angles and movement flags (sprint, crouch, ground,
  velocity) from `McState`, and its buttons and weapon from `Mw2Control`.
- Renders only the viewmodel, MW2 HUD (ammo, crosshair, hitmarkers) and effects (muzzle flash,
  tracers, impacts from `Mw2Hit`) to an offscreen RGBA target at Skyrim's viewport size, with a
  hidden window, then copies it into the overlay triple buffer.
- Plays MW2's sounds itself.

**Skyrim (SKSE):**
- Creates the mapping, starts the guest (`[MW2]` section in `SkyCraft.ini`: `bEnable`,
  `sGuestExe`, `sMw2Path`) and stops it with Skyrim.
- Composites the MW2 layer after the scene and before Minecraft's GUI layer.

## 6. Milestones

| # | Done when | Oracle |
|---|---|---|
| M0 ✓ | IW4L builds natively on Windows and plays `mp_rust` with the user's MW2 (2026-10-04: `cargo build --profile play -p launcher`, `IW4L_GAMES` = the MW2 folder itself) | the real game, screenshot |
| M1 ✓ | `iw4l --skycraft` boots hidden and publishes a viewmodel frame into the MW2 overlay | `tools/fake_mw2_host.py` saves the overlay to PNG |
| M2 | Skyrim shows the viewmodel; it bobs, sways and sprints with MC movement (guest half ✓; Skyrim half: [HANDOFF-MW2-SKYRIM.md](HANDOFF-MW2-SKYRIM.md)) | in game, SkyCraft.log + screenshot |
| M3 | Firing: shots reach MC, NPCs take damage and react, hitmarkers and ammo HUD work | fake host first (shot ring dump), then a bandit camp |
| M4 | ADS zoom, reload, melee, sounds polished; all three guns | in game |
| later | Killstreaks (UAV → map reveal, care package, Predator), more guns, MW2 icons as MC item textures | |

## 7. The portal home

A small house built from Minecraft blocks, placed in Skyrim the first time you load a save (a
structure file the Fabric mod stamps onto a flat spot near the player), with two portal frames.
No Skyrim plugin (.esp) is needed: the house is ordinary SkyCraft blocks, and the portals are
blocks too, so you can also craft portal frames and build your own.

| Portal | Step through and |
|---|---|
| **Minecraft** | You leave Skyrim for a real Minecraft world: a normal overworld dimension (`skycraft:real`) with vanilla terrain generation in the same save. Skyrim pauses; Minecraft's window comes forward and takes input, and Minecraft draws everything. A portal there brings you back to the house. |
| **MW2** | An instant Team Deathmatch. The IW4L guest leaves passthrough mode, loads a map (`war` game type, bots filling both teams) and shows its own window full screen. Skyrim pauses until the match ends or you leave, then you're back in the house. |

Pieces:
- A `Takeover` field in the main mapping: none / Minecraft / MW2. Skyrim stops the puppet, opens a
  pause menu and hands focus to that window. The other side clears the field to come back.
- The guest gets a second mode, `--skycraft-match <map> war`, using IW4L's own gametypes
  (`gamemode_iw4::GameKind::TeamDeathmatch`) and bots (`bot add N`).

## 8. Joint boss fight (later)

The Ender Dragon and General Shepherd at the Throat of the World.
- **Ender Dragon:** Minecraft's own entity, drawn by SkyCraft's entity capture. It needs a fight
  origin and height probes that see Skyrim's terrain (the mirror world has no blocks), a longer
  draw range than `AvatarExporter.SCENE_RANGE`, and its attacks reaching Skyrim NPCs through the
  proxy → `HitActor` path. End crystals sit on the word walls.
- **Shepherd:** a Skyrim NPC owns his AI and body. The guest draws his MW2 model and animations at
  the NPC (needs a depth-tested guest layer), fires his .44 Magnum through MW2's weapon code, and
  plays his lines from the player's own singleplayer files.

## 9. Skate (parked)

Needs the player's own extracted Skate 3 (`default.xex` + `data`) and a controller. The seams
exist: `skate_host::bridge::Session` takes collision triangles + rails and returns a pose, and
`render_anim::skate::rails::find` turns any triangle soup into grind rails. SkyCraft already
gathers Skyrim's collision triangles for Minecraft, so they can feed the skate session directly,
with Minecraft's player teleported along the skater's path.

## 10. Open questions

- IW4L's offscreen path: Bevy camera to an `Image` target plus a GPU readback each frame, or a
  shared D3D11/D3D12 texture later (overlay is CPU first, same as the MC overlay).
- Whether the MW2 HUD or Minecraft's hotbar owns the bottom of the screen while a gun is held.
- IW4L's dependence on a master server and session flow for a local match (the menu works offline,
  `map` starts a local listen session).
