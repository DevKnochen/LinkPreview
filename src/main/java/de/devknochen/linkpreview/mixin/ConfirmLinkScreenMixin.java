package de.devknochen.linkpreview.mixin;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screens.ConfirmLinkScreen;

@Mixin(ConfirmLinkScreen.class)
public abstract class ConfirmLinkScreenMixin {
	@Inject(method = "confirmMessage(ZLjava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", at = @At("HEAD"), cancellable = true)
	private static void linkpreview$styleConfirmUrl(boolean trusted, String url, CallbackInfoReturnable<MutableComponent> info) {
		String key = trusted ? "chat.link.confirmTrusted" : "chat.link.confirm";
		MutableComponent styledUrl = Component.literal(url)
				.withStyle(style -> style.withColor(TextColor.fromRgb(0x2F81F7)))
				.withStyle(style -> style.withUnderlined(true));

		info.setReturnValue(Component.translatable(key)
				.append(CommonComponents.SPACE)
				.append(styledUrl));
	}
}
