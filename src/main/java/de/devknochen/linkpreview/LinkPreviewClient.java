package de.devknochen.linkpreview;

import java.net.http.HttpClient;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.mojang.authlib.GameProfile;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.resources.Identifier;

import de.devknochen.linkpreview.mixin.ChatComponentAccessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinkPreviewClient implements ClientModInitializer {
	public static final String MOD_ID = "linkpreview";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		PreviewService previewService = new PreviewService(
				HttpClient.newBuilder()
						.connectTimeout(Duration.ofMillis(PreviewService.REQUEST_TIMEOUT_MILLIS))
						.followRedirects(HttpClient.Redirect.NEVER)
						.build()
		);
		PreviewCardStore cardStore = new PreviewCardStore();
		PreviewImageService imageService = new PreviewImageService(previewService.httpClient());
		PreviewRenderer renderer = new PreviewRenderer(cardStore, imageService);

		HudElementRegistry.attachElementAfter(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "preview_cards"),
				cardStore::render
		);

		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, playerChatMessage, sender, boundChatType, timeStamp) -> {
			String rawMessage = message.getString();
			List<String> urls = UrlExtractor.findUrls(rawMessage);
			if (urls.isEmpty()) {
				return true;
			}

			List<PendingPreview> previews = reservePreviews(urls, renderer);
			MessageSignature signature = playerChatMessage == null ? null : playerChatMessage.signature();
			insertLinkMessageWithPreviewSpace(rawMessage, previews, GuiMessageSource.PLAYER, signature, sender);
			fetchPreviews(previews, previewService, renderer);
			return false;
		});

		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay) {
				return true;
			}

			String rawMessage = message.getString();
			List<String> urls = UrlExtractor.findUrls(rawMessage);
			if (urls.isEmpty()) {
				return true;
			}

			List<PendingPreview> previews = reservePreviews(urls, renderer);
			insertLinkMessageWithPreviewSpace(rawMessage, previews, GuiMessageSource.SYSTEM_CLIENT, null, null);
			fetchPreviews(previews, previewService, renderer);
			return false;
		});

		LOGGER.info("LinkPreview initialized");
	}

	private static List<PendingPreview> reservePreviews(List<String> urls, PreviewRenderer renderer) {
		List<PendingPreview> previews = new ArrayList<>(urls.size());
		for (String url : urls) {
			previews.add(new PendingPreview(renderer.reserve(url), url));
		}

		return previews;
	}

	private static void fetchPreviews(List<PendingPreview> previews, PreviewService previewService, PreviewRenderer renderer) {
		for (PendingPreview preview : previews) {
			previewService.fetch(preview.url()).thenAccept(response -> renderer.complete(preview.id(), preview.url(), response));
		}
	}

	private static void insertLinkMessageWithPreviewSpace(String rawMessage, List<PendingPreview> previews, GuiMessageSource source, MessageSignature signature, GameProfile sender) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui == null) {
			return;
		}

		Component message = LinkifiedText.from(rawMessage);
		if (source == GuiMessageSource.PLAYER) {
			message = decorateForChatHeads(minecraft, message, sender);
		}

		addUntaggedMessage(minecraft, message, source, signature);
		reservePreviewSpace(previews);
	}

	private static Component decorateForChatHeads(Minecraft minecraft, Component message, GameProfile sender) {
		try {
			Class<?> chatHeads = Class.forName("dzwdz.chat_heads.ChatHeads");
			Method handleAddedMessage = chatHeads.getMethod("handleAddedMessage", Component.class, PlayerInfo.class);
			return (Component) handleAddedMessage.invoke(null, message, playerInfo(minecraft, sender));
		} catch (ReflectiveOperationException exception) {
			return message;
		}
	}

	private static PlayerInfo playerInfo(Minecraft minecraft, GameProfile sender) {
		if (sender == null || minecraft.getConnection() == null) {
			return null;
		}

		return minecraft.getConnection().getPlayerInfo(sender.id());
	}

	private static void reservePreviewSpace(List<PendingPreview> previews) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui == null) {
			return;
		}

		for (PendingPreview preview : previews) {
			for (int line = 0; line < PreviewCardStore.spacerLines(true); line++) {
				addSilentMessage(minecraft, PreviewCardStore.spacerComponent(preview.id()), GuiMessageSource.SYSTEM_CLIENT, null);
			}
		}
	}

	private static void addSilentMessage(Minecraft minecraft, Component message, GuiMessageSource source, MessageSignature signature) {
		GuiMessage guiMessage = new GuiMessage(minecraft.gui.getGuiTicks(), message, signature, source, null);
		ChatComponentAccessor chat = (ChatComponentAccessor) minecraft.gui.getChat();
		chat.linkpreview$addMessageToDisplayQueue(guiMessage);
		chat.linkpreview$addMessageToQueue(guiMessage);
	}

	private static void addUntaggedMessage(Minecraft minecraft, Component message, GuiMessageSource source, MessageSignature signature) {
		if (source == GuiMessageSource.PLAYER) {
			minecraft.gui.getChat().addPlayerMessage(message, signature, null);
			return;
		}

		((ChatComponentAccessor) minecraft.gui.getChat()).linkpreview$addMessage(message, signature, source, null);
	}

	private record PendingPreview(long id, String url) {
	}
}
