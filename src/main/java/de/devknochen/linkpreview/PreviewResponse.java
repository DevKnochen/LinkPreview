package de.devknochen.linkpreview;

public record PreviewResponse(
		String title,
		String description,
		String image,
		String siteName,
		String finalUrl
) {
	public boolean isEmpty() {
		return isBlank(title) && isBlank(description);
	}

	public String displayTitle(String fallbackUrl) {
		if (!isBlank(title)) {
			return title.trim();
		}

		if (!isBlank(siteName)) {
			return siteName.trim();
		}

		return fallbackUrl;
	}

	public String displayDescription() {
		return isBlank(description) ? "" : description.trim();
	}

	public String displaySiteName(String fallback) {
		return isBlank(siteName) ? fallback : siteName.trim();
	}

	public boolean hasImage() {
		return !isBlank(image);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
