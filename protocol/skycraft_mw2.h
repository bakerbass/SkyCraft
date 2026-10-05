// SkyCraft MW2 shared-memory protocol (Skyrim SKSE plugin, Minecraft Fabric mod and the IW4L
// guest that plays MW2's guns). See docs/MW2.md.
//
// This header is the single source of truth for the byte layout. It is mirrored in
// fabric/src/main/java/dev/skycraft/link/Mw2Proto.java and in IW4L's
// crates/skycraft_guest/src/proto.rs; if you change anything here, change it there too and bump
// kVersion.
//
// All multi-byte values are little-endian. Skyrim creates the mapping; Minecraft and the guest
// open it. The guest also reads Minecraft's camera (McState) from the main mapping.
// Coordinates are Minecraft space (blocks, Y up, Z south), like the main protocol.
#pragma once

#include <cstdint>

namespace skycraft::mw2
{
	inline constexpr std::uint32_t kMagic = 0x4732574D;  // "MW2G"
	inline constexpr std::uint32_t kVersion = 1;
	inline constexpr wchar_t       kMappingName[] = L"Local\\SkyCraft_MW2_v1";

	// ---- region offsets ---------------------------------------------------------------------
	inline constexpr std::uint64_t kOffHeader = 0x0;
	inline constexpr std::uint64_t kOffControl = 0x100;
	inline constexpr std::uint64_t kOffState = 0x200;
	inline constexpr std::uint64_t kOffOverlayCtl = 0x300;
	inline constexpr std::uint64_t kOffOverlaySlotHdr = 0x340;  // 3 x 0x40
	inline constexpr std::uint64_t kOffShotRing = 0x1000;       // guest -> MC
	inline constexpr std::uint64_t kOffHitRing = 0x9000;        // MC -> guest
	inline constexpr std::uint64_t kOffOverlayPixels = 0x10000;
	inline constexpr std::uint32_t kMaxOverlayW = 3840;
	inline constexpr std::uint32_t kMaxOverlayH = 2160;
	inline constexpr std::uint64_t kOverlaySlotBytes = std::uint64_t(kMaxOverlayW) * kMaxOverlayH * 4;
	inline constexpr std::uint32_t kOverlaySlots = 3;
	inline constexpr std::uint64_t kMappingBytes = kOffOverlayPixels + kOverlaySlotBytes * kOverlaySlots;

	// ---- header @0x0 ------------------------------------------------------------------------
	struct Header
	{
		std::uint32_t magic;
		std::uint32_t version;
		std::uint32_t guestPid;
		std::uint32_t pad0C;
		std::uint64_t hostHeartbeatMs;   // GetTickCount64() at the last Skyrim frame
		std::uint64_t guestHeartbeatMs;  // GetTickCount64() at the last guest frame
		std::uint32_t viewportW;         // what the guest renders at: Skyrim's back buffer
		std::uint32_t viewportH;
	};
	static_assert(sizeof(Header) == 0x28);

	// ---- Minecraft -> guest @0x100 (seqlock: seq odd while writing) -------------------------
	enum Weapon : std::uint32_t
	{
		kWeaponNone = 0,
		kWeaponIntervention = 1,  // cheytac_mp
		kWeaponSpas12 = 2,        // spas12_mp
		kWeaponUmp45 = 3,         // ump45_mp
	};

	enum Buttons : std::uint32_t
	{
		kBtnAttack = 1u << 0,
		kBtnAds = 1u << 1,
		kBtnReload = 1u << 2,
		kBtnMelee = 1u << 3,
		kBtnSprint = 1u << 4,
		kBtnCrouch = 1u << 5,
		kBtnJump = 1u << 6,
		kBtnHoldBreath = 1u << 7,
	};

	struct Control
	{
		std::uint32_t seq;
		std::uint32_t weapon;   // Weapon held in Minecraft's hand
		std::uint32_t buttons;  // Buttons held this frame
		std::uint32_t pad0C;
		std::uint64_t mcFrame;  // Minecraft frame counter, for staleness checks
	};
	static_assert(sizeof(Control) == 0x18);

