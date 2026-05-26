package de.devknochen.linkpreview.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.ARGB;

import de.devknochen.linkpreview.PreviewCardStore;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Inject(method = "lambda$extractRenderState$1", at = @At("HEAD"), cancellable = true)
	private static void linkpreview$drawPreviewBackground(int bottom, int lineHeight, ChatComponent.ChatGraphicsAccess graphics, int width, float backgroundOpacity, GuiMessage.Line line, int lineIndex, float alpha, CallbackInfo info) {
		if (PreviewCardStore.isSpacerLine(line)) {
			info.cancel();
			return;
		}

		if (PreviewCardStore.isPreviewSourceLine(line)) {
			int yBottom = bottom - lineIndex * lineHeight;
			int yTop = yBottom - lineHeight;
			int xLeft = -4;
			int xRight = width + 8;

			graphics.fill(xLeft, yTop, xRight, yBottom, ARGB.black(alpha * backgroundOpacity));
			graphics.fill(xLeft, yTop, xLeft + 3, yBottom, withAlpha(0xFF2F81F7, alpha));
			info.cancel();
		}
	}

	private static int withAlpha(int argb, float alphaScale) {
		int alpha = (argb >>> 24) & 0xFF;
		int scaledAlpha = Math.clamp(Math.round(alpha * alphaScale), 0, 255);
		return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
	}
}
