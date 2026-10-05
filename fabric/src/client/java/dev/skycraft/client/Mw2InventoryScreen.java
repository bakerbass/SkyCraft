package dev.skycraft.client;

import static dev.skycraft.link.Mw2Proto.*;

import dev.skycraft.link.Mw2Link;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.Player;

/**
 * The player's inventory while MW2's guest draws it (Mw2Client): vanilla's screen, so the menu
 * opens, ticks and closes as usual and Skyrim sees a Minecraft screen open (its cursor shows), but
 * it draws nothing and hands the mouse and the number keys to the guest, whose clicks come back
 * through Mw2Client.
 */
public final class Mw2InventoryScreen extends InventoryScreen {
	public Mw2InventoryScreen(Player player) {
		super(player);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	public void containerTick() {
		super.containerTick();
		if (!Mw2Client.active()) {
			onClose();
		}
	}

	private void send(int kind, int code, InputWithModifiers input, double x, double y) {
		int mods = (input != null && input.hasShiftDown() ? UI_MOD_SHIFT : 0) | (input != null && input.hasControlDown() ? UI_MOD_CTRL : 0);
		Mw2Link.pushInput(kind, code, mods, (float) (x / Math.max(1, this.width)), (float) (y / Math.max(1, this.height)));
	}

	@Override
	public void mouseMoved(double x, double y) {
		send(UI_IN_MOVE, 0, null, x, y);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() <= 1) {
			send(UI_IN_BUTTON_DOWN, event.button(), event, event.x(), event.y());
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() <= 1) {
			send(UI_IN_BUTTON_UP, event.button(), event, event.x(), event.y());
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		send(UI_IN_MOVE, 0, event, event.x(), event.y());
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		return true;
	}

	private int hotbarKey(KeyEvent event) {
		var keys = this.minecraft.options.keyHotbarSlots;
		for (int i = 0; i < keys.length; i++) {
			if (keys[i].matches(event)) {
				return i + 1;
			}
		}
		return 0;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape() || this.minecraft.options.keyInventory.matches(event)) {
			onClose();
			return true;
		}
		int digit = hotbarKey(event);
		if (digit != 0) {
			send(UI_IN_KEY_DOWN, digit, event, lastX(), lastY());
		}
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		int digit = hotbarKey(event);
		if (digit != 0) {
			send(UI_IN_KEY_UP, digit, event, lastX(), lastY());
		}
		return true;
	}

	private double lastX() {
		return this.minecraft.mouseHandler.getScaledXPos(this.minecraft.getWindow());
	}

	private double lastY() {
		return this.minecraft.mouseHandler.getScaledYPos(this.minecraft.getWindow());
	}
}
