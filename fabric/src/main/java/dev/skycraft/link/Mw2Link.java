package dev.skycraft.link;

import static dev.skycraft.link.Mw2Proto.*;
import static java.lang.foreign.ValueLayout.*;

import dev.skycraft.SkyCraft;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft's end of the MW2 guns' mapping (docs/MW2.md), which Skyrim creates when [MW2] is on.
 * Minecraft owns the inventory and the held gun; the IW4L guest draws them in MW2's style.
 * Render thread only.
 */
public final class Mw2Link {
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	private static final long GUEST_TIMEOUT_MS = 3000;

	private static final VarHandle INT = JAVA_INT.varHandle();
	private static final VarHandle LONG = JAVA_LONG.varHandle();

	private static final MethodHandle OPEN_FILE_MAPPING;
	private static final MethodHandle MAP_VIEW_OF_FILE;
	private static final MethodHandle UNMAP_VIEW_OF_FILE;
	private static final MethodHandle CLOSE_HANDLE;

	static {
		Linker linker = Linker.nativeLinker();
		SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		OPEN_FILE_MAPPING = linker.downcallHandle(k32.find("OpenFileMappingW").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
		MAP_VIEW_OF_FILE = linker.downcallHandle(k32.find("MapViewOfFile").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
		UNMAP_VIEW_OF_FILE = linker.downcallHandle(k32.find("UnmapViewOfFile").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
		CLOSE_HANDLE = linker.downcallHandle(k32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
	}

	private static MemorySegment view;
	private static MemorySegment handle;
	private static int openedFor = -1;
	private static long lastAttempt;
	private static int session;
	private static int warnedVersion = -1;
	private static float hotbarTop;
	private static float fovScale = 1.0F;

	private Mw2Link() {
	}

	/** Opens the mapping once Skyrim has made it, and again for each new Skyrim. Once a frame. */
	public static void poll() {
		int generation = SkyLink.generation();
		if (view != null && generation != openedFor) {
			close();
		}
		if (view != null || !SkyLink.active()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastAttempt < 1000) {
			return;
		}
		lastAttempt = now;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocateFrom(MAPPING_NAME, StandardCharsets.UTF_16LE);
			MemorySegment h = (MemorySegment) OPEN_FILE_MAPPING.invokeExact(FILE_MAP_ALL_ACCESS, 0, name);
			if (h.address() == 0) {
				return; // [MW2] is off in this Skyrim
			}
			MemorySegment v = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(h, FILE_MAP_ALL_ACCESS, 0, 0, VIEW_BYTES);
			if (v.address() == 0) {
				int ignored = (int) CLOSE_HANDLE.invokeExact(h);
				return;
			}
			MemorySegment seg = v.reinterpret(VIEW_BYTES);
			int magic = (int) INT.getAcquire(seg, H_MAGIC);
			int version = seg.get(JAVA_INT, H_VERSION);
			if (magic != MAGIC || version != VERSION) {
				if (warnedVersion != version) {
					warnedVersion = version;
					SkyCraft.LOG.warn("SkyCraft: MW2 mapping has magic {} version {}; expected version {}", Integer.toHexString(magic), version, VERSION);
				}
				int ignored = (int) UNMAP_VIEW_OF_FILE.invokeExact(v);
				ignored = (int) CLOSE_HANDLE.invokeExact(h);
				return;
			}
			view = seg;
			handle = h;
			openedFor = generation;
			session++;
			SkyCraft.LOG.info("SkyCraft: MW2 guns linked");
		} catch (Throwable t) {
			SkyCraft.LOG.error("SkyCraft: failed to open the MW2 mapping", t);
		}
	}

	private static void close() {
		try {
			int ignored = (int) UNMAP_VIEW_OF_FILE.invokeExact(view);
			ignored = (int) CLOSE_HANDLE.invokeExact(handle);
		} catch (Throwable t) {
			SkyCraft.LOG.warn("SkyCraft: closing the MW2 mapping failed", t);
		}
		view = null;
		handle = null;
	}

	public static boolean open() {
		return view != null;
	}

	/** Bumps each time the mapping is (re)opened. */
	public static int session() {
		return session;
	}

	/** The guest is running and its soldier is up. */
	public static boolean guestReady() {
		MemorySegment s = view;
		if (s == null) {
			return false;
		}
		long beat = (long) LONG.getAcquire(s, H_GUEST_HEARTBEAT);
		if (beat == 0 || SkyLink.tickCount() - beat > GUEST_TIMEOUT_MS) {
			return false;
		}
		for (int attempt = 0; attempt < 8; attempt++) {
			int seq = (int) INT.getAcquire(s, OFF_STATE + ST_SEQ);
			if ((seq & 1) != 0) {
				Thread.onSpinWait();
				continue;
			}
			int flags = s.get(JAVA_INT, OFF_STATE + ST_FLAGS);
			float top = s.get(JAVA_FLOAT, OFF_STATE + ST_HOTBAR_TOP);
			float zoom = s.get(JAVA_FLOAT, OFF_STATE + ST_FOV_SCALE);
			VarHandle.loadLoadFence();
			if ((int) INT.getAcquire(s, OFF_STATE + ST_SEQ) == seq) {
				hotbarTop = top;
				fovScale = Float.isFinite(zoom) && zoom > 0.0F ? Math.min(zoom, 1.0F) : 1.0F;
				return (flags & ST_READY) != 0;
			}
		}
		return false;
	}

	/** Where the guest's hotbar starts, as a fraction of the screen's height (0: not shown), as of guestReady. */
	public static float hotbarTop() {
		return hotbarTop;
	}

	/**
	 * MW2's aim-down-sights zoom as of guestReady: the tangent of the half field of view now over
	 * unzoomed (1 at the hip).
	 */
	public static float fovScale() {
		return fovScale;
	}

	/** One bullet the guest fired, for Minecraft to trace (Shot in the header; blocks). */
	public record Shot(int id, int blast, int weapon, float dirX, float dirY, float dirZ, float range, float damage, float minDamage,
		float damageRange, float minDamageRange, float headMultiplier, float bodyMultiplier) {
	}

	/** Takes every bullet the guest fired since the last call. */
	public static java.util.List<Shot> drainShots() {
		MemorySegment s = view;
		if (s == null) {
			return java.util.List.of();
		}
		long base = OFF_SHOT_RING;
		long head = (long) LONG.getAcquire(s, base + RING_HEAD);
		long tail = s.get(JAVA_LONG, base + RING_TAIL);
		if (head - tail > SHOT_ENTRIES) {
			tail = head - SHOT_ENTRIES;
		}
		java.util.List<Shot> shots = new java.util.ArrayList<>();
		while (tail < head) {
			long e = base + RING_DATA + (tail & (SHOT_ENTRIES - 1)) * SHOT_BYTES;
			float[] f = new float[10];
			for (int i = 0; i < f.length; i++) {
				f[i] = s.get(JAVA_FLOAT, e + 0x28 + i * 4L);
			}
			shots.add(new Shot(s.get(JAVA_INT, e), s.get(JAVA_INT, e + 4), s.get(JAVA_INT, e + 8), f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9]));
			tail++;
		}
		LONG.setRelease(s, base + RING_TAIL, tail);
		return shots;
	}

	/** Tells the guest what one of its bullets hit (HIT_*), for MW2's hitmarker. */
	public static void pushHit(int shotId, int kind, double x, double y, double z, float damage, boolean headshot) {
		MemorySegment s = view;
		if (s == null) {
			return;
		}
		long base = OFF_HIT_RING;
		long head = s.get(JAVA_LONG, base + RING_HEAD);
		long tail = (long) LONG.getAcquire(s, base + RING_TAIL);
		if (head - tail >= HIT_ENTRIES) {
			return;
		}
		long e = base + RING_DATA + (head & (HIT_ENTRIES - 1)) * HIT_BYTES;
		s.set(JAVA_INT, e, shotId);
		s.set(JAVA_INT, e + 4, kind);
		s.set(JAVA_DOUBLE, e + 0x08, x);
		s.set(JAVA_DOUBLE, e + 0x10, y);
		s.set(JAVA_DOUBLE, e + 0x18, z);
		s.set(JAVA_FLOAT, e + 0x2C, damage);
		s.set(JAVA_INT, e + 0x30, headshot ? 1 : 0);
		LONG.setRelease(s, base + RING_HEAD, head + 1);
	}

	/** The gun in Minecraft's hand (WEAPON_*) and the buttons held for it. */
	public static void writeControl(int weapon, int buttons, long mcFrame) {
		MemorySegment s = view;
		if (s == null) {
			return;
		}
		int seq = s.get(JAVA_INT, OFF_CONTROL + CT_SEQ);
		INT.setRelease(s, OFF_CONTROL + CT_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, OFF_CONTROL + CT_WEAPON, weapon);
		s.set(JAVA_INT, OFF_CONTROL + CT_BUTTONS, buttons);
		s.set(JAVA_LONG, OFF_CONTROL + CT_MC_FRAME, mcFrame);
		INT.setRelease(s, OFF_CONTROL + CT_SEQ, seq + 2);
	}

	/** One stack of the inventory snapshot; an empty id is no stack. */
	public interface StackSource {
		void stack(int index, Stack out);
	}

	public static final class Stack {
		public String id = "";
		public int weapon;
		public int count;
		public float durability = -1.0F;
	}

	private static final Stack SCRATCH = new Stack();
	private static final byte[] ID_BYTES = new byte[UI_ID_BYTES];

	/**
	 * Writes the inventory snapshot under its seqlock: every stack from a_source. guiUnit is one
	 * Minecraft GUI pixel as a fraction of the screen's height.
	 */
	public static void writeUi(int flags, int selected, float guiUnit, long mcFrame, StackSource source) {
		MemorySegment s = view;
		if (s == null) {
			return;
		}
		long b = OFF_UI;
		int seq = s.get(JAVA_INT, b + UI_SEQ);
		INT.setRelease(s, b + UI_SEQ, seq + 1);
		VarHandle.storeStoreFence();
		s.set(JAVA_INT, b + UI_FLAGS, flags);
		s.set(JAVA_INT, b + UI_SELECTED, selected);
		s.set(JAVA_FLOAT, b + UI_GUI_UNIT, guiUnit);
		s.set(JAVA_LONG, b + UI_MC_FRAME, mcFrame);
		for (int i = 0; i < UI_STACK_COUNT; i++) {
			Stack stack = SCRATCH;
			stack.id = "";
			stack.weapon = WEAPON_NONE;
			stack.count = 0;
			stack.durability = -1.0F;
			if (source != null) {
				source.stack(i, stack);
			}
			long e = b + UI_STACKS + i * UI_STACK_BYTES;
			byte[] id = stack.id.getBytes(StandardCharsets.UTF_8);
			int n = Math.min(id.length, UI_ID_BYTES - 1);
			java.util.Arrays.fill(ID_BYTES, (byte) 0);
			System.arraycopy(id, 0, ID_BYTES, 0, n);
			MemorySegment.copy(ID_BYTES, 0, s, JAVA_BYTE, e + US_ID, UI_ID_BYTES);
			s.set(JAVA_INT, e + US_WEAPON, stack.weapon);
			s.set(JAVA_INT, e + US_COUNT, stack.count);
			s.set(JAVA_FLOAT, e + US_DURABILITY, stack.durability);
		}
		INT.setRelease(s, b + UI_SEQ, seq + 2);
	}

	/** Passes one mouse or key event of the inventory screen to the guest (x, y: 0..1). */
	public static void pushInput(int kind, int code, int mods, float x, float y) {
		MemorySegment s = view;
		if (s == null) {
			return;
		}
		long base = OFF_UI_INPUT_RING;
		long head = s.get(JAVA_LONG, base + RING_HEAD);
		long tail = (long) LONG.getAcquire(s, base + RING_TAIL);
		if (head - tail >= UI_INPUT_ENTRIES) {
			return; // the guest has fallen a whole ring behind
		}
		long e = base + RING_DATA + (head & (UI_INPUT_ENTRIES - 1)) * UI_INPUT_BYTES;
		s.set(JAVA_SHORT, e, (short) kind);
		s.set(JAVA_SHORT, e + 2, (short) code);
		s.set(JAVA_SHORT, e + 4, (short) mods);
		s.set(JAVA_FLOAT, e + 8, x);
		s.set(JAVA_FLOAT, e + 12, y);
		LONG.setRelease(s, base + RING_HEAD, head + 1);
	}

	public interface ClickSink {
		void accept(int kind, int slot, int flags, int hotbar, long spreadMask);
	}

	/** Takes every click the guest's inventory screen made. */
	public static void drainClicks(ClickSink sink) {
		MemorySegment s = view;
		if (s == null) {
			return;
		}
		long base = OFF_UI_CLICK_RING;
		long head = (long) LONG.getAcquire(s, base + RING_HEAD);
		long tail = s.get(JAVA_LONG, base + RING_TAIL);
		if (head - tail > UI_CLICK_ENTRIES) {
			tail = head - UI_CLICK_ENTRIES;
		}
		while (tail < head) {
			long e = base + RING_DATA + (tail & (UI_CLICK_ENTRIES - 1)) * UI_CLICK_BYTES;
			int kind = s.get(JAVA_INT, e + UC_KIND);
			int slot = s.get(JAVA_INT, e + UC_SLOT);
			int flags = s.get(JAVA_INT, e + UC_FLAGS);
			int hotbar = s.get(JAVA_INT, e + UC_HOTBAR);
			long mask = s.get(JAVA_LONG, e + UC_SPREAD_MASK);
			tail++;
			sink.accept(kind, slot, flags, hotbar, mask);
		}
		LONG.setRelease(s, base + RING_TAIL, tail);
	}
}
