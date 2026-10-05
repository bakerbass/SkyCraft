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
import dev.skycraft.link.Mw2Link;

/**
 * While MW2's guest draws the hotbar (Mw2Client), Minecraft leaves out its own hotbar, the name of
 * the selected item and its crosshair (MW2's reticle is there), and all of its HUD while the MW2
 * inventory is open over it. Health, hunger, armour and XP, and the action bar over them, move up
 * to sit on MW2's hotbar, which is taller than Minecraft's.
 */
@Mixin(Hud.class)
public abstract class Mw2HudMixin {
	/** Minecraft's hotbar: what its health, hunger and XP sit on. */
	private static final int VANILLA_HOTBAR = 22;
	/** Air between MW2's hotbar (its selected slot's glow rises above it) and the bars. */
	private static final int GAP = 4;

	private static float skycraft$lift(GuiGraphicsExtractor graphics) {
		float top = Mw2Link.hotbarTop();
		if (!Mw2Client.active() || top <= 0.0F) {
			return 0.0F;
		}
		return Math.max(0.0F, (1.0F - top) * graphics.guiHeight() - VANILLA_HOTBAR + GAP);
	}

	@Inject(method = {"extractHotbarAndDecorations", "extractOverlayMessage"}, at = @At("HEAD"))
	private void skycraft$liftOverMw2Hotbar(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(0.0F, -skycraft$lift(graphics));
	}

	@Inject(method = {"extractHotbarAndDecorations", "extractOverlayMessage"}, at = @At("RETURN"))
	private void skycraft$liftDone(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		graphics.pose().popMatrix();
	}

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
