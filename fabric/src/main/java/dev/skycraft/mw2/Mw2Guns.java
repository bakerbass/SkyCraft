package dev.skycraft.mw2;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Mw2Proto;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import dev.skycraft.link.Mw2Link;
import dev.skycraft.net.SkyNet;
import java.util.ArrayList;
import java.util.List;

/**
 * MW2's guns as Minecraft items (docs/MW2.md). Minecraft owns which one is held; the IW4L guest
 * plays it. In the guest's MW2-style hotbar and inventory they show as MW2's own weapon icons.
 */
public final class Mw2Guns {
	public static final Item INTERVENTION = register("mw2_intervention");
	public static final Item SPAS12 = register("mw2_spas12");
	public static final Item UMP45 = register("mw2_ump45");

	private static final Item[] ALL = { INTERVENTION, SPAS12, UMP45 };

	private Mw2Guns() {
	}

	private static Item register(String name) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
	}

	/** Loads the class, registering the items. */
	public static void init() {
	}

	/** The MW2 weapon (Mw2Proto.WEAPON_*) a stack is, or WEAPON_NONE. */
	public static int weaponOf(ItemStack stack) {
		Item item = stack.getItem();
		if (item == INTERVENTION) {
			return Mw2Proto.WEAPON_INTERVENTION;
		}
		if (item == SPAS12) {
			return Mw2Proto.WEAPON_SPAS12;
		}
		if (item == UMP45) {
			return Mw2Proto.WEAPON_UMP45;
		}
		return Mw2Proto.WEAPON_NONE;
	}

	/** MW2's damage is out of a player's 100 health, Minecraft's out of 20. */
	private static final float MW2_TO_MC_DAMAGE = 5.0F;
	/** The top of a body this tall a share of its height is its head. */
	private static final double HEAD_SHARE = 0.2;
	/** Furthest a bullet is traced, blocks. */
	private static final double MAX_RANGE = 512.0;

	/**
	 * Traces the bullets the player's guest fired, from the player's eye: Minecraft blocks and
	 * Skyrim's geometry stop them; Skyrim actors' stand-ins and mobs take MW2's damage (falloff with
	 * range, head and torso multipliers) in Minecraft units, which Skyrim scales by level as for any
	 * Minecraft weapon. Returns what each bullet hit.
	 */
	public static List<SkyNet.Mw2Hit> fire(ServerPlayer player, List<Mw2Link.Shot> shots) {
		List<SkyNet.Mw2Hit> hits = new ArrayList<>();
		if (!player.isAlive() || weaponOf(player.getMainHandItem()) == Mw2Proto.WEAPON_NONE) {
			return hits;
		}
		var level = player.level();
		Vec3 from = player.getEyePosition();
		for (Mw2Link.Shot shot : shots) {
			Vec3 dir = new Vec3(shot.dirX(), shot.dirY(), shot.dirZ());
			if (!(dir.lengthSqr() > 1e-6) || !Float.isFinite(shot.damage())) {
				continue;
			}
			dir = dir.normalize();
			double range = Math.max(1.0, Math.min(MAX_RANGE, shot.range()));
			Vec3 to = from.add(dir.scale(range));
			BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			block = dev.skycraft.world.SkyClip.refine(from, to, block, dev.skycraft.world.SkyClip.Use.PROJECTILE);
			Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();

			Entity target = null;
			Vec3 at = null;
			double nearest = Double.MAX_VALUE;
			for (Entity e : level.getEntities(player, new AABB(from, end).inflate(1.0),
				e -> e instanceof LivingEntity living && living.isAlive() && !(e instanceof Player))) {
				var point = e.getBoundingBox().inflate(0.1).clip(from, end);
				if (point.isPresent() && from.distanceToSqr(point.get()) < nearest) {
					nearest = from.distanceToSqr(point.get());
					target = e;
					at = point.get();
				}
			}
			if (target == null) {
				int kind = block.getType() == HitResult.Type.MISS ? Mw2Proto.HIT_MISS
					: level.getBlockState(block.getBlockPos()).isAir() ? Mw2Proto.HIT_WORLD : Mw2Proto.HIT_BLOCK;
				hits.add(new SkyNet.Mw2Hit(shot.id(), kind, end.x, end.y, end.z, 0.0F, false));
				continue;
			}
			var box = target.getBoundingBox();
			boolean head = at.y >= box.maxY - box.getYsize() * HEAD_SHARE;
			float mw2 = falloff(shot, Math.sqrt(nearest)) * Math.min(5.0F, Math.max(0.0F, head ? shot.headMultiplier() : shot.bodyMultiplier()));
			float damage = Math.min(mw2, 1000.0F) / MW2_TO_MC_DAMAGE;
			int kind;
			if (target instanceof dev.skycraft.combat.SkyrimActorEntity actor) {
				actor.gunHit(damage, dir.x, dir.z, head);
				kind = Mw2Proto.HIT_ACTOR;
			} else {
				LivingEntity living = (LivingEntity) target;
				// One bullet after another: no invulnerability frames between them.
				living.setInvulnerableTime(0);
				living.hurtServer(level, level.damageSources().playerAttack(player), damage);
				living.setInvulnerableTime(0);
				kind = living.isAlive() ? Mw2Proto.HIT_MOB : Mw2Proto.HIT_KILL;
			}
			hits.add(new SkyNet.Mw2Hit(shot.id(), kind, at.x, at.y, at.z, mw2, head));
		}
		return hits;
	}

	/** MW2's damage at a range (blocks): full to damageRange, minDamage from minDamageRange, linear between. */
	static float falloff(Mw2Link.Shot shot, double distance) {
		float near = Math.max(0.0F, shot.damageRange());
		float far = Math.max(near, shot.minDamageRange());
		if (distance <= near || far <= near) {
			return Math.max(0.0F, shot.damage());
		}
		if (distance >= far) {
			return Math.max(0.0F, shot.minDamage());
		}
		float t = (float) ((distance - near) / (far - near));
		return Math.max(0.0F, shot.damage() + (shot.minDamage() - shot.damage()) * t);
	}

	/** Gives the player each gun they don't carry (their Minecraft has MW2's guns on). */
	public static void giveMissing(ServerPlayer player) {
		var inventory = player.getInventory();
		for (Item gun : ALL) {
			if (!inventory.contains(stack -> stack.is(gun)) && !player.containerMenu.getCarried().is(gun)) {
				inventory.add(new ItemStack(gun));
				SkyCraft.LOG.info("SkyCraft: gave {} {}", player.getName().getString(), BuiltInRegistries.ITEM.getKey(gun));
			}
		}
	}
}
