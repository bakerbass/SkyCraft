package dev.skycraft.client.mixin;

import dev.skycraft.client.Mw2Client;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MW2's guest draws the gun or MW2's bare hands (Mw2Client.drawsHand): Minecraft's hand stays out. */
@Mixin(GameRenderer.class)
public abstract class Mw2HandMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void skycraft$mw2Hand(CallbackInfo ci) {
		if (Mw2Client.drawsHand()) {
			ci.cancel();
		}
	}
}
