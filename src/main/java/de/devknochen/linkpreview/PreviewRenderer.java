package de.devknochen.linkpreview;

import java.net.URI;

import net.minecraft.client.MinecraftClient;

public final class PreviewRenderer {
	private static final int MAX_TITLE_LENGTH = 120;
	private static final int MAX_DESCRIPTION_LENGTH = 180;
	private static final int MAX_SITE_LENGTH = 48;
	private static final int DESCRIPTION_WRAP_WIDTH = 58;
	private static final int DESCRIPTION_WRAP_LINES = 2;

	private final PreviewCardStore cardStore;
	private final PreviewImageService imageService;

	public PreviewRenderer(PreviewCardStore cardStore, PreviewImageService imageService) {
		this.cardStore = cardStore;
		this.imageService = imageService;
	}

	public long reserve(String sourceUrl) {
		long id = cardStore.nextId();
		cardStore.reserve(id, limit(hostName(sourceUrl), MAX_SITE_LENGTH));
		return id;
	}

	public void complete(long previewId, String sourceUrl, PreviewResponse preview) {
		MinecraftClient minecraft = MinecraftClient.getInstance();
		minecraft.execute(() -> completeOnClientThread(minecraft, previewId, sourceUrl, preview));
	}

	public void discard(long previewId) {
		MinecraftClient.getInstance().execute(() -> cardStore.discard(previewId));
	}

	private void completeOnClientThread(MinecraftClient minecraft, long previewId, String sourceUrl, PreviewResponse preview) {
		if (preview.isEmpty()) {
			cardStore.discard(previewId);
			return;
		}

		String site = limit(preview.displaySiteName(hostName(sourceUrl)), MAX_SITE_LENGTH);
		String title = limit(preview.displayTitle(sourceUrl), MAX_TITLE_LENGTH);
		String description = limit(preview.displayDescription(), MAX_DESCRIPTION_LENGTH);
		boolean hasImage = preview.hasImage();
		cardStore.update(previewId, site, title, wrap(description));

		if (hasImage) {
			imageService.fetch(sourceUrl)
					.thenApply(image -> image.flatMap(PreviewCardStore::decodeImage))
					.exceptionally(failure -> {
						LinkPreviewClient.LOGGER.warn("Failed to prepare LinkPreview image for {}", sourceUrl, failure);
						return java.util.Optional.empty();
					})
					.thenAccept(image -> minecraft.execute(() -> image.ifPresentOrElse(
							preparedImage -> attachImage(previewId, sourceUrl, preparedImage),
							() -> cardStore.markImageUnavailable(previewId)
					)));
		} else {
			LinkPreviewClient.LOGGER.warn("LinkPreview metadata contains no image for {}", sourceUrl);
			cardStore.markImageUnavailable(previewId);
		}
	}

	private void attachImage(long previewId, String sourceUrl, PreviewCardStore.PreparedPreviewImage preparedImage) {
		try {
			cardStore.attachImage(previewId, preparedImage);
		} catch (RuntimeException exception) {
			preparedImage.close();
			cardStore.markImageUnavailable(previewId);
			LinkPreviewClient.LOGGER.warn("Failed to attach LinkPreview image for {}", sourceUrl, exception);
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

	private static java.util.List<String> wrap(String text) {
		String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
		if (normalized.isBlank()) {
			return java.util.List.of();
		}

		java.util.List<String> lines = new java.util.ArrayList<>();
		String remaining = normalized;

		while (!remaining.isBlank() && lines.size() < DESCRIPTION_WRAP_LINES) {
			if (remaining.length() <= DESCRIPTION_WRAP_WIDTH) {
				lines.add(remaining);
				break;
			}

			int split = remaining.lastIndexOf(' ', DESCRIPTION_WRAP_WIDTH);
			if (split < 24) {
				split = DESCRIPTION_WRAP_WIDTH;
			}

			String line = remaining.substring(0, split).trim();
			remaining = remaining.substring(split).trim();

			if (lines.size() == DESCRIPTION_WRAP_LINES - 1 && !remaining.isBlank()) {
				line = limit(line + " " + remaining, DESCRIPTION_WRAP_WIDTH);
				remaining = "";
			}

			lines.add(line);
		}

		return lines;
	}
}
