package de.devknochen.linkpreview.mixin;

import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screen.ConfirmLinkScreen;

@Mixin(ConfirmLinkScreen.class)
public abstract class ConfirmLinkScreenMixin {
	@ModifyArg(
			method = "<init>(Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Ljava/lang/String;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/ConfirmLinkScreen;<init>(Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;Lnet/minecraft/text/Text;Lnet/minecraft/text/Text;Ljava/lang/String;Lnet/minecraft/text/Text;Z)V"),
			index = 2
	)
	private static Text linkpreview$styleRawConfirmUrl(Text message) {
		String url = message.getString();
		if (!url.startsWith("http" + "://") && !url.startsWith("https" + "://")) {
			return message;
		}

		return styledUrl(url);
	}

	@Inject(method = "getConfirmText(ZLjava/lang/String;)Lnet/minecraft/text/MutableText;", at = @At("HEAD"), cancellable = true)
	private static void linkpreview$styleConfirmUrl(boolean trusted, String url, CallbackInfoReturnable<MutableText> info) {
		String key = trusted ? "chat.link.confirmTrusted" : "chat.link.confirm";

		info.setReturnValue(Text.translatable(key)
				.append(ScreenTexts.SPACE)
				.append(styledUrl(url)));
	}

	private static MutableText styledUrl(String url) {
		return Text.literal(url)
				.styled(style -> style.withColor(TextColor.fromRgb(0x2F81F7)))
				.styled(style -> style.withUnderline(true));
	}
}
