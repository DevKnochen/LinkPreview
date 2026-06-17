package de.devknochen.linkpreview;

import java.awt.image.BufferedImage;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.systems.RenderSystem;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.Window;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

public final class PreviewCardStore {
	public static final String SPACER_MARKER_PREFIX = "[linkpreview-spacer:";
	private static final int TEXT_SPACER_LINES = 9;
	private static final int IMAGE_SPACER_LINES = 9;

	private static final int CARD_X = -4;
	private static final int MAX_STORED_CARDS = 100;
	private static final int MIN_CARD_WIDTH = 320;
	private static final int CARD_PADDING = 10;
	private static final int THUMBNAIL_RIGHT_INSET = 14;
	private static final int THUMBNAIL_WIDTH = 112;
	private static final int THUMBNAIL_HEIGHT = 63;
	private static final int EMOJI_SIZE = 10;
	private static final int EMOJI_TEXTURE_SIZE = 32;
	private static final int MAX_GIF_FRAMES = 120;
	private static final int MIN_GIF_FRAME_DELAY_MILLIS = 20;
	private static final int DEFAULT_GIF_FRAME_DELAY_MILLIS = 100;
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

	public static Text spacerComponent(long previewId) {
		return Text.literal(SPACER_MARKER_PREFIX + previewId + "]");
	}

	public synchronized void reserve(long id, String site) {
		PreviewCard card = new PreviewCard(id, site, "Loading preview", List.of(), true);
		cards.add(0, card);

		while (cards.size() > MAX_STORED_CARDS) {
			release(cards.remove(cards.size() - 1));
		}
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

		MinecraftClient minecraft = MinecraftClient.getInstance();
		LinkPreviewChatAccess accessor = (LinkPreviewChatAccess) minecraft.inGameHud.getChatHud();
		String marker = spacerMarker(cardId);
		accessor.linkpreview$visibleMessages().removeIf(line -> marker.equals(visibleText(line)));
		accessor.linkpreview$messages().removeIf(message -> marker.equals(message.content().getString()));
	}

	synchronized void attachImage(long cardId, PreparedPreviewImage preparedImage) {
		for (PreviewCard card : cards) {
			if (card.id() == cardId) {
				card.image(createImage(cardId, preparedImage));
				return;
			}
		}

		preparedImage.close();
	}

	public synchronized void markImageUnavailable(long cardId) {
		for (PreviewCard card : cards) {
			if (card.id() == cardId) {
				card.markImageFailed();
				return;
			}
		}
	}

	public synchronized void render(DrawContext graphics) {
		MinecraftClient minecraft = MinecraftClient.getInstance();
		if (cards.isEmpty()) {
			return;
		}

		int now = minecraft.inGameHud.getTicks();
		TextRenderer font = minecraft.textRenderer;
		ChatHud chat = minecraft.inGameHud.getChatHud();
		LinkPreviewChatAccess accessor = (LinkPreviewChatAccess) chat;
		List<ChatHudLine.Visible> lines = accessor.linkpreview$visibleMessages();
		int scroll = accessor.linkpreview$scrolledLines();
		int visibleLines = MathHelper.clamp(lines.size() - scroll, 0, chat.getVisibleLineCount());
		if (visibleLines <= 0) {
			return;
		}

		double scale = minecraft.options.getChatScale().getValue();
		double spacing = minecraft.options.getChatLineSpacing().getValue();
		int lineHeight = (int) (9.0D * (spacing + 1.0D));
		int chatBottom = (int) Math.floor((graphics.getScaledWindowHeight() - 40) / scale);
		int chatWidth = Math.max(MIN_CARD_WIDTH, (int) Math.ceil(ChatHud.getWidth(minecraft.options.getChatWidth().getValue()) / scale) + 12);
		int clipTop = chatBottom - visibleLines * lineHeight;
		int scissorTop = MathHelper.clamp((int) Math.floor(clipTop * scale), 0, graphics.getScaledWindowHeight());
		int scissorBottom = MathHelper.clamp((int) Math.ceil(chatBottom * scale), 0, graphics.getScaledWindowHeight());
		boolean chatFocused = minecraft.currentScreen instanceof ChatScreen;
		HoverState hover = chatFocused ? hoverState(minecraft, graphics, scale) : null;

		graphics.getMatrices().push();
		graphics.getMatrices().scale((float) scale, (float) scale, 1.0F);
		graphics.getMatrices().translate(4.0F, 0.0F, 0.0F);

		try {
			graphics.enableScissor(0, scissorTop, graphics.getScaledWindowWidth(), scissorBottom);
			try {
				drawVisibleCards(graphics, font, lines, scroll, visibleLines, lineHeight, chatBottom, now, chatFocused, chatWidth, hover);
			} finally {
				graphics.disableScissor();
			}
		} finally {
			graphics.getMatrices().pop();
		}
	}

