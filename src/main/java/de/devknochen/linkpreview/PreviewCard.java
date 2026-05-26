package de.devknochen.linkpreview;

import java.util.List;

final class PreviewCard {
	private final long id;
	private final String url;
	private String site;
	private String title;
	private List<String> descriptionLines;
	private final int createdTick;
	private final boolean imageExpected;
	private PreviewImage image;
	private boolean imageFailed;

	PreviewCard(long id, String url, String site, String title, List<String> descriptionLines, int createdTick, boolean imageExpected) {
		this.id = id;
		this.url = url;
		this.site = site;
		this.title = title;
		this.descriptionLines = List.copyOf(descriptionLines);
		this.createdTick = createdTick;
		this.imageExpected = imageExpected;
	}

	long id() {
		return id;
	}

	String url() {
		return url;
	}

	String site() {
		return site;
	}

	String title() {
		return title;
	}

	List<String> descriptionLines() {
		return descriptionLines;
	}

	int createdTick() {
		return createdTick;
	}

	PreviewImage image() {
		return image;
	}

	boolean imageExpected() {
		return imageExpected;
	}

	void image(PreviewImage image) {
		this.image = image;
	}

	void update(String site, String title, List<String> descriptionLines) {
		this.site = site;
		this.title = title;
		this.descriptionLines = List.copyOf(descriptionLines);
	}

	boolean imageFailed() {
		return imageFailed;
	}

	void markImageFailed() {
		this.imageFailed = true;
	}
}
