"""Stand-in for the SKSE plugin's MW2 side, for testing the IW4L guest without Skyrim.

Creates Local\\SkyCraft_MW2_v1 (protocol/skycraft_mw2.h) with a viewport size, waits for the guest
to publish overlay frames, prints how many arrived and how much of the frame is transparent, and
saves the last one (with its alpha) to a PNG.

    python tools/fake_mw2_host.py [seconds] [out.png] [width] [height]

After Minecraft's player has walked, sprinted and turned, it plays Minecraft's inventory: picks
each gun, a block and an empty hand on the hotbar, then opens the inventory, sweeps the mouse
over it clicking, and prints the clicks the guest hands back. Each step is saved as a PNG.

Start the guest separately, e.g. with IW4L_SKYCRAFT=1 and IW4L_GAMES pointing at MW2:
    iw4l.exe map mp_rust --cmds "wait world; spawn 0; force_match_start"
"""

import ctypes
import math
import mmap
import struct
import sys
import time

from fake_skyrim import save_png

MAGIC = 0x4732574D
VERSION = 5
NAME = "Local\\SkyCraft_MW2_v1"
OFF_OVL = 0x300
OFF_OVL_HDR = 0x340
OFF_PIX = 0x10000
SLOT = 3840 * 2160 * 4
SIZE = OFF_PIX + SLOT * 3

# The inventory (protocol/skycraft_mw2.h): snapshot, input ring, click ring.
OFF_UI = 0xD000
OFF_UI_INPUT = 0xE000
OFF_UI_CLICKS = 0xF000
UI_ACTIVE, UI_INVENTORY_OPEN = 1, 2
UI_IN_MOVE, UI_IN_DOWN, UI_IN_UP = 1, 2, 3
CLICK_KINDS = {1: "slot", 2: "outside", 3: "gather", 4: "swap", 5: "spread"}

# What Minecraft's player carries: stack index -> (item id, MW2 weapon, count).
INVENTORY = {
    0: ("skycraft:mw2_intervention", 1, 1),
    1: ("skycraft:mw2_spas12", 2, 1),
    2: ("skycraft:mw2_ump45", 3, 1),
    3: ("minecraft:oak_planks", 0, 64),
    4: ("minecraft:torch", 0, 32),
    5: ("minecraft:diamond_sword", 0, 1),
    9: ("minecraft:cobblestone", 0, 64),
    10: ("minecraft:stone_bricks", 0, 64),
    11: ("minecraft:glass", 0, 64),
    12: ("minecraft:golden_apple", 0, 4),
    36: ("minecraft:iron_boots", 0, 1),
    37: ("minecraft:iron_leggings", 0, 1),
    38: ("minecraft:iron_chestplate", 0, 1),
    39: ("minecraft:iron_helmet", 0, 1),
    40: ("minecraft:shield", 0, 1),
}

BTN_ATTACK, BTN_ADS = 1, 2

# (name, seconds, selected hotbar slot, inventory open, gun buttons)
UI_PHASES = [
    ("intervention", 4.0, 0, False, 0),
    ("spas12", 4.0, 1, False, 0),
    ("ump45", 4.0, 2, False, 0),
    ("planks", 3.0, 3, False, 0),
    ("empty-hand", 3.0, 8, False, 0),
    ("inventory", 6.0, 0, True, 0),
    ("ads", 3.0, 0, False, BTN_ADS),
    ("quickscope", 0.6, 0, False, BTN_ADS | BTN_ATTACK),
    ("after-shot", 0.4, 0, False, 0),
]

_tick = ctypes.windll.kernel32.GetTickCount64
_tick.restype = ctypes.c_uint64


MAIN_NAME = "Local\\SkyCraft_v1"
MAIN_MAGIC = 0x43594B53
MAIN_VERSION = 11
OFF_MC = 0x200
MC_IN_WORLD, MC_ON_GROUND, MC_SPRINTING = 1, 4, 16

# (name, seconds, yaw start, yaw end, pitch, speed blocks/s along the look, flags)
PHASES = [
    ("stand", 6.0, 0.0, 0.0, 0.0, 0.0, MC_IN_WORLD | MC_ON_GROUND),
    ("walk", 4.0, 0.0, 0.0, 0.0, 4.317, MC_IN_WORLD | MC_ON_GROUND),
    ("sprint", 4.0, 0.0, 0.0, 0.0, 5.612, MC_IN_WORLD | MC_ON_GROUND | MC_SPRINTING),
    ("turn", 1.5, 0.0, 120.0, -20.0, 0.0, MC_IN_WORLD | MC_ON_GROUND),
    ("settle", 3.0, 120.0, 120.0, 0.0, 0.0, MC_IN_WORLD | MC_ON_GROUND),
]


