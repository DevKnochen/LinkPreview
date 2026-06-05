package de.devknochen.linkpreview;

import java.util.List;

final class PreviewCard {
	private final long id;
	private String site;
	private String title;
	private List<String> descriptionLines;
	private final boolean imageExpected;
	private PreviewImage image;
	private boolean imageFailed;

	PreviewCard(long id, String site, String title, List<String> descriptionLines, boolean imageExpected) {
		this.id = id;
		this.site = site;
		this.title = title;
		this.descriptionLines = List.copyOf(descriptionLines);
		this.imageExpected = imageExpected;
	}

	long id() {
		return id;
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
