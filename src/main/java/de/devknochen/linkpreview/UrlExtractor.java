package de.devknochen.linkpreview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlExtractor {
	private static final Pattern URL_PATTERN = Pattern.compile("\\bhttps?://[^\\s<>()\"']+");

	private UrlExtractor() {
	}

	public static List<String> findUrls(String message) {
		if (message == null || message.isBlank()) {
			return List.of();
		}

		List<String> urls = new ArrayList<>();
		Matcher matcher = URL_PATTERN.matcher(message);

		while (matcher.find()) {
			String url = trimTrailingPunctuation(matcher.group());

			if (!url.isBlank() && !urls.contains(url)) {
				urls.add(url);
			}
		}

		return urls;
	}

	private static String trimTrailingPunctuation(String url) {
		int end = url.length();

		while (end > 0 && isTrailingPunctuation(url.charAt(end - 1))) {
			end--;
		}

		return url.substring(0, end);
	}

	private static boolean isTrailingPunctuation(char character) {
		return character == '.' || character == ',' || character == ';' || character == ':'
				|| character == '!' || character == '?' || character == ']' || character == '}';
	}
}
