package de.devknochen.linkpreview;

import java.util.List;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

public interface LinkPreviewChatAccess {
	List<GuiMessage.Line> linkpreview$trimmedMessages();

	List<GuiMessage> linkpreview$allMessages();

	int linkpreview$chatScrollbarPos();

	void linkpreview$addMessage(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag);

	void linkpreview$addMessageToDisplayQueue(GuiMessage message);

	void linkpreview$addMessageToQueue(GuiMessage message);
}
