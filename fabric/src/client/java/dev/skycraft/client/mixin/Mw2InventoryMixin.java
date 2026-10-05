package dev.skycraft.client.mixin;

import dev.skycraft.client.Mw2Client;
import dev.skycraft.client.Mw2InventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** While MW2's guest draws the inventory (Mw2Client), the inventory key opens Mw2InventoryScreen. */
@Mixin(Gui.class)
public abstract class Mw2InventoryMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen skycraft$mw2Inventory(Screen screen) {
		if (screen != null && screen.getClass() == InventoryScreen.class && Mw2Client.active()) {
			var player = Minecraft.getInstance().player;
			if (player != null) {
				return new Mw2InventoryScreen(player);
			}
		}
		return screen;
	}
}
