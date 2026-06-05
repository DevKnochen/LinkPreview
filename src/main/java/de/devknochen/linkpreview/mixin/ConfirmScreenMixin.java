package de.devknochen.linkpreview.mixin;

import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ConfirmScreen.class)
public abstract class ConfirmScreenMixin {
	@ModifyArg(
			method = "init",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/MultiLineTextWidget;<init>(Lnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/Font;)V"),
			index = 0
	)
	private Component linkpreview$styleConfirmLinkMessage(Component message) {
		if (!ConfirmLinkScreen.class.isInstance(this)) {
			return message;
		}

		String url = message.getString();
		if (!url.startsWith("http" + "://") && !url.startsWith("https" + "://")) {
			return message;
		}

		return Component.literal(url)
				.withStyle(style -> style.withColor(TextColor.fromRgb(0x2F81F7)).withUnderlined(true));
	}
}
