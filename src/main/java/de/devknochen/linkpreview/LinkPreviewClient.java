package de.devknochen.linkpreview;

import java.net.http.HttpClient;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import com.mojang.authlib.GameProfile;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;

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

		HudRenderCallback.EVENT.register((drawContext, tickDelta) -> cardStore.render(drawContext));

		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			String rawMessage = message.getString();
			List<String> urls = UrlExtractor.findUrls(rawMessage);
			if (urls.isEmpty()) {
				return true;
			}

			List<PendingPreview> previews = reservePreviews(urls, renderer);
			MessageSignatureData signature = signedMessage == null ? null : signedMessage.signature();
			insertLinkMessageWithPreviewSpace(rawMessage, previews, signature, sender);
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
			insertLinkMessageWithPreviewSpace(rawMessage, previews, null, null);
			fetchPreviews(previews, previewService, renderer);
			return false;
		});

		LOGGER.info("LinkPreview initialized");
	}

	private static List<PendingPreview> reservePreviews(List<String> urls, PreviewRenderer renderer) {
		return urls.stream()
				.map(url -> new PendingPreview(renderer.reserve(url), url))
				.toList();
	}

	private static void fetchPreviews(List<PendingPreview> previews, PreviewService previewService, PreviewRenderer renderer) {
		for (PendingPreview preview : previews) {
			previewService.fetch(preview.url()).thenAccept(response -> response.ifPresentOrElse(
					previewResponse -> renderer.complete(preview.id(), preview.url(), previewResponse),
					() -> renderer.discard(preview.id())
			));
		}
	}

	private static void insertLinkMessageWithPreviewSpace(String rawMessage, List<PendingPreview> previews, MessageSignatureData signature, GameProfile sender) {
		MinecraftClient minecraft = MinecraftClient.getInstance();
		Text message = LinkifiedText.from(rawMessage);
		if (sender != null) {
			message = decorateForChatHeads(minecraft, message, sender);
		}

		addUntaggedMessage(minecraft, message, signature);
		reservePreviewSpace(previews);
	}

	private static Text decorateForChatHeads(MinecraftClient minecraft, Text message, GameProfile sender) {
		try {
			Class<?> chatHeads = Class.forName("dzwdz.chat_heads.ChatHeads");
			Method handleAddedMessage = chatHeads.getMethod("handleAddedMessage", Text.class, PlayerListEntry.class);
			return (Text) handleAddedMessage.invoke(null, message, playerInfo(minecraft, sender));
		} catch (ReflectiveOperationException exception) {
			return message;
		}
	}

	private static PlayerListEntry playerInfo(MinecraftClient minecraft, GameProfile sender) {
		if (sender == null || minecraft.getNetworkHandler() == null) {
			return null;
		}

		return minecraft.getNetworkHandler().getPlayerListEntry(sender.id());
	}

	private static void reservePreviewSpace(List<PendingPreview> previews) {
		MinecraftClient minecraft = MinecraftClient.getInstance();
		for (PendingPreview preview : previews) {
			for (int line = 0; line < PreviewCardStore.spacerLines(true); line++) {
				addSilentMessage(minecraft, PreviewCardStore.spacerComponent(preview.id()));
			}
		}
	}

	private static void addSilentMessage(MinecraftClient minecraft, Text message) {
		ChatHudLine chatHudLine = new ChatHudLine(minecraft.inGameHud.getTicks(), message, null, null);
		LinkPreviewChatAccess chat = (LinkPreviewChatAccess) minecraft.inGameHud.getChatHud();
		chat.linkpreview$addVisibleMessage(chatHudLine);
		chat.linkpreview$addMessageToQueue(chatHudLine);
	}

	private static void addUntaggedMessage(MinecraftClient minecraft, Text message, MessageSignatureData signature) {
		minecraft.inGameHud.getChatHud().addMessage(message, signature, null);
	}

	private record PendingPreview(long id, String url) {
	}
}
