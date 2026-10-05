package dev.skycraft.client.mixin;

import dev.skycraft.client.Mw2Client;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** MW2's aim-down-sights zoom on Minecraft's camera, which Skyrim's follows (Mw2Client.zoomFov). */
@Mixin(Camera.class)
public abstract class Mw2ZoomMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void skycraft$adsZoom(float partialTick, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(Mw2Client.zoomFov(cir.getReturnValueF()));
	}
}
