package de.devknochen.linkpreview.mixin;

import java.util.List;

import de.devknochen.linkpreview.LinkPreviewChatAccess;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor extends LinkPreviewChatAccess {
	@Accessor("trimmedMessages")
	List<GuiMessage.Line> linkpreview$trimmedMessages();

	@Accessor("allMessages")
	List<GuiMessage> linkpreview$allMessages();

	@Accessor("chatScrollbarPos")
	int linkpreview$chatScrollbarPos();

	@Invoker("addMessage")
	void linkpreview$addMessage(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag);

	@Invoker("addMessageToDisplayQueue")
	void linkpreview$addMessageToDisplayQueue(GuiMessage message);

	@Invoker("addMessageToQueue")
	void linkpreview$addMessageToQueue(GuiMessage message);
}
