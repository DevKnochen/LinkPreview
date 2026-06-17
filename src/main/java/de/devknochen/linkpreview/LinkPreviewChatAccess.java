package de.devknochen.linkpreview;

import java.util.List;

import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;

public interface LinkPreviewChatAccess {
	List<ChatHudLine.Visible> linkpreview$visibleMessages();

	List<ChatHudLine> linkpreview$messages();

	int linkpreview$scrolledLines();

	void linkpreview$addMessage(Text message, MessageSignatureData signature, int ticks, MessageIndicator indicator, boolean refresh);

	default void linkpreview$addVisibleMessage(ChatHudLine message) {
		linkpreview$addMessage(message.content(), message.signature(), message.creationTick(), message.indicator(), true);
	}

	default void linkpreview$addMessageToQueue(ChatHudLine message) {
		linkpreview$messages().add(0, message);
		while (linkpreview$messages().size() > 100) {
			linkpreview$messages().remove(linkpreview$messages().size() - 1);
		}
	}
}
