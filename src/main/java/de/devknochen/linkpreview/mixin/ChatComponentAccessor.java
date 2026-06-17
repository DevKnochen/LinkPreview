package de.devknochen.linkpreview.mixin;

import java.util.List;

import de.devknochen.linkpreview.LinkPreviewChatAccess;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;

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

	@Invoker("addVisibleMessage")
	public abstract void linkpreview$invokeAddVisibleMessage(ChatHudLine message);

	@Invoker("addMessage")
	public abstract void linkpreview$invokeAddMessage(ChatHudLine message);
}
