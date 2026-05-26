package de.devknochen.linkpreview.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.FormattedCharSequence;

import de.devknochen.linkpreview.PreviewCardStore;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$1")
public abstract class ChatComponentLineMixin {
	@Redirect(
			method = "accept(Lnet/minecraft/client/multiplayer/chat/GuiMessage$Line;IF)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleMessage(IFLnet/minecraft/util/FormattedCharSequence;)Z")
	)
	private boolean linkpreview$keepPreviewSourceTextBright(ChatComponent.ChatGraphicsAccess graphics, int y, float alpha, FormattedCharSequence message, GuiMessage.Line line, int lineIndex, float lineAlpha) {
		if (PreviewCardStore.isSpacerLine(line)) {
			return false;
		}

		return graphics.handleMessage(y, alpha, message);
	}
}