class FakeMinecraft:
    """McState in a stand-in main mapping, written under its seqlock."""

    def __init__(self):
        self.m = mmap.mmap(-1, 0x1000, tagname=MAIN_NAME)
        magic, version = struct.unpack_from("<II", self.m, 0)
        if magic == MAIN_MAGIC and version == MAIN_VERSION:
            # A real Skyrim made it; leave Minecraft to the real one.
            self.m = None
            return
        self.m[0:0x1000] = bytes(0x1000)
        struct.pack_into("<II", self.m, 0, MAIN_MAGIC, MAIN_VERSION)
        self.seq = 0
        self.pos = [0.0, 64.0, 0.0]

    def write(self, flags, yaw, pitch, velocity, dt):
        if self.m is None:
            return
        prev = list(self.pos)
        self.pos = [p + v * dt for p, v in zip(self.pos, velocity)]
        cur = self.pos
        tick_prev = [c - v * 0.05 for c, v in zip(cur, velocity)]
        self.seq += 1
        struct.pack_into("<I", self.m, OFF_MC, self.seq)  # odd: writing
        struct.pack_into("<I3d2f", self.m, OFF_MC + 0x04, flags, *cur, yaw, pitch)
        struct.pack_into("<f", self.m, OFF_MC + 0x40, 70.0)
        struct.pack_into("<3d3d", self.m, OFF_MC + 0x70, *tick_prev, *cur)
        struct.pack_into("<f", self.m, OFF_MC + 0xB8, 50.0)
        self.seq += 1
        struct.pack_into("<I", self.m, OFF_MC, self.seq)
        del prev


