package dev.skycraft.client.mixin;

import dev.skycraft.client.Mw2Client;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With one of MW2's guns in hand, attack fires it and use aims down its sights (Mw2Client): Minecraft
 * doesn't punch, mine or use anything with them.
 */
@Mixin(Minecraft.class)
public abstract class Mw2AttackMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void skycraft$gunFires(CallbackInfoReturnable<Boolean> cir) {
		if (Mw2Client.holdsGun()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void skycraft$gunKeepsFiring(boolean down, CallbackInfo ci) {
		if (Mw2Client.holdsGun()) {
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void skycraft$gunAims(CallbackInfo ci) {
		if (Mw2Client.holdsGun()) {
			ci.cancel();
		}
	}
}
