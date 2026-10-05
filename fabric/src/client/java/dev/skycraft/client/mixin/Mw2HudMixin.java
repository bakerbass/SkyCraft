package dev.skycraft.client.mixin;

import dev.skycraft.client.Mw2Client;
import dev.skycraft.client.Mw2InventoryScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While MW2's guest draws the hotbar (Mw2Client), Minecraft leaves out its own hotbar, the name of
 * the selected item and its crosshair (MW2's reticle is there), and all of its HUD while the MW2
 * inventory is open over it.
 */
@Mixin(Hud.class)
public abstract class Mw2HudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void skycraft$hideUnderMw2Inventory(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (Mw2Client.active() && Minecraft.getInstance().gui.screen() instanceof Mw2InventoryScreen) {
			ci.cancel();
		}
	}

	@Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
	private void skycraft$mw2Hotbar(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (Mw2Client.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
	private void skycraft$mw2ItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (Mw2Client.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void skycraft$mw2Reticle(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (Mw2Client.active()) {
			ci.cancel();
		}
	}
}