class Mw2Link:
    def __init__(self, width, height):
        self.m = mmap.mmap(-1, SIZE, tagname=NAME)
        self.m[0:0x400] = bytes(0x400)
        struct.pack_into("<IIII", self.m, 0, MAGIC, VERSION, 0, 0)
        struct.pack_into("<II", self.m, 0x20, width, height)
        self.front = 2  # middle starts at 0, the writer's back slot at 1

    def heartbeat(self):
        # GetTickCount64, as Skyrim writes it: the guest compares it with its own clock.
        struct.pack_into("<Q", self.m, 0x10, _tick())
        # Skyrim's frame counter: the guest renders one frame per tick of it.
        frame, = struct.unpack_from("<I", self.m, 0x0C)
        struct.pack_into("<I", self.m, 0x0C, (frame + 1) & 0xFFFFFFFF)

    def hotbar_top(self):
        top, = struct.unpack_from("<f", self.m, 0x228)
        return top

    def write_ui(self, selected, open_):
        """Minecraft's inventory snapshot, under its seqlock."""
        seq, = struct.unpack_from("<I", self.m, OFF_UI)
        struct.pack_into("<I", self.m, OFF_UI, seq + 1)
        flags = UI_ACTIVE | (UI_INVENTORY_OPEN if open_ else 0)
        # Minecraft's GUI scale 7 at 1800 pixels high, as on a 2880x1800 screen.
        struct.pack_into("<IIfQ", self.m, OFF_UI + 4, flags, selected, 7 / 1800, 0)
        for i in range(47):
            item = INVENTORY.get(i)
            at = OFF_UI + 0x20 + i * 0x40
            raw = item[0].encode() if item else b""
            self.m[at:at + 48] = raw + bytes(48 - len(raw))
            struct.pack_into("<IIfI", self.m, at + 0x30, item[1] if item else 0, item[2] if item else 0, -1.0, 0)
        struct.pack_into("<I", self.m, OFF_UI, seq + 2)

    def write_control(self, weapon, buttons):
        seq, = struct.unpack_from("<I", self.m, 0x100)
        struct.pack_into("<I", self.m, 0x100, seq + 1)
        struct.pack_into("<IIIQ", self.m, 0x104, weapon, buttons, 0, 0)
        struct.pack_into("<I", self.m, 0x100, seq + 2)

    def zoom(self):
        ads, fov_scale = struct.unpack_from("<ff", self.m, 0x214)
        return ads, fov_scale

    def drain_shots(self):
        head, = struct.unpack_from("<Q", self.m, 0x1000)
        tail, = struct.unpack_from("<Q", self.m, 0x1040)
        shots = []
        while tail < head:
            at = 0x1080 + (tail % 256) * 0x50
            sid, blast, weapon = struct.unpack_from("<III", self.m, at)
            f = struct.unpack_from("<10f", self.m, at + 0x28)
            shots.append((sid, blast, weapon, f))
            tail += 1
        struct.pack_into("<Q", self.m, 0x1040, tail)
        return shots

    def push_hit(self, shot_id, kind, damage, headshot):
        head, = struct.unpack_from("<Q", self.m, 0x9000)
        at = 0x9080 + (head % 256) * 0x38
        struct.pack_into("<II3d3ffII", self.m, at, shot_id, kind, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, damage, int(headshot), 0)
        struct.pack_into("<Q", self.m, 0x9000, head + 1)

    def push_input(self, kind, code, x, y):
        head, = struct.unpack_from("<Q", self.m, OFF_UI_INPUT)
        at = OFF_UI_INPUT + 0x80 + (head % 128) * 16
        struct.pack_into("<HHHHff", self.m, at, kind, code, 0, 0, x, y)
        struct.pack_into("<Q", self.m, OFF_UI_INPUT, head + 1)

    def drain_clicks(self):
        head, = struct.unpack_from("<Q", self.m, OFF_UI_CLICKS)
        tail, = struct.unpack_from("<Q", self.m, OFF_UI_CLICKS + 0x40)
        clicks = []
        while tail < head:
            at = OFF_UI_CLICKS + 0x80 + (tail % 64) * 32
            kind, slot, flags, hotbar, mask = struct.unpack_from("<IIIIQ", self.m, at)
            clicks.append((CLICK_KINDS.get(kind, kind), slot, flags, hotbar, mask))
            tail += 1
        struct.pack_into("<Q", self.m, OFF_UI_CLICKS + 0x40, tail)
        return clicks

    def ready(self):
        flags, = struct.unpack_from("<I", self.m, 0x204)
        return bool(flags & 1)

    def guest(self):
        pid, = struct.unpack_from("<I", self.m, 0x08)
        beat, = struct.unpack_from("<Q", self.m, 0x18)
        return pid, beat

    def acquire_overlay(self):
        (state,) = struct.unpack_from("<I", self.m, OFF_OVL)
        if not state & 4:
            return None
        struct.pack_into("<I", self.m, OFF_OVL, self.front)
        self.front = state & 3
        w, h, flags, _pad, frame = struct.unpack_from("<IIIIQ", self.m, OFF_OVL_HDR + self.front * 0x40)
        start = OFF_PIX + self.front * SLOT
        pixels = bytes(self.m[start:start + w * h * 4])
        if flags & 1:  # BGRA, as the guest renders it
            import numpy
            px = numpy.frombuffer(pixels, numpy.uint8).reshape(-1, 4)
            pixels = px[:, [2, 1, 0, 3]].tobytes()
        return w, h, frame, pixels


