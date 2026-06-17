package de.devknochen.linkpreview.mixin;

import java.util.List;

import de.devknochen.linkpreview.LinkPreviewChatAccess;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChatHud.class)
public abstract class ChatComponentAccessor implements LinkPreviewChatAccess {
	@Accessor("visibleMessages")
	public abstract List<ChatHudLine.Visible> linkpreview$visibleMessages();

	@Accessor("messages")
	public abstract List<ChatHudLine> linkpreview$messages();

	@Accessor("scrolledLines")
	public abstract int linkpreview$scrolledLines();

	@Invoker("addMessage")
	public abstract void linkpreview$addMessage(Text message, MessageSignatureData signature, int ticks, MessageIndicator indicator, boolean refresh);
}
