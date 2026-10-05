package dev.skycraft.mw2;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Mw2Proto;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