	private void drawVisibleCards(DrawContext graphics, TextRenderer font, List<ChatHudLine.Visible> lines, int scroll, int visibleLines, int lineHeight, int chatBottom, int now, boolean chatFocused, int cardWidth, HoverState hover) {
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
					float textOpacity = MinecraftClient.getInstance().options.getChatOpacity().getValue().floatValue() * 0.9F + 0.1F;
					if ((int) (255.0F * alpha * textOpacity) > 3) {
						drawCard(graphics, font, card, reservedTop, cardWidth, Math.min(cardHeight(card), runLength * lineHeight), alpha, hover);
					}
				}
			}
		}
	}

	private void drawCard(DrawContext graphics, TextRenderer font, PreviewCard card, int y, int width, int height, float alpha, HoverState hover) {
		int imageX = CARD_X + width - THUMBNAIL_RIGHT_INSET - THUMBNAIL_WIDTH;
		int textRight = CARD_X + width - CARD_PADDING;
		if (card.imageExpected()) {
			textRight = imageX - CARD_PADDING;
		}
		float backgroundOpacity = MinecraftClient.getInstance().options.getTextBackgroundOpacity().getValue().floatValue();
		float textOpacity = MinecraftClient.getInstance().options.getChatOpacity().getValue().floatValue() * 0.9F + 0.1F;
		graphics.fill(CARD_X, y, CARD_X + width, y + height, MathHelper.clamp(Math.round(255.0F * alpha * backgroundOpacity), 0, 255) << 24);
		graphics.fill(CARD_X, y, CARD_X + 3, y + height, withAlpha(ACCENT, alpha * textOpacity));

		int textX = CARD_X + CARD_PADDING + 5;
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

	private void drawThumbnail(DrawContext graphics, TextRenderer font, PreviewCard card, int imageX, int imageY, float alpha, float textOpacity) {
		if (!card.imageExpected()) {
			return;
		}

		graphics.fill(imageX - 1, imageY - 1, imageX + THUMBNAIL_WIDTH + 1, imageY + THUMBNAIL_HEIGHT + 1, withAlpha(0xFF0B0D10, alpha * 0.8F));

		if (card.image() != null) {
			card.image().updateAnimation(System.currentTimeMillis());
			int sourceWidth = card.image().width();
			int sourceHeight = card.image().height();
			float scale = Math.min((float) THUMBNAIL_WIDTH / sourceWidth, (float) THUMBNAIL_HEIGHT / sourceHeight);
			int targetWidth = Math.max(1, Math.round(sourceWidth * scale));
			int targetHeight = Math.max(1, Math.round(sourceHeight * scale));
			int targetX = imageX + (THUMBNAIL_WIDTH - targetWidth) / 2;
			int targetY = imageY + (THUMBNAIL_HEIGHT - targetHeight) / 2;

			drawTexture(
					graphics,
					card.image().textureId(),
					targetX,
					targetY,
					targetWidth,
					targetHeight,
					sourceWidth,
					sourceHeight,
					sourceWidth,
					sourceHeight,
					withAlpha(0xFFFFFFFF, alpha)
			);
		} else {
			String label = card.imageFailed() ? "No image" : spinner() + " Loading";
			graphics.drawTextWithShadow(
					font,
					label,
					imageX + (THUMBNAIL_WIDTH - font.getWidth(label)) / 2,
					imageY + (THUMBNAIL_HEIGHT - 9) / 2,
					withAlpha(MUTED, alpha * textOpacity)
			);
		}
	}

	private static String spinner() {
		return switch ((MinecraftClient.getInstance().inGameHud.getTicks() / 4) & 3) {
			case 0 -> "|";
			case 1 -> "/";
			case 2 -> "-";
			default -> "\\";
		};
	}

	private static void drawText(DrawContext graphics, TextRenderer font, String text, int x, int y, int color) {
		int cursor = x;
		int index = 0;

		while (index < text.length()) {
			int nextEmoji = nextEmojiStart(text, index);
			if (nextEmoji > index) {
				String normal = text.substring(index, nextEmoji);
				graphics.drawTextWithShadow(font, normal, cursor, y, color);
				cursor += font.getWidth(normal);
				index = nextEmoji;
				continue;
			}

			int emojiEnd = consumeEmojiSequence(text, index);
			String emoji = text.substring(index, emojiEnd);
			PreviewImage emojiImage = EmojiTextures.get(emoji);
			if (emojiImage == null) {
				graphics.drawTextWithShadow(font, emoji, cursor, y, color);
				cursor += font.getWidth(emoji);
			} else {
				float opacity = ((color >>> 24) & 0xFF) / 255.0F;
				drawTexture(
						graphics,
						emojiImage.textureId(),
						cursor,
						y - 1,
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

	private static void drawTexture(DrawContext graphics, Identifier textureId, int x, int y, int width, int height, int regionWidth, int regionHeight, int textureWidth, int textureHeight, int color) {
		float alpha = ((color >>> 24) & 0xFF) / 255.0F;
		float red = ((color >>> 16) & 0xFF) / 255.0F;
		float green = ((color >>> 8) & 0xFF) / 255.0F;
		float blue = (color & 0xFF) / 255.0F;
		float u1 = 0.0F;
		float u2 = (float) regionWidth / textureWidth;
		float v1 = 0.0F;
		float v2 = (float) regionHeight / textureHeight;
		int x2 = x + width;
		int y2 = y + height;

		RenderSystem.setShaderTexture(0, textureId);
		RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
		RenderSystem.enableBlend();
		Matrix4f matrix = graphics.getMatrices().peek().getPositionMatrix();
		BufferBuilder bufferBuilder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
		bufferBuilder.vertex(matrix, x, y, 0).texture(u1, v1).color(red, green, blue, alpha);
		bufferBuilder.vertex(matrix, x, y2, 0).texture(u1, v2).color(red, green, blue, alpha);
		bufferBuilder.vertex(matrix, x2, y2, 0).texture(u2, v2).color(red, green, blue, alpha);
		bufferBuilder.vertex(matrix, x2, y, 0).texture(u2, v1).color(red, green, blue, alpha);
		BufferRenderer.drawWithGlobalProgram(bufferBuilder.end());
		RenderSystem.disableBlend();
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
					nativeImage.setColor(x, y, argbToAbgr(bufferedImage.getRGB(x, y)));
				}
			}

			Identifier id = Identifier.of(LinkPreviewClient.MOD_ID, "emoji/" + Integer.toHexString(emoji.hashCode()));
			NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);
			MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
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
					.exceptionally(ignored -> Optional.empty())
					.thenAccept(bytes -> MinecraftClient.getInstance().execute(() -> bytes.ifPresentOrElse(
							body -> finishGoogleFetch(emoji, body),
							() -> failGoogleFetch(emoji)
					)));
		}

		private static void finishGoogleFetch(String emoji, byte[] bytes) {
			FETCHING.remove(emoji);
			try {
				NativeImage nativeImage = downscaleEmoji(readImage(bytes));
				Identifier id = Identifier.of(LinkPreviewClient.MOD_ID, "emoji/" + Integer.toHexString(emoji.hashCode()) + "_google");
				NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);
				MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
				CACHE.put(emoji, Optional.of(new PreviewImage(id, nativeImage.getWidth(), nativeImage.getHeight())));
			} catch (IOException exception) {
				CACHE.put(emoji, Optional.empty());
			}
		}

		private static void failGoogleFetch(String emoji) {
			FETCHING.remove(emoji);
			CACHE.put(emoji, Optional.empty());
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
					target.setColor(x, y, averagedPixel(source, x, y));
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
					int pixel = source.getColor(Math.min(source.getWidth() - 1, x), Math.min(source.getHeight() - 1, y));
					alpha += (pixel >>> 24) & 0xFF;
					blue += (pixel >>> 16) & 0xFF;
					green += (pixel >>> 8) & 0xFF;
					red += pixel & 0xFF;
					count++;
				}
			}

			return ((int) (alpha / count) << 24) | ((int) (blue / count) << 16) | ((int) (green / count) << 8) | (int) (red / count);
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

	public static boolean isSpacerLine(ChatHudLine.Visible line) {
		return visibleText(line).startsWith(SPACER_MARKER_PREFIX);
	}

	public static boolean isPreviewSourceLine(ChatHudLine.Visible line) {
		return !isSpacerLine(line) && !UrlExtractor.findUrls(visibleText(line)).isEmpty();
	}

	private static String visibleText(ChatHudLine.Visible line) {
		StringBuilder text = new StringBuilder();
		OrderedText orderedText = line.content();
		orderedText.accept((index, style, codePoint) -> {
			text.appendCodePoint(codePoint);
			return true;
		});
		return text.toString();
	}

	private static boolean isSpacer(ChatHudLine.Visible line) {
		return isSpacerLine(line);
	}

	private static String spacerMarker(long previewId) {
		return SPACER_MARKER_PREFIX + previewId + "]";
	}

	private static Long spacerId(ChatHudLine.Visible line) {
		String text = visibleText(line);
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
		return Objects.equals(first, second);
	}

	private static int runStart(List<ChatHudLine.Visible> lines, int lineIndex, Long runId) {
		int index = lineIndex;
		while (index > 0 && isSpacer(lines.get(index - 1)) && sameId(runId, spacerId(lines.get(index - 1)))) {
			index--;
		}

		return index;
	}

	private static int runEnd(List<ChatHudLine.Visible> lines, int lineIndex, Long runId) {
		int index = lineIndex + 1;
		while (index < lines.size() && isSpacer(lines.get(index)) && sameId(runId, spacerId(lines.get(index)))) {
			index++;
		}

		return index;
	}

	static Optional<PreparedPreviewImage> decodeImage(byte[] imageBytes) {
		try {
			if (isGif(imageBytes)) {
				try {
					Optional<GifAnimation> animation = readGifAnimation(imageBytes);
					if (animation.isPresent() && animation.get().frames().size() > 1) {
						return Optional.of(PreparedPreviewImage.animated(animation.get().width(), animation.get().height(), animation.get().frames(), animation.get().frameDelaysMillis()));
					}

					animation.ifPresent(GifAnimation::close);
				} catch (IOException | RuntimeException exception) {
					LinkPreviewClient.LOGGER.debug("Failed to decode animated GIF, falling back to static image", exception);
				}
			}

			return Optional.of(PreparedPreviewImage.staticImage(readImage(imageBytes)));
		} catch (IOException exception) {
			LinkPreviewClient.LOGGER.warn("Failed to decode LinkPreview image. Worker may be returning an unsupported image format.", exception);
			return Optional.empty();
		}
	}

	private PreviewImage createImage(long cardId, PreparedPreviewImage preparedImage) {
		Identifier id = Identifier.of(LinkPreviewClient.MOD_ID, "preview/" + cardId);
		if (preparedImage.animated()) {
			NativeImage texturePixels = copyNative(preparedImage.frames().get(0));
			NativeImageBackedTexture texture = new NativeImageBackedTexture(texturePixels);
			MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
			LinkPreviewClient.LOGGER.info("Loaded animated LinkPreview GIF {}x{} with {} frames for card {}", preparedImage.width(), preparedImage.height(), preparedImage.frames().size(), cardId);
			return new PreviewImage(id, preparedImage.width(), preparedImage.height(), texture, preparedImage.frames(), preparedImage.frameDelaysMillis());
		}

		NativeImage nativeImage = preparedImage.staticPixels();
		NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
		LinkPreviewClient.LOGGER.info("Loaded LinkPreview image {}x{} for card {}", nativeImage.getWidth(), nativeImage.getHeight(), cardId);
		return new PreviewImage(id, nativeImage.getWidth(), nativeImage.getHeight());
	}

	private static boolean isGif(byte[] imageBytes) {
		return imageBytes.length >= 6
				&& imageBytes[0] == 'G'
				&& imageBytes[1] == 'I'
				&& imageBytes[2] == 'F'
				&& imageBytes[3] == '8'
				&& (imageBytes[4] == '7' || imageBytes[4] == '9')
				&& imageBytes[5] == 'a';
	}

	private static Optional<GifAnimation> readGifAnimation(byte[] imageBytes) throws IOException {
		java.util.Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
		ImageReader reader = readers.hasNext() ? readers.next() : null;
		if (reader == null) {
			return Optional.empty();
		}

		List<NativeImage> frames = new ArrayList<>();
		List<Integer> delays = new ArrayList<>();
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
			if (input == null) {
				return Optional.empty();
			}

			reader.setInput(input, false, false);
			int[] screenSize = gifScreenSize(reader);
			int canvasWidth = screenSize[0] > 0 ? screenSize[0] : reader.getWidth(0);
			int canvasHeight = screenSize[1] > 0 ? screenSize[1] : reader.getHeight(0);
			BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);

			for (int index = 0; index < MAX_GIF_FRAMES; index++) {
				BufferedImage frame;
				GifFrameMetadata metadata;
				try {
					frame = reader.read(index);
					metadata = gifFrameMetadata(reader.getImageMetadata(index));
				} catch (IndexOutOfBoundsException exception) {
					break;
				}

				BufferedImage beforeFrame = "restoreToPrevious".equals(metadata.disposalMethod()) ? copyBuffered(canvas) : null;
				Graphics2D graphics = canvas.createGraphics();
				try {
					graphics.drawImage(frame, metadata.left(), metadata.top(), null);
				} finally {
					graphics.dispose();
				}

				frames.add(bufferedToNative(canvas));
				delays.add(metadata.delayMillis());
				applyGifDisposal(canvas, beforeFrame, metadata, frame.getWidth(), frame.getHeight());
			}

			if (frames.isEmpty()) {
				return Optional.empty();
			}

			int[] frameDelays = new int[delays.size()];
			for (int index = 0; index < delays.size(); index++) {
				frameDelays[index] = delays.get(index);
			}

			return Optional.of(new GifAnimation(canvasWidth, canvasHeight, frames, frameDelays));
		} catch (IOException | RuntimeException exception) {
			for (NativeImage frame : frames) {
				frame.close();
			}
			throw exception;
		} finally {
			reader.dispose();
		}
	}

	private static int[] gifScreenSize(ImageReader reader) {
		try {
			Node root = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
			Node descriptor = firstChild(root, "LogicalScreenDescriptor");
			if (descriptor != null) {
				return new int[] {
						intAttribute(descriptor, "logicalScreenWidth", 0),
						intAttribute(descriptor, "logicalScreenHeight", 0)
				};
			}
		} catch (IOException | RuntimeException exception) {
			return new int[] { 0, 0 };
		}

		return new int[] { 0, 0 };
	}

	private static GifFrameMetadata gifFrameMetadata(IIOMetadata metadata) {
		Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
		Node descriptor = firstChild(root, "ImageDescriptor");
		Node control = firstChild(root, "GraphicControlExtension");
		int left = descriptor == null ? 0 : intAttribute(descriptor, "imageLeftPosition", 0);
		int top = descriptor == null ? 0 : intAttribute(descriptor, "imageTopPosition", 0);
		int delay = control == null ? DEFAULT_GIF_FRAME_DELAY_MILLIS : intAttribute(control, "delayTime", DEFAULT_GIF_FRAME_DELAY_MILLIS / 10) * 10;
		String disposal = control == null ? "none" : disposalMethod(control);

		return new GifFrameMetadata(left, top, Math.max(MIN_GIF_FRAME_DELAY_MILLIS, delay), disposal);
	}

	private static Node firstChild(Node node, String name) {
		if (node == null) {
			return null;
		}

		NodeList children = node.getChildNodes();
		for (int index = 0; index < children.getLength(); index++) {
			Node child = children.item(index);
			if (name.equals(child.getNodeName())) {
				return child;
			}
		}

		return null;
	}

	private static int intAttribute(Node node, String name, int fallback) {
		Node attribute = node.getAttributes().getNamedItem(name);
		if (attribute == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(attribute.getNodeValue());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static String disposalMethod(Node node) {
		Node attribute = node.getAttributes().getNamedItem("disposalMethod");
		return attribute == null ? "none" : attribute.getNodeValue();
	}

	private static void applyGifDisposal(BufferedImage canvas, BufferedImage beforeFrame, GifFrameMetadata metadata, int frameWidth, int frameHeight) {
		if ("restoreToPrevious".equals(metadata.disposalMethod()) && beforeFrame != null) {
			Graphics2D graphics = canvas.createGraphics();
			try {
				graphics.setComposite(AlphaComposite.Src);
				graphics.drawImage(beforeFrame, 0, 0, null);
			} finally {
				graphics.dispose();
			}
			return;
		}

		if ("restoreToBackgroundColor".equals(metadata.disposalMethod())) {
			Graphics2D graphics = canvas.createGraphics();
			try {
				graphics.setComposite(AlphaComposite.Clear);
				graphics.fillRect(metadata.left(), metadata.top(), frameWidth, frameHeight);
			} finally {
				graphics.dispose();
			}
		}
	}

	private static BufferedImage copyBuffered(BufferedImage source) {
		BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = copy.createGraphics();
		try {
			graphics.setComposite(AlphaComposite.Src);
			graphics.drawImage(source, 0, 0, null);
		} finally {
			graphics.dispose();
		}

		return copy;
	}

	private static NativeImage readImage(byte[] imageBytes) throws IOException {
		BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
		if (bufferedImage == null) {
			throw new IOException("Unsupported image format");
		}

		return bufferedToNative(bufferedImage);
	}

	private static NativeImage copyNative(NativeImage source) {
		NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), false);
		copy.copyFrom(source);
		return copy;
	}

	private static NativeImage bufferedToNative(BufferedImage bufferedImage) {
		NativeImage nativeImage = new NativeImage(bufferedImage.getWidth(), bufferedImage.getHeight(), false);
		for (int y = 0; y < bufferedImage.getHeight(); y++) {
			for (int x = 0; x < bufferedImage.getWidth(); x++) {
				nativeImage.setColor(x, y, argbToAbgr(bufferedImage.getRGB(x, y)));
			}
		}

		return nativeImage;
	}

	private static int argbToAbgr(int argb) {
		return (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16);
	}

	record PreparedPreviewImage(NativeImage staticPixels, int width, int height, List<NativeImage> frames, int[] frameDelaysMillis) {
		static PreparedPreviewImage staticImage(NativeImage pixels) {
			return new PreparedPreviewImage(pixels, pixels.getWidth(), pixels.getHeight(), List.of(), new int[0]);
		}

		static PreparedPreviewImage animated(int width, int height, List<NativeImage> frames, int[] frameDelaysMillis) {
			return new PreparedPreviewImage(null, width, height, frames, frameDelaysMillis);
		}

		boolean animated() {
			return frames.size() > 1;
		}

		void close() {
			if (staticPixels != null) {
				staticPixels.close();
			}
			for (NativeImage frame : frames) {
				frame.close();
			}
		}
	}

	private record GifAnimation(int width, int height, List<NativeImage> frames, int[] frameDelaysMillis) {
		void close() {
			for (NativeImage frame : frames) {
				frame.close();
			}
		}
	}

	private record GifFrameMetadata(int left, int top, int delayMillis, String disposalMethod) {
	}

	private static void release(PreviewCard card) {
		if (card.image() != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(card.image().textureId());
			card.image().close();
		}
	}

	private static String clip(TextRenderer font, String text, int width) {
		if (font.getWidth(text) <= width) {
			return text;
		}

		return font.trimToWidth(text, Math.max(0, width - font.getWidth("..."))) + "...";
	}

	private static float lineAlpha(int now, ChatHudLine.Visible line) {
		int ticks = now - line.addedTime();
		if (ticks >= 200) {
			return 0.0F;
		}

		double age = (double) ticks / 200.0D;
		double alpha = 1.0D - age;
		alpha = MathHelper.clamp(alpha * 10.0D, 0.0D, 1.0D);
		return (float) (alpha * alpha);
	}

	private static int withAlpha(int argb, float alphaScale) {
		int alpha = (argb >>> 24) & 0xFF;
		int scaledAlpha = MathHelper.clamp(Math.round(alpha * alphaScale), 0, 255);
		return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
	}

	private static HoverState hoverState(MinecraftClient minecraft, DrawContext graphics, double chatScale) {
		Window window = minecraft.getWindow();
		int mouseGuiX = (int) (minecraft.mouse.getX() * graphics.getScaledWindowWidth() / window.getWidth());
		int mouseGuiY = (int) (minecraft.mouse.getY() * graphics.getScaledWindowHeight() / window.getHeight());
		int localX = (int) Math.floor(mouseGuiX / chatScale - 4.0D);
		int localY = (int) Math.floor(mouseGuiY / chatScale);
		return new HoverState(mouseGuiX, mouseGuiY, localX, localY);
	}

	private static void showTooltipIfHovered(DrawContext graphics, TextRenderer font, HoverState hover, int x, int y, int textRight, String text) {
		if (hover == null || text == null || text.isBlank()) {
			return;
		}

		int width = MathHelper.clamp(font.getWidth(text), 0, Math.max(0, textRight - x));
		if (hover.localX() >= x && hover.localX() < x + width && hover.localY() >= y && hover.localY() < y + font.fontHeight) {
			graphics.drawTooltip(font, Text.literal(text), hover.mouseGuiX(), hover.mouseGuiY());
		}
	}

	private record HoverState(int mouseGuiX, int mouseGuiY, int localX, int localY) {
	}
}