	// ---- guest -> Minecraft @0x200 (seqlock) ------------------------------------------------
	enum StateFlags : std::uint32_t
	{
		kStReady = 1u << 0,      // a match is up and the soldier is spawned
		kStReloading = 1u << 1,
		kStFiring = 1u << 2,
		kStAds = 1u << 3,        // fully aimed down sights
	};

	struct State
	{
		std::uint32_t seq;
		std::uint32_t flags;      // StateFlags
		std::uint32_t weapon;     // the weapon the guest has up (may lag Control::weapon while switching)
		std::int32_t  ammoClip;
		std::int32_t  ammoStock;
		float         adsFraction;  // 0 hip .. 1 fully aimed
		float         fovScale;     // multiply Minecraft's FOV by this (ADS zoom); 1 = unchanged
		float         spread;       // current hip spread, degrees
		std::uint64_t guestFrame;
	};
	static_assert(sizeof(State) == 0x28);

	// ---- overlay triple buffer @0x300 (guest -> Skyrim) -------------------------------------
	// Same scheme as the main protocol's overlay: state bits 0-1 = middle slot, bit 2 = dirty.
	// Pixels are RGBA8, straight (not premultiplied) alpha, top-down rows of width * 4 bytes.
	inline constexpr std::uint32_t kOverlayDirty = 1u << 2;

	struct OverlayCtl
	{
		std::uint32_t state;
		std::uint32_t pad;
		std::uint64_t framesPublished;
	};

	struct OverlaySlotHdr
	{
		std::uint32_t width;
		std::uint32_t height;
		std::uint32_t flags;  // reserved
		std::uint32_t pad;
		std::uint64_t frameId;
		std::uint8_t  reserved[0x40 - 0x18];
	};
	static_assert(sizeof(OverlaySlotHdr) == 0x40);

	// ---- shot ring @0x1000 (guest -> MC), hit ring @0x9000 (MC -> guest) ----------------------
	// SPSC rings: u64 head (writer) at +0x00, u64 tail (reader) at +0x40, entries from +0x80.
	inline constexpr std::uint64_t kRingHeadOff = 0x00;
	inline constexpr std::uint64_t kRingTailOff = 0x40;
	inline constexpr std::uint64_t kRingDataOff = 0x80;
	inline constexpr std::uint32_t kShotRingEntries = 256;  // power of two
	inline constexpr std::uint32_t kHitRingEntries = 256;

	// One bullet. A shotgun blast is one Shot per pellet with the same `blast` id.
	struct Shot
	{
		std::uint32_t id;
		std::uint32_t blast;
		std::uint32_t weapon;
		std::uint32_t pad0C;
		double        originX, originY, originZ;  // muzzle ray start (the eye), MC coords
		float         dirX, dirY, dirZ;           // unit direction, spread already applied
		float         range;                       // blocks
		float         damage;          // MW2 damage up to damageRange
		float         minDamage;
		float         damageRange;     // blocks: full damage up to here
		float         minDamageRange;  // blocks: minDamage from here on, linear in between
		float         headMultiplier;
		float         pad4C;
	};
	static_assert(sizeof(Shot) == 0x50);

	enum HitKind : std::uint32_t
	{
		kHitMiss = 0,
		kHitWorld = 1,  // Skyrim geometry (CollisionField)
		kHitBlock = 2,  // a Minecraft block
		kHitActor = 3,  // a Skyrim NPC (actor proxy)
		kHitKill = 4,   // ...and it died
		kHitMob = 5,    // a Minecraft mob
	};

	struct Hit
	{
		std::uint32_t shotId;
		std::uint32_t kind;  // HitKind
		double        x, y, z;  // impact point, MC coords
		float         nx, ny, nz;  // surface normal
		float         damage;      // what was dealt (MC hearts for mobs, Skyrim HP for NPCs)
		std::uint32_t headshot;
		std::uint32_t pad2C;
	};
	static_assert(sizeof(Hit) == 0x38);

	static_assert(kOffShotRing + kRingDataOff + kShotRingEntries * sizeof(Shot) <= kOffHitRing);
	static_assert(kOffHitRing + kRingDataOff + kHitRingEntries * sizeof(Hit) <= kOffOverlayPixels);
}
