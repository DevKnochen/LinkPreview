package de.devknochen.linkpreview.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.ARGB;

import de.devknochen.linkpreview.PreviewCardStore;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Inject(method = "lambda$extractRenderState$1", at = @At("HEAD"), cancellable = true)
	private static void linkpreview$drawPreviewBackground(int chatBottom, int entryHeight, ChatComponent.ChatGraphicsAccess graphics, int maxWidth, float backgroundOpacity, GuiMessage.Line line, int lineIndex, float alpha, CallbackInfo info) {
		if (PreviewCardStore.isSpacerLine(line)) {
			info.cancel();
			return;
		}

		if (PreviewCardStore.isPreviewSourceLine(line)) {
			int entryBottom = chatBottom - lineIndex * entryHeight;
			int entryTop = entryBottom - entryHeight;
			int xLeft = -4;
			int xRight = maxWidth + 8;

			graphics.fill(xLeft, entryTop, xRight, entryBottom, ARGB.black(alpha * backgroundOpacity));
			graphics.fill(xLeft, entryTop, xLeft + 3, entryBottom, linkpreview$withAlpha(0xFF2F81F7, alpha));
			info.cancel();
		}
	}

	@Unique
	private static int linkpreview$withAlpha(int argb, float alphaScale) {
		int alpha = (argb >>> 24) & 0xFF;
		int scaledAlpha = Math.clamp(Math.round(alpha * alphaScale), 0, 255);
		return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
	}
}
