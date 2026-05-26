package de.devknochen.linkpreview;

import java.net.URI;
import java.util.Optional;

import net.minecraft.client.Minecraft;

public final class PreviewRenderer {
	private static final int MAX_TITLE_LENGTH = 120;
	private static final int MAX_DESCRIPTION_LENGTH = 180;
	private static final int MAX_SITE_LENGTH = 48;

	private final PreviewCardStore cardStore;
	private final PreviewImageService imageService;

	public PreviewRenderer(PreviewCardStore cardStore, PreviewImageService imageService) {
		this.cardStore = cardStore;
		this.imageService = imageService;
	}

	public long reserve(String sourceUrl) {
		Minecraft minecraft = Minecraft.getInstance();
		int createdTick = minecraft.gui == null ? 0 : minecraft.gui.getGuiTicks();
		long id = cardStore.nextId();
		cardStore.reserve(id, sourceUrl, limit(hostName(sourceUrl), MAX_SITE_LENGTH), createdTick);
		return id;
	}

	public void complete(long previewId, String sourceUrl, Optional<PreviewResponse> preview) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> completeOnClientThread(minecraft, previewId, sourceUrl, preview));
	}

	private void completeOnClientThread(Minecraft minecraft, long previewId, String sourceUrl, Optional<PreviewResponse> maybePreview) {
		if (minecraft.gui == null || maybePreview.isEmpty() || maybePreview.get().isEmpty()) {
			cardStore.discard(previewId);
			return;
		}

		PreviewResponse preview = maybePreview.get();
		String site = limit(preview.displaySiteName(hostName(sourceUrl)), MAX_SITE_LENGTH);
		String title = limit(preview.displayTitle(sourceUrl), MAX_TITLE_LENGTH);
		String description = limit(preview.displayDescription(), MAX_DESCRIPTION_LENGTH);
		boolean hasImage = preview.hasImage();
		cardStore.update(previewId, site, title, wrap(description, 58, 2));

		if (hasImage) {
			imageService.fetch(sourceUrl).thenAccept(image -> minecraft.execute(() -> cardStore.attachImage(previewId, image)));
		} else {
			LinkPreviewClient.LOGGER.warn("LinkPreview metadata contains no image for {}", sourceUrl);
			cardStore.markImageUnavailable(previewId);
		}
	}

	private static String hostName(String url) {
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			return host == null ? url : host;
		} catch (IllegalArgumentException exception) {
			return url;
		}
	}

	private static String limit(String text, int maxLength) {
		String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();

		if (normalized.length() <= maxLength) {
			return normalized;
		}

		return normalized.substring(0, maxLength - 3) + "...";
	}

	private static java.util.List<String> wrap(String text, int maxLineLength, int maxLines) {
		String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
		if (normalized.isBlank()) {
			return java.util.List.of();
		}

		java.util.List<String> lines = new java.util.ArrayList<>();
		String remaining = normalized;

		while (!remaining.isBlank() && lines.size() < maxLines) {
			if (remaining.length() <= maxLineLength) {
				lines.add(remaining);
				break;
			}

			int split = remaining.lastIndexOf(' ', maxLineLength);
			if (split < 24) {
				split = maxLineLength;
			}

			String line = remaining.substring(0, split).trim();
			remaining = remaining.substring(split).trim();

			if (lines.size() == maxLines - 1 && !remaining.isBlank()) {
				line = limit(line + " " + remaining, maxLineLength);
				remaining = "";
			}

			lines.add(line);
		}

		return lines;
	}
}
