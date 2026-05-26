package de.devknochen.linkpreview;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.ImageIO;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.util.ARGB;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import de.devknochen.linkpreview.mixin.ChatComponentAccessor;

public final class PreviewCardStore {
	public static final String SPACER_MARKER_PREFIX = "[linkpreview-spacer:";
	private static final int TEXT_SPACER_LINES = 9;
	private static final int IMAGE_SPACER_LINES = 9;

	private static final int MAX_STORED_CARDS = 100;
	private static final int MIN_CARD_WIDTH = 320;
	private static final int CARD_PADDING = 10;
	private static final int THUMBNAIL_RIGHT_INSET = 14;
	private static final int THUMBNAIL_WIDTH = 112;
	private static final int THUMBNAIL_HEIGHT = 63;
	private static final float THUMBNAIL_VERTICAL_CROP_BIAS = 0.75F;
	private static final int EMOJI_SIZE = 10;
	private static final int EMOJI_TEXTURE_SIZE = 32;
	private static final int GAP = 6;
	private static final int ACCENT = 0xFF2F81F7;
	private static final int TEXT = 0xFFE6EDF3;
	private static final int MUTED = 0xFF9CA3AF;
	private static final int TITLE = 0xFF2F81F7;

	private final List<PreviewCard> cards = new ArrayList<>();
	private long nextId;

	public synchronized long nextId() {
		return ++nextId;
	}

	public static int spacerLines(boolean hasImage) {
		return hasImage ? IMAGE_SPACER_LINES : TEXT_SPACER_LINES;
	}

	public static Component spacerComponent(long previewId) {
		return Component.literal(SPACER_MARKER_PREFIX + previewId + "]");
	}

	public synchronized PreviewCard reserve(long id, String url, String site, int createdTick) {
		PreviewCard card = new PreviewCard(id, url, site, "Loading preview", List.of(), createdTick, true);
		cards.add(0, card);

		while (cards.size() > MAX_STORED_CARDS) {
			release(cards.remove(cards.size() - 1));
		}

		return card;
	}

	public synchronized void update(long cardId, String site, String title, List<String> descriptionLines) {
		PreviewCard card = findCard(cardId);
		if (card != null) {
			card.update(site, title, descriptionLines);
		}
	}

	public synchronized void discard(long cardId) {
		for (int index = 0; index < cards.size(); index++) {
			PreviewCard card = cards.get(index);
			if (card.id() == cardId) {
				release(cards.remove(index));
				break;
			}
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui == null) {
			return;
		}

		ChatComponentAccessor accessor = (ChatComponentAccessor) minecraft.gui.getChat();
		String marker = spacerMarker(cardId);
		accessor.linkpreview$trimmedMessages().removeIf(line -> marker.equals(line.parent().content().getString()));
		accessor.linkpreview$allMessages().removeIf(message -> marker.equals(message.content().getString()));
	}

	public synchronized void attachImage(long cardId, Optional<byte[]> imageBytes) {
		if (imageBytes.isEmpty()) {
			markImageUnavailable(cardId);
			return;
		}

		for (PreviewCard card : cards) {
			if (card.id() == cardId) {
				PreviewImage image = createImage(cardId, imageBytes.get());
				if (image == null) {
					card.markImageFailed();
				} else {
					card.image(image);
				}
				return;
			}
		}
	}

	public synchronized void markImageUnavailable(long cardId) {
		for (PreviewCard card : cards) {
			if (card.id() == cardId) {
				card.markImageFailed();
				return;
			}
		}
	}

