package dev.skycraft.link;

/**
 * Mirror of protocol/skycraft_mw2.h (the MW2 guns' mapping, docs/MW2.md). Keep the two in sync.
 */
public final class Mw2Proto {
	private Mw2Proto() {
	}

	public static final int MAGIC = 0x4732574D; // "MW2G"
	public static final int VERSION = 5;
	public static final String MAPPING_NAME = "Local\\SkyCraft_MW2_v1";

	// Header
	public static final long H_MAGIC = 0x00;
	public static final long H_VERSION = 0x04;
	public static final long H_GUEST_PID = 0x08;
	public static final long H_HOST_HEARTBEAT = 0x10;
	public static final long H_GUEST_HEARTBEAT = 0x18;

	// Control (Minecraft -> guest, seqlock)
	public static final long OFF_CONTROL = 0x100;
	public static final long CT_SEQ = 0x00, CT_WEAPON = 0x04, CT_BUTTONS = 0x08, CT_MC_FRAME = 0x10;

	public static final int BTN_ATTACK = 1;
	public static final int BTN_ADS = 1 << 1;
	public static final int BTN_RELOAD = 1 << 2;
	public static final int BTN_MELEE = 1 << 3;
	public static final int BTN_HOLD_BREATH = 1 << 7;

	public static final int WEAPON_NONE = 0;
	public static final int WEAPON_INTERVENTION = 1;
	public static final int WEAPON_SPAS12 = 2;
	public static final int WEAPON_UMP45 = 3;

	// State (guest -> Minecraft, seqlock)
	public static final long OFF_STATE = 0x200;
	public static final long ST_SEQ = 0x00, ST_FLAGS = 0x04, ST_ADS_FRACTION = 0x14, ST_FOV_SCALE = 0x18, ST_HOTBAR_TOP = 0x28;
	public static final int ST_READY = 1;

	// SPSC rings: head (writer) at +0x00, tail (reader) at +0x40, entries from +0x80.
	public static final long RING_HEAD = 0x00, RING_TAIL = 0x40, RING_DATA = 0x80;

	// Shots (guest -> Minecraft ring) and hits (Minecraft -> guest ring)
	public static final long OFF_SHOT_RING = 0x1000;
	public static final int SHOT_ENTRIES = 256;
	public static final long SHOT_BYTES = 0x50;
	public static final long OFF_HIT_RING = 0x9000;
	public static final int HIT_ENTRIES = 256;
	public static final long HIT_BYTES = 0x38;
	public static final int HIT_MISS = 0;
	public static final int HIT_WORLD = 1;
	public static final int HIT_BLOCK = 2;
	public static final int HIT_ACTOR = 3;
	public static final int HIT_KILL = 4;
	public static final int HIT_MOB = 5;

	// Inventory (Minecraft -> guest, seqlock)
	public static final long OFF_UI = 0xD000;
	public static final long UI_SEQ = 0x00, UI_FLAGS = 0x04, UI_SELECTED = 0x08, UI_GUI_UNIT = 0x0C, UI_MC_FRAME = 0x10, UI_STACKS = 0x20;
	public static final int UI_ACTIVE = 1;
	public static final int UI_INVENTORY_OPEN = 1 << 1;
	public static final int UI_INVENTORY_SLOTS = 41;
	public static final int UI_CRAFTING = 41;
	public static final int UI_RESULT = 45;
	public static final int UI_CURSOR = 46;
	public static final int UI_STACK_COUNT = 47;
	public static final long UI_STACK_BYTES = 0x40;
	public static final int UI_ID_BYTES = 48;
	public static final long US_ID = 0x00, US_WEAPON = 0x30, US_COUNT = 0x34, US_DURABILITY = 0x38;

	// Inventory input (Minecraft -> guest ring)
	public static final long OFF_UI_INPUT_RING = 0xE000;
	public static final int UI_INPUT_ENTRIES = 128;
	public static final long UI_INPUT_BYTES = 0x10;
	public static final int UI_IN_MOVE = 1;
	public static final int UI_IN_BUTTON_DOWN = 2;
	public static final int UI_IN_BUTTON_UP = 3;
	public static final int UI_IN_KEY_DOWN = 4;
	public static final int UI_IN_KEY_UP = 5;
	public static final int UI_MOD_SHIFT = 1;
	public static final int UI_MOD_CTRL = 1 << 1;

	// Inventory clicks (guest -> Minecraft ring)
	public static final long OFF_UI_CLICK_RING = 0xF000;
	public static final int UI_CLICK_ENTRIES = 64;
	public static final long UI_CLICK_BYTES = 0x20;
	public static final long UC_KIND = 0x00, UC_SLOT = 0x04, UC_FLAGS = 0x08, UC_HOTBAR = 0x0C, UC_SPREAD_MASK = 0x10;
	public static final int UI_CLICK_SLOT = 1;
	public static final int UI_CLICK_OUTSIDE = 2;
	public static final int UI_CLICK_GATHER = 3;
	public static final int UI_CLICK_SWAP = 4;
	public static final int UI_CLICK_SPREAD = 5;
	public static final int UI_CLICK_RIGHT = 1;
	public static final int UI_CLICK_SHIFT = 1 << 1;

	/** Everything but the overlay's pixels, which are Skyrim's to read. */
	public static final long VIEW_BYTES = 0x10000;
}
