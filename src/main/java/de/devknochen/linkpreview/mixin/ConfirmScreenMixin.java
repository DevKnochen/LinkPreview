package de.devknochen.linkpreview.mixin;

import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ConfirmScreen.class)
public abstract class ConfirmScreenMixin {
	@ModifyArg(
			method = "init",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/MultilineTextWidget;<init>(Lnet/minecraft/text/Text;Lnet/minecraft/client/font/TextRenderer;)V"),
			index = 0
	)
	private Text linkpreview$styleConfirmLinkMessage(Text message) {
		if (!"net.minecraft.client.gui.screen.ConfirmLinkScreen".equals(((Object) this).getClass().getName())) {
			return message;
		}

		String url = message.getString();
		if (!url.startsWith("http" + "://") && !url.startsWith("https" + "://")) {
			return message;
		}

		return Text.literal(url)
				.styled(style -> style.withColor(TextColor.fromRgb(0x2F81F7)).withUnderline(true));
	}
}