def gun_top(frame):
    """First row of the lower-right quarter with an opaque pixel: where the gun's top edge is."""
    import numpy
    w, h, _, rgba = frame
    alpha = numpy.frombuffer(rgba, numpy.uint8)[3::4].reshape(h, w)[h // 2:, w // 2:]
    rows = numpy.nonzero((alpha == 255).any(axis=1))[0]
    return int(rows[0]) + h // 2 if len(rows) else None


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 120.0
    out = sys.argv[2] if len(sys.argv) > 2 else "mw2_overlay.png"
    width = int(sys.argv[3]) if len(sys.argv) > 3 else 1280
    height = int(sys.argv[4]) if len(sys.argv) > 4 else 720
    link = Mw2Link(width, height)
    mc = FakeMinecraft()
    print(f"{NAME} up ({width}x{height}); waiting for the guest for {seconds:.0f}s"
          + ("" if mc.m is None else "; playing Minecraft's player"))
    deadline = time.monotonic() + seconds
    frames = 0
    last = None
    announced = False
    phase, phase_start, playing = 0, None, False
    ready_since = None
    tops = {}
    ui_phase, ui_start, ui_step, clicks = 0, None, -1, []
    now = time.monotonic()
    while now < deadline:
        dt = time.monotonic() - now
        now = time.monotonic()
        link.heartbeat()
        pid, _ = link.guest()
        if pid and not announced:
            print(f"guest pid {pid} connected")
            announced = True
        got = link.acquire_overlay()
        if got:
            frames += 1
            last = got
            # Start the script once the soldier has been up for a moment.
            if link.ready():
                ready_since = ready_since or now
            else:
                ready_since = None
            if not playing and ready_since and now - ready_since > 3.0:
                playing, phase_start = True, now
                print("guest ready; playing the script")
            if playing and phase < len(PHASES) and frames % 4 == 0:
                tops.setdefault(PHASES[phase][0], []).append(gun_top(got))
        if playing and phase >= len(PHASES) and ui_phase < len(UI_PHASES):
            name, length, selected, open_, buttons = UI_PHASES[ui_phase]
            ui_start = ui_start or now
            link.write_ui(selected, open_)
            link.write_control(1 if selected == 0 else 0, buttons)
            for shot in link.drain_shots():
                sid, blast, weapon, f = shot
                print(f"shot {sid:#x} (blast {blast}, weapon {weapon}): dir ({f[0]:.3f}, {f[1]:.3f}, {f[2]:.3f}) range {f[3]:.0f} blocks, "
                      f"damage {f[4]:.0f}..{f[5]:.0f} over {f[6]:.0f}..{f[7]:.0f} blocks, head x{f[8]:.2f}, body x{f[9]:.2f}")
                link.push_hit(sid, 3, f[4] * f[9], False)
            t = (now - ui_start) / length
            if open_:
                # Sweep the mouse over the middle of the screen, clicking on the way.
                step = int(t * 48)
                if step != ui_step:
                    ui_step = step
                    x = 0.35 + 0.30 * ((step % 8) / 7.0)
                    y = 0.30 + 0.40 * ((step // 8) / 5.0)
                    link.push_input(UI_IN_MOVE, 0, x, y)
                    if step % 3 == 1:
                        link.push_input(UI_IN_DOWN, 0, x, y)
                        link.push_input(UI_IN_UP, 0, x, y)
            for click in link.drain_clicks():
                clicks.append((name, click))
            if t >= 1.0:
                if last:
                    w, h, _, rgba = last
                    stem = out[:-4] if out.endswith(".png") else out
                    save_png(f"{stem}-{name}.png", w, h, rgba, False)
                    ads, zoom = link.zoom()
                    print(f"inventory {name}: saved {stem}-{name}.png (hotbar top at {link.hotbar_top():.3f} of the height; "
                          f"ADS {ads:.2f}, zoom {zoom:.3f})")
                ui_phase, ui_start = ui_phase + 1, None
                if ui_phase == len(UI_PHASES):
                    link.write_ui(0, False)
                    link.write_control(0, 0)
        if playing and phase < len(PHASES):
            name, length, yaw0, yaw1, pitch, speed, flags = PHASES[phase]
            t = min((now - phase_start) / length, 1.0)
            yaw = yaw0 + (yaw1 - yaw0) * t
            rad = math.radians(yaw)
            velocity = [-math.sin(rad) * speed, 0.0, math.cos(rad) * speed]
            mc.write(flags, yaw, pitch * math.sin(math.pi * t), velocity, dt)
            if t >= 1.0:
                if last:
                    w, h, _, rgba = last
                    stem = out[:-4] if out.endswith(".png") else out
                    save_png(f"{stem}-{name}.png", w, h, rgba, False)
                    print(f"phase {name}: saved {stem}-{name}.png")
                phase, phase_start = phase + 1, now
        elif not playing:
            mc.write(MC_IN_WORLD | MC_ON_GROUND, 0.0, 0.0, [0.0, 0.0, 0.0], dt)
        time.sleep(0.005)
    for name, click in clicks:
        print(f"click during {name}: {click}")
    if ui_phase == len(UI_PHASES) and not clicks:
        print("no clicks came back from the inventory screen")
    for name, values in tops.items():
        values = [v for v in values if v is not None]
        if values:
            print(f"gun top edge during {name}: rows {min(values)}..{max(values)} over {len(values)} samples")
    if not last:
        print("no overlay frames")
        return 1
    w, h, frame, rgba = last
    alpha = rgba[3::4]
    clear = alpha.count(0)
    opaque = alpha.count(255)
    print(f"{frames} frames received, last frame id {frame}, {w}x{h}")
    print(f"alpha: {clear / len(alpha):.1%} fully transparent, {opaque / len(alpha):.1%} opaque")
    save_png(out, w, h, rgba, False)
    print(f"saved {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
