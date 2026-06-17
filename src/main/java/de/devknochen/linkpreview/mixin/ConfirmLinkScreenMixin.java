package de.devknochen.linkpreview.mixin;

import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screen.ConfirmLinkScreen;

@Mixin(ConfirmLinkScreen.class)
public abstract class ConfirmLinkScreenMixin {
	@Inject(method = "getConfirmText(ZLjava/lang/String;)Lnet/minecraft/text/MutableText;", at = @At("HEAD"), cancellable = true)
	private static void linkpreview$styleConfirmUrl(boolean trusted, String url, CallbackInfoReturnable<MutableText> info) {
		String key = trusted ? "chat.link.confirmTrusted" : "chat.link.confirm";
		MutableText styledUrl = Text.literal(url)
				.styled(style -> style.withColor(TextColor.fromRgb(0x2F81F7)))
				.styled(style -> style.withUnderline(true));

		info.setReturnValue(Text.translatable(key)
				.append(ScreenTexts.SPACE)
				.append(styledUrl));
	}
}
