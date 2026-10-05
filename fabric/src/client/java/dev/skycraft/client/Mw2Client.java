package dev.skycraft.client;

import static dev.skycraft.link.Mw2Proto.*;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Mw2Link;
import dev.skycraft.mw2.Mw2Guns;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/**
 * MW2's guns, Minecraft's side (docs/MW2.md). While the IW4L guest is up, it draws the hotbar and
 * the inventory in MW2's style, with the MW2 soldier where Minecraft shows the player: Minecraft
 * keeps the inventory itself, publishes it every frame, hides its own hotbar, crosshair and hand,
 * and applies the clicks the guest's inventory screen makes with vanilla's container rules.
 */
public final class Mw2Client {
	private static boolean active;
	private static int gunsAskedFor = -1;
	private static long frame;

	private Mw2Client() {
	}

	/** The guest owns the hotbar and inventory this frame. */
	public static boolean active() {
		return active;
	}

	/**
	 * Minecraft's hand stays out: the guest draws a gun, or MW2's bare hands for an empty hand, and
	 * its inventory screen goes over everything.
	 */
	public static boolean drawsHand() {
		if (!active) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return false;
		}
		if (minecraft.gui.screen() instanceof Mw2InventoryScreen) {
			return true;
		}
		ItemStack held = player.getMainHandItem();
		return held.isEmpty() || Mw2Guns.weaponOf(held) != WEAPON_NONE;
	}

	/** Once a frame, after Skyrim's input has been replayed. */
	public static void frame(Minecraft minecraft) {
		Mw2Link.poll();
		LocalPlayer player = minecraft.player;
		boolean now = SkyClient.linked() && Mw2Link.guestReady() && player != null && minecraft.level != null;
		if (now != active) {
			active = now;
			SkyCraft.LOG.info("SkyCraft: MW2 hotbar and inventory {}", active ? "on" : "off");
		}
		if (!Mw2Link.open()) {
			return;
		}
		frame++;
		if (player == null || minecraft.gameMode == null) {
			Mw2Link.writeUi(0, 0, frame, null);
			Mw2Link.writeControl(WEAPON_NONE, 0, frame);
			return;
		}
		if (active && gunsAskedFor != Mw2Link.session() && ClientPlayNetworking.canSend(dev.skycraft.net.SkyNet.RequestMw2Guns.TYPE)) {
			gunsAskedFor = Mw2Link.session();
			ClientPlayNetworking.send(dev.skycraft.net.SkyNet.RequestMw2Guns.INSTANCE);
		}
		applyClicks(minecraft, player);
		publish(minecraft, player);
		Mw2Link.writeControl(Mw2Guns.weaponOf(player.getMainHandItem()), 0, frame);
	}

	private static void publish(Minecraft minecraft, LocalPlayer player) {
		int flags = active ? UI_ACTIVE : 0;
		if (active && minecraft.gui.screen() instanceof Mw2InventoryScreen) {
			flags |= UI_INVENTORY_OPEN;
		}
		var inventory = player.getInventory();
		InventoryMenu menu = player.inventoryMenu;
		Mw2Link.writeUi(flags, inventory.getSelectedSlot(), frame, (index, out) -> {
			ItemStack stack;
			if (index < UI_INVENTORY_SLOTS) {
				stack = inventory.getItem(index);
			} else if (index < UI_RESULT) {
				stack = menu.getSlot(InventoryMenu.CRAFT_SLOT_START + index - UI_CRAFTING).getItem();
			} else if (index == UI_RESULT) {
				stack = menu.getSlot(InventoryMenu.RESULT_SLOT).getItem();
			} else {
				stack = player.containerMenu.getCarried();
			}
			describe(stack, out);
		});
	}

	private static void describe(ItemStack stack, Mw2Link.Stack out) {
		if (stack.isEmpty()) {
			return;
		}
		out.id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		out.weapon = Mw2Guns.weaponOf(stack);
		out.count = stack.getCount();
		if (stack.isDamaged() && stack.has(DataComponents.MAX_DAMAGE)) {
			out.durability = 1.0F - (float) stack.getDamageValue() / stack.getMaxDamage();
		}
	}

	/** A stack index of the snapshot as a slot of the player's InventoryMenu. */
	private static int menuSlot(int index) {
		if (index < 9) {
			return InventoryMenu.USE_ROW_SLOT_START + index;
		}
		if (index < 36) {
			return index;
		}
		if (index < 40) {
			// Inventory 36..39 is feet..head; the menu's armour slots run head..feet.
			return InventoryMenu.ARMOR_SLOT_START + (39 - index);
		}
		if (index == 40) {
			return InventoryMenu.SHIELD_SLOT;
		}
		if (index < UI_RESULT) {
			return InventoryMenu.CRAFT_SLOT_START + index - UI_CRAFTING;
		}
		return InventoryMenu.RESULT_SLOT;
	}

	private static void applyClicks(Minecraft minecraft, LocalPlayer player) {
		Mw2Link.drainClicks((kind, slot, flags, hotbar, mask) -> {
			// Only on the inventory screen: a stale click must not land in a chest.
			if (!(minecraft.gui.screen() instanceof Mw2InventoryScreen) || player.containerMenu != player.inventoryMenu) {
				return;
			}
			int id = player.inventoryMenu.containerId;
			int button = (flags & UI_CLICK_RIGHT) != 0 ? 1 : 0;
			var game = minecraft.gameMode;
			switch (kind) {
				case UI_CLICK_SLOT -> {
					if (slot >= 0 && slot <= UI_RESULT) {
						boolean shift = (flags & UI_CLICK_SHIFT) != 0;
						game.handleContainerInput(id, menuSlot(slot), button, shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, player);
					}
				}
				case UI_CLICK_OUTSIDE -> game.handleContainerInput(id, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, button, ContainerInput.PICKUP, player);
				case UI_CLICK_GATHER -> {
					if (slot >= 0 && slot < UI_RESULT) {
						game.handleContainerInput(id, menuSlot(slot), 0, ContainerInput.PICKUP_ALL, player);
					}
				}
				case UI_CLICK_SWAP -> {
					if (slot >= 0 && slot <= UI_RESULT && hotbar >= 0 && hotbar < 9) {
						game.handleContainerInput(id, menuSlot(slot), hotbar, ContainerInput.SWAP, player);
					}
				}
				case UI_CLICK_SPREAD -> {
					// Vanilla's drag: start, each slot, end.
					game.handleContainerInput(id, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(0, button), ContainerInput.QUICK_CRAFT, player);
					for (int i = 0; i < UI_INVENTORY_SLOTS; i++) {
						if ((mask & (1L << i)) != 0) {
							game.handleContainerInput(id, menuSlot(i), AbstractContainerMenu.getQuickcraftMask(1, button), ContainerInput.QUICK_CRAFT, player);
						}
					}
					game.handleContainerInput(id, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(2, button), ContainerInput.QUICK_CRAFT, player);
				}
				default -> {
				}
			}
		});
	}
}