	public synchronized void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui == null) {
			return;
		}

		if (cards.isEmpty()) {
			return;
		}

		int now = minecraft.gui.getGuiTicks();
		Font font = minecraft.font;
		ChatComponent chat = minecraft.gui.getChat();
		ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
		List<GuiMessage.Line> lines = accessor.linkpreview$trimmedMessages();
		int scroll = accessor.linkpreview$chatScrollbarPos();
		int visibleLines = Math.min(chat.getLinesPerPage(), Math.max(0, lines.size() - scroll));
		if (visibleLines <= 0) {
			return;
		}

		double scale = minecraft.options.chatScale().get();
		double spacing = minecraft.options.chatLineSpacing().get();
		int lineHeight = (int) (9.0D * (spacing + 1.0D));
		int chatBottom = (int) Math.floor((graphics.guiHeight() - 40) / scale);
		int chatWidth = Math.max(MIN_CARD_WIDTH, (int) Math.ceil(ChatComponent.getWidth(minecraft.options.chatWidth().get()) / scale) + 12);
		int clipTop = chatBottom - visibleLines * lineHeight;
		boolean chatFocused = chat.isChatFocused();
		HoverState hover = chatFocused ? hoverState(minecraft, graphics, scale) : null;

		graphics.pose().pushMatrix();
		graphics.pose().scale((float) scale, (float) scale);
		graphics.pose().translate(4.0F, 0.0F);

		try {
			graphics.enableScissor(-4, clipTop, -4 + chatWidth, chatBottom);
			try {
				drawVisibleCards(graphics, font, lines, scroll, visibleLines, lineHeight, chatBottom, now, chatFocused, chatWidth, hover);
			} finally {
				graphics.disableScissor();
			}
		} finally {
			graphics.pose().popMatrix();
		}
	}

	private void drawVisibleCards(GuiGraphicsExtractor graphics, Font font, List<GuiMessage.Line> lines, int scroll, int visibleLines, int lineHeight, int chatBottom, int now, boolean chatFocused, int cardWidth, HoverState hover) {
		int visibleIndex = 0;

		while (visibleIndex < visibleLines) {
			int lineIndex = scroll + visibleIndex;
			if (!isSpacer(lines.get(lineIndex))) {
				visibleIndex++;
				continue;
			}

			Long runId = spacerId(lines.get(lineIndex));
			int runStart = runStart(lines, lineIndex, runId);
			int runEnd = runEnd(lines, lineIndex, runId);
			int runLength = runEnd - runStart;
			visibleIndex = Math.min(visibleLines, runEnd - scroll);

			if (runLength >= 3) {
				PreviewCard card = runId == null ? null : findCard(runId);
				if (card != null) {
					int reservedTop = chatBottom - (runStart - scroll + runLength) * lineHeight;
					float alpha = chatFocused ? 1.0F : lineAlpha(now, lines.get(Math.max(runStart, scroll)));
					if (alpha > 1.0E-5F) {
						drawCard(graphics, font, card, -4, reservedTop, cardWidth, Math.min(cardHeight(card), runLength * lineHeight), alpha, hover);
					}
				}
			}
		}
	}

	private void drawCard(GuiGraphicsExtractor graphics, Font font, PreviewCard card, int x, int y, int width, int height, float alpha, HoverState hover) {
		int imageX = x + width - THUMBNAIL_RIGHT_INSET - THUMBNAIL_WIDTH;
		int textRight = x + width - CARD_PADDING;
		if (card.imageExpected()) {
			textRight = imageX - CARD_PADDING;
		}
		float backgroundOpacity = ((Double) Minecraft.getInstance().options.textBackgroundOpacity().get()).floatValue();
		float textOpacity = ((Double) Minecraft.getInstance().options.chatOpacity().get()).floatValue() * 0.9F + 0.1F;
		graphics.fill(x, y, x + width, y + height, ARGB.black(alpha * backgroundOpacity));
		graphics.fill(x, y, x + 3, y + height, withAlpha(ACCENT, alpha * textOpacity));

		int textX = x + CARD_PADDING + 5;
		int lineY = y + CARD_PADDING;

		drawText(graphics, font, clip(font, card.site(), textRight - textX), textX, lineY, withAlpha(MUTED, alpha * textOpacity));
		showTooltipIfHovered(graphics, font, hover, textX, lineY, textRight, card.site());
		lineY += 13;
		drawText(graphics, font, clip(font, card.title(), textRight - textX), textX, lineY, withAlpha(TITLE, alpha * textOpacity));
		showTooltipIfHovered(graphics, font, hover, textX, lineY, textRight, card.title());
		lineY += 14;

		for (String descriptionLine : card.descriptionLines()) {
			drawText(graphics, font, clip(font, descriptionLine, textRight - textX), textX, lineY, withAlpha(TEXT, alpha * textOpacity));
			showTooltipIfHovered(graphics, font, hover, textX, lineY, textRight, descriptionLine);
			lineY += 11;
		}

		drawThumbnail(graphics, font, card, imageX, y + CARD_PADDING, alpha, textOpacity);
	}

	private static int cardHeight(PreviewCard card) {
		int textHeight = CARD_PADDING * 2 + 13 + 14 + Math.max(1, card.descriptionLines().size()) * 11;
		int imageHeight = card.imageExpected() ? CARD_PADDING * 2 + THUMBNAIL_HEIGHT : 0;
		return Math.max(textHeight, imageHeight);
	}

	private void drawThumbnail(GuiGraphicsExtractor graphics, Font font, PreviewCard card, int imageX, int imageY, float alpha, float textOpacity) {
		if (!card.imageExpected()) {
			return;
		}

		graphics.fill(imageX - 1, imageY - 1, imageX + THUMBNAIL_WIDTH + 1, imageY + THUMBNAIL_HEIGHT + 1, withAlpha(0xFF0B0D10, alpha * 0.8F));

		if (card.image() != null) {
			int sourceWidth = card.image().width();
			int sourceHeight = card.image().height();
			float targetAspect = (float) THUMBNAIL_WIDTH / THUMBNAIL_HEIGHT;
			float sourceAspect = (float) sourceWidth / sourceHeight;
			int cropWidth = sourceWidth;
			int cropHeight = sourceHeight;
			if (sourceAspect > targetAspect) {
				cropWidth = Math.max(1, Math.round(sourceHeight * targetAspect));
			} else if (sourceAspect < targetAspect) {
				cropHeight = Math.max(1, Math.round(sourceWidth / targetAspect));
			}
			int cropX = (sourceWidth - cropWidth) / 2;
			int cropY = Math.round((sourceHeight - cropHeight) * THUMBNAIL_VERTICAL_CROP_BIAS);

			graphics.blit(
					RenderPipelines.GUI_TEXTURED,
					card.image().textureId(),
					imageX,
					imageY,
					cropX,
					cropY,
					THUMBNAIL_WIDTH,
					THUMBNAIL_HEIGHT,
					cropWidth,
					cropHeight,
					sourceWidth,
					sourceHeight,
					withAlpha(0xFFFFFFFF, alpha)
			);
		} else {
			String label = card.imageFailed() ? "No image" : spinner() + " Loading";
			graphics.text(
					font,
					label,
					imageX + (THUMBNAIL_WIDTH - font.width(label)) / 2,
					imageY + (THUMBNAIL_HEIGHT - 9) / 2,
					withAlpha(MUTED, alpha * textOpacity)
			);
		}
	}

	private static String spinner() {
		return switch ((Minecraft.getInstance().gui.getGuiTicks() / 4) & 3) {
			case 0 -> "|";
			case 1 -> "/";
			case 2 -> "-";
			default -> "\\";
		};
	}

	private static void drawText(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		int cursor = x;
		int index = 0;

		while (index < text.length()) {
			int nextEmoji = nextEmojiStart(text, index);
			if (nextEmoji > index) {
				String normal = text.substring(index, nextEmoji);
				graphics.text(font, normal, cursor, y, color);
				cursor += font.width(normal);
				index = nextEmoji;
				continue;
			}

			int emojiEnd = consumeEmojiSequence(text, index);
			String emoji = text.substring(index, emojiEnd);
			PreviewImage emojiImage = EmojiTextures.get(emoji);
			if (emojiImage == null) {
				graphics.text(font, emoji, cursor, y, color);
				cursor += font.width(emoji);
			} else {
				float opacity = ((color >>> 24) & 0xFF) / 255.0F;
				graphics.blit(
						RenderPipelines.GUI_TEXTURED,
						emojiImage.textureId(),
						cursor,
						y - 1,
						0,
						0,
						EMOJI_SIZE,
						EMOJI_SIZE,
						emojiImage.width(),
						emojiImage.height(),
						emojiImage.width(),
						emojiImage.height(),
						withAlpha(0xFFFFFFFF, opacity)
				);
				cursor += EMOJI_SIZE;
			}
			index = emojiEnd;
		}
	}

	private static int nextEmojiStart(String text, int start) {
		for (int index = start; index < text.length(); index += Character.charCount(text.codePointAt(index))) {
			if (isEmojiCodePoint(text.codePointAt(index))) {
				return index;
			}
		}

		return text.length();
	}

	private static int consumeEmojiSequence(String text, int start) {
		int index = start + Character.charCount(text.codePointAt(start));
		while (index < text.length()) {
			int codePoint = text.codePointAt(index);
			if (codePoint == 0xFE0E || codePoint == 0xFE0F || isEmojiModifier(codePoint)) {
				index += Character.charCount(codePoint);
				continue;
			}

			if (codePoint == 0x200D && index + 1 < text.length()) {
				index += Character.charCount(codePoint);
				index += Character.charCount(text.codePointAt(index));
				continue;
			}

			break;
		}

		return index;
	}

	private static boolean isEmojiCodePoint(int codePoint) {
		return codePoint >= 0x1F000 && codePoint <= 0x1FAFF
				|| codePoint >= 0x2600 && codePoint <= 0x27BF
				|| codePoint >= 0x2300 && codePoint <= 0x23FF;
	}

	private static boolean isEmojiModifier(int codePoint) {
		return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
	}

	private static final class EmojiTextures {
		private static final String FONT_RESOURCE = "/assets/linkpreview/font/noto_color_emoji/notocoloremoji-regular.ttf";
		private static final String GOOGLE_EMOJI_PNG_BASE = "https://raw.githubusercontent.com/googlefonts/noto-emoji/main/png/128/";
		private static final int CANVAS_SIZE = 64;
		private static final float FONT_SIZE = 48.0F;
		private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(PreviewService.REQUEST_TIMEOUT_MILLIS))
				.build();
		private static final Map<String, Optional<PreviewImage>> CACHE = new ConcurrentHashMap<>();
		private static final Set<String> FETCHING = ConcurrentHashMap.newKeySet();
		private static java.awt.Font emojiFont;

		private EmojiTextures() {
		}

		static PreviewImage get(String emoji) {
			Optional<PreviewImage> cached = CACHE.get(emoji);
			if (cached != null) {
				return cached.orElse(null);
			}

			Optional<PreviewImage> fontImage = createFromFont(emoji);
			if (fontImage.isPresent()) {
				CACHE.put(emoji, fontImage);
				return fontImage.get();
			}

			fetchGooglePng(emoji);
			return null;
		}

		private static Optional<PreviewImage> createFromFont(String emoji) {
			java.awt.Font font = font();
			if (font == null) {
				return Optional.empty();
			}

			BufferedImage bufferedImage = new BufferedImage(CANVAS_SIZE, CANVAS_SIZE, BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D graphics = bufferedImage.createGraphics();
			try {
				graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				graphics.setFont(font);
				graphics.setColor(java.awt.Color.WHITE);

				java.awt.font.FontRenderContext context = graphics.getFontRenderContext();
				java.awt.font.TextLayout layout = new java.awt.font.TextLayout(emoji, font, context);
				java.awt.geom.Rectangle2D bounds = layout.getBounds();
				float drawX = (float) ((CANVAS_SIZE - bounds.getWidth()) * 0.5D - bounds.getX());
				float drawY = (float) ((CANVAS_SIZE - bounds.getHeight()) * 0.5D - bounds.getY());
				layout.draw(graphics, drawX, drawY);
			} finally {
				graphics.dispose();
			}

			if (!hasVisiblePixel(bufferedImage)) {
				return Optional.empty();
			}

			NativeImage nativeImage = new NativeImage(CANVAS_SIZE, CANVAS_SIZE, false);
			for (int y = 0; y < CANVAS_SIZE; y++) {
				for (int x = 0; x < CANVAS_SIZE; x++) {
					nativeImage.setPixel(x, y, bufferedImage.getRGB(x, y));
				}
			}

			Identifier id = Identifier.fromNamespaceAndPath(LinkPreviewClient.MOD_ID, "emoji/" + Integer.toHexString(emoji.hashCode()));
			DynamicTexture texture = new DynamicTexture(() -> "LinkPreview emoji", nativeImage);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			return Optional.of(new PreviewImage(id, CANVAS_SIZE, CANVAS_SIZE));
		}

		private static void fetchGooglePng(String emoji) {
			if (!FETCHING.add(emoji)) {
				return;
			}

			HttpRequest request = HttpRequest.newBuilder(URI.create(GOOGLE_EMOJI_PNG_BASE + googleEmojiFilename(emoji)))
					.timeout(Duration.ofMillis(PreviewService.REQUEST_TIMEOUT_MILLIS))
					.header("Accept", "image/png")
					.GET()
					.build();

			HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
					.thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300 ? Optional.of(response.body()) : Optional.<byte[]>empty())
					.exceptionally(exception -> Optional.empty())
					.thenAccept(bytes -> Minecraft.getInstance().execute(() -> finishGoogleFetch(emoji, bytes)));
		}

		private static void finishGoogleFetch(String emoji, Optional<byte[]> bytes) {
			FETCHING.remove(emoji);
			if (bytes.isEmpty()) {
				CACHE.put(emoji, Optional.empty());
				return;
			}

			try {
				NativeImage nativeImage = downscaleEmoji(readImage(bytes.get()));
				Identifier id = Identifier.fromNamespaceAndPath(LinkPreviewClient.MOD_ID, "emoji/" + Integer.toHexString(emoji.hashCode()) + "_google");
				DynamicTexture texture = new DynamicTexture(() -> "LinkPreview Google emoji", nativeImage);
				Minecraft.getInstance().getTextureManager().register(id, texture);
				CACHE.put(emoji, Optional.of(new PreviewImage(id, nativeImage.getWidth(), nativeImage.getHeight())));
			} catch (IOException exception) {
				CACHE.put(emoji, Optional.empty());
			}
		}

		private static String googleEmojiFilename(String emoji) {
			List<String> codePoints = new ArrayList<>();
			for (int index = 0; index < emoji.length(); index += Character.charCount(emoji.codePointAt(index))) {
				int codePoint = emoji.codePointAt(index);
				if (codePoint != 0xFE0E && codePoint != 0xFE0F) {
					codePoints.add(Integer.toHexString(codePoint));
				}
			}

			return "emoji_u" + String.join("_", codePoints) + ".png";
		}

		private static NativeImage downscaleEmoji(NativeImage source) {
			if (source.getWidth() == EMOJI_TEXTURE_SIZE && source.getHeight() == EMOJI_TEXTURE_SIZE) {
				return source;
			}

			NativeImage target = new NativeImage(EMOJI_TEXTURE_SIZE, EMOJI_TEXTURE_SIZE, false);
			for (int y = 0; y < EMOJI_TEXTURE_SIZE; y++) {
				for (int x = 0; x < EMOJI_TEXTURE_SIZE; x++) {
					target.setPixel(x, y, averagedPixel(source, x, y));
				}
			}

			source.close();
			return target;
		}

		private static int averagedPixel(NativeImage source, int targetX, int targetY) {
			int startX = targetX * source.getWidth() / EMOJI_TEXTURE_SIZE;
			int endX = Math.max(startX + 1, (targetX + 1) * source.getWidth() / EMOJI_TEXTURE_SIZE);
			int startY = targetY * source.getHeight() / EMOJI_TEXTURE_SIZE;
			int endY = Math.max(startY + 1, (targetY + 1) * source.getHeight() / EMOJI_TEXTURE_SIZE);
			long alpha = 0;
			long red = 0;
			long green = 0;
			long blue = 0;
			long count = 0;

			for (int y = startY; y < endY; y++) {
				for (int x = startX; x < endX; x++) {
					int pixel = source.getPixel(Math.min(source.getWidth() - 1, x), Math.min(source.getHeight() - 1, y));
					alpha += (pixel >>> 24) & 0xFF;
					red += (pixel >>> 16) & 0xFF;
					green += (pixel >>> 8) & 0xFF;
					blue += pixel & 0xFF;
					count++;
				}
			}

			return ((int) (alpha / count) << 24) | ((int) (red / count) << 16) | ((int) (green / count) << 8) | (int) (blue / count);
		}

		private static java.awt.Font font() {
			if (emojiFont != null) {
				return emojiFont;
			}

			try (InputStream input = PreviewCardStore.class.getResourceAsStream(FONT_RESOURCE)) {
				if (input == null) {
					LinkPreviewClient.LOGGER.warn("Bundled emoji font not found: {}", FONT_RESOURCE);
					return null;
				}

				emojiFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, input).deriveFont(FONT_SIZE);
				return emojiFont;
			} catch (java.awt.FontFormatException | IOException exception) {
				LinkPreviewClient.LOGGER.warn("Failed to load bundled emoji font", exception);
				return null;
			}
		}

		private static boolean hasVisiblePixel(BufferedImage image) {
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					if ((image.getRGB(x, y) >>> 24) != 0) {
						return true;
					}
				}
			}

			return false;
		}
	}

	public static boolean isSpacerLine(GuiMessage.Line line) {
		return line.parent().content().getString().startsWith(SPACER_MARKER_PREFIX);
	}

	public static boolean isPreviewSourceLine(GuiMessage.Line line) {
		return !isSpacerLine(line) && !UrlExtractor.findUrls(line.parent().content().getString()).isEmpty();
	}

	private static boolean isSpacer(GuiMessage.Line line) {
		return isSpacerLine(line);
	}

	private static String spacerMarker(long previewId) {
		return SPACER_MARKER_PREFIX + previewId + "]";
	}

	private static Long spacerId(GuiMessage.Line line) {
		String text = line.parent().content().getString();
		if (!text.startsWith(SPACER_MARKER_PREFIX) || !text.endsWith("]")) {
			return null;
		}

		try {
			return Long.parseLong(text.substring(SPACER_MARKER_PREFIX.length(), text.length() - 1));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private PreviewCard findCard(long cardId) {
		for (PreviewCard card : cards) {
			if (card.id() == cardId) {
				return card;
			}
		}

		return null;
	}

	private static boolean sameId(Long first, Long second) {
		return first == null ? second == null : first.equals(second);
	}

	private static int runStart(List<GuiMessage.Line> lines, int lineIndex, Long runId) {
		int index = lineIndex;
		while (index > 0 && isSpacer(lines.get(index - 1)) && sameId(runId, spacerId(lines.get(index - 1)))) {
			index--;
		}

		return index;
	}

	private static int runEnd(List<GuiMessage.Line> lines, int lineIndex, Long runId) {
		int index = lineIndex + 1;
		while (index < lines.size() && isSpacer(lines.get(index)) && sameId(runId, spacerId(lines.get(index)))) {
			index++;
		}

		return index;
	}

	private PreviewImage createImage(long cardId, byte[] imageBytes) {
		try {
			NativeImage nativeImage = readImage(imageBytes);
			Identifier id = Identifier.fromNamespaceAndPath(LinkPreviewClient.MOD_ID, "preview/" + cardId);
			DynamicTexture texture = new DynamicTexture(() -> "LinkPreview thumbnail", nativeImage);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			LinkPreviewClient.LOGGER.info("Loaded LinkPreview image {}x{} for card {}", nativeImage.getWidth(), nativeImage.getHeight(), cardId);
			return new PreviewImage(id, nativeImage.getWidth(), nativeImage.getHeight());
		} catch (IOException exception) {
			LinkPreviewClient.LOGGER.warn("Failed to decode LinkPreview image. Worker may be returning an unsupported image format.", exception);
			return null;
		}
	}

	private static NativeImage readImage(byte[] imageBytes) throws IOException {
		try {
			return NativeImage.read(imageBytes);
		} catch (IOException nativeImageException) {
			BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
			if (bufferedImage == null) {
				throw nativeImageException;
			}

			NativeImage nativeImage = new NativeImage(bufferedImage.getWidth(), bufferedImage.getHeight(), false);
			for (int y = 0; y < bufferedImage.getHeight(); y++) {
				for (int x = 0; x < bufferedImage.getWidth(); x++) {
					nativeImage.setPixel(x, y, bufferedImage.getRGB(x, y));
				}
			}

			return nativeImage;
		}
	}

	private static void release(PreviewCard card) {
		if (card.image() != null) {
			Minecraft.getInstance().getTextureManager().release(card.image().textureId());
		}
	}

	private static String clip(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}

		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
	}

	private static float lineAlpha(int now, GuiMessage.Line line) {
		double age = (double) (now - line.addedTime()) / 200.0D;
		double alpha = 1.0D - age;
		alpha = Math.clamp(alpha * 10.0D, 0.0D, 1.0D);
		return (float) (alpha * alpha);
	}

	private static int withAlpha(int argb, float alphaScale) {
		int alpha = (argb >>> 24) & 0xFF;
		int scaledAlpha = Math.clamp(Math.round(alpha * alphaScale), 0, 255);
		return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
	}

	private static HoverState hoverState(Minecraft minecraft, GuiGraphicsExtractor graphics, double chatScale) {
		Window window = minecraft.getWindow();
		int mouseGuiX = (int) (minecraft.mouseHandler.xpos() * graphics.guiWidth() / window.getWidth());
		int mouseGuiY = (int) (minecraft.mouseHandler.ypos() * graphics.guiHeight() / window.getHeight());
		int localX = (int) Math.floor(mouseGuiX / chatScale - 4.0D);
		int localY = (int) Math.floor(mouseGuiY / chatScale);
		return new HoverState(mouseGuiX, mouseGuiY, localX, localY);
	}

	private static void showTooltipIfHovered(GuiGraphicsExtractor graphics, Font font, HoverState hover, int x, int y, int textRight, String text) {
		if (hover == null || text == null || text.isBlank()) {
			return;
		}

		int width = Math.min(font.width(text), Math.max(0, textRight - x));
		if (hover.localX() >= x && hover.localX() < x + width && hover.localY() >= y && hover.localY() < y + font.lineHeight) {
			graphics.setTooltipForNextFrame(font, Component.literal(text), hover.mouseGuiX(), hover.mouseGuiY());
		}
	}

	private record HoverState(int mouseGuiX, int mouseGuiY, int localX, int localY) {
	}
}
