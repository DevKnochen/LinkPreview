package de.devknochen.linkpreview;

import java.util.List;

import net.minecraft.client.gui.hud.ChatHudLine;

public interface LinkPreviewChatAccess {
	List<ChatHudLine.Visible> linkpreview$visibleMessages();

	List<ChatHudLine> linkpreview$messages();

	int linkpreview$scrolledLines();

	void linkpreview$invokeAddVisibleMessage(ChatHudLine message);

	void linkpreview$invokeAddMessage(ChatHudLine message);

	default void linkpreview$addVisibleMessage(ChatHudLine message) {
		linkpreview$invokeAddVisibleMessage(message);
	}

	default void linkpreview$addMessageToQueue(ChatHudLine message) {
		linkpreview$invokeAddMessage(message);
	}
}
