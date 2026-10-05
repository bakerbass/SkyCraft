"""Stand-in for the SKSE plugin's MW2 side, for testing the IW4L guest without Skyrim.

Creates Local\\SkyCraft_MW2_v1 (protocol/skycraft_mw2.h) with a viewport size, waits for the guest
to publish overlay frames, prints how many arrived and how much of the frame is transparent, and
saves the last one (with its alpha) to a PNG.

    python tools/fake_mw2_host.py [seconds] [out.png] [width] [height]

Start the guest separately, e.g. with IW4L_SKYCRAFT=1 and IW4L_GAMES pointing at MW2:
    iw4l.exe map mp_rust --cmds "wait world; spawn 0; force_match_start"
"""

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


class Mw2Link:
    def __init__(self, width, height):
        self.m = mmap.mmap(-1, SIZE, tagname=NAME)
        self.m[0:0x400] = bytes(0x400)
        struct.pack_into("<IIII", self.m, 0, MAGIC, VERSION, 0, 0)
        struct.pack_into("<II", self.m, 0x20, width, height)
        self.front = 2  # middle starts at 0, the writer's back slot at 1

    def heartbeat(self):
        struct.pack_into("<Q", self.m, 0x10, int(time.monotonic() * 1000))

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


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 120.0
    out = sys.argv[2] if len(sys.argv) > 2 else "mw2_overlay.png"
    width = int(sys.argv[3]) if len(sys.argv) > 3 else 1280
    height = int(sys.argv[4]) if len(sys.argv) > 4 else 720
    link = Mw2Link(width, height)
    print(f"{NAME} up ({width}x{height}); waiting for the guest for {seconds:.0f}s")
    deadline = time.monotonic() + seconds
    frames = 0
    last = None
    announced = False
    while time.monotonic() < deadline:
        link.heartbeat()
        pid, _ = link.guest()
        if pid and not announced:
            print(f"guest pid {pid} connected")
            announced = True
        got = link.acquire_overlay()
        if got:
            frames += 1
            last = got
        time.sleep(0.005)
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
