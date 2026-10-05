"""Stand-in for the SKSE plugin's MW2 side, for testing the IW4L guest without Skyrim.

Creates Local\\SkyCraft_MW2_v1 (protocol/skycraft_mw2.h) with a viewport size, waits for the guest
to publish overlay frames, prints how many arrived and how much of the frame is transparent, and
saves the last one (with its alpha) to a PNG.

    python tools/fake_mw2_host.py [seconds] [out.png] [width] [height]

Start the guest separately, e.g. with IW4L_SKYCRAFT=1 and IW4L_GAMES pointing at MW2:
    iw4l.exe map mp_rust --cmds "wait world; spawn 0; force_match_start"
"""

import math
import mmap
import struct
import sys
import time

from fake_skyrim import save_png

MAGIC = 0x4732574D
VERSION = 1
NAME = "Local\\SkyCraft_MW2_v1"
OFF_OVL = 0x300
OFF_OVL_HDR = 0x340
OFF_PIX = 0x10000
SLOT = 3840 * 2160 * 4
SIZE = OFF_PIX + SLOT * 3


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
        struct.pack_into("<Q", self.m, 0x10, int(time.monotonic() * 1000))

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
        w, h, _flags, _pad, frame = struct.unpack_from("<IIIIQ", self.m, OFF_OVL_HDR + self.front * 0x40)
        start = OFF_PIX + self.front * SLOT
        return w, h, frame, bytes(self.m[start:start + w * h * 4])


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
