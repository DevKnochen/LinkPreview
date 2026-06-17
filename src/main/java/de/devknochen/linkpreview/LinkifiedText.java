package de.devknochen.linkpreview;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

public final class LinkifiedText {
	private static final Pattern URL_PATTERN = Pattern.compile("\\bhttps?://[^\\s<>()\"']+");
	private static final int MAX_DISPLAY_URL_LENGTH = 44;

	private LinkifiedText() {
	}

	public static Text from(String text) {
		MutableText result = Text.empty();
		Matcher matcher = URL_PATTERN.matcher(text);
		int position = 0;

		while (matcher.find()) {
			String rawUrl = matcher.group();
			String url = trimTrailingPunctuation(rawUrl);

			if (matcher.start() > position) {
				result.append(Text.literal(text.substring(position, matcher.start())));
			}

			result.append(Text.literal(displayUrl(url)).setStyle(urlStyle(url)));
			position = matcher.start() + url.length();
		}

		if (position < text.length()) {
			result.append(Text.literal(text.substring(position)));
		}

		return result;
	}

	private static Style urlStyle(String url) {
		Style style = Style.EMPTY
				.withColor(TextColor.fromRgb(0x2F81F7))
				.withUnderline(true);

		try {
			URI uri = URI.create(url);
			return style.withClickEvent(new ClickEvent.OpenUrl(uri));
		} catch (IllegalArgumentException exception) {
			return style;
		}
	}

	private static String trimTrailingPunctuation(String url) {
		int end = url.length();

		while (end > 0 && isTrailingPunctuation(url.charAt(end - 1))) {
			end--;
		}

		return url.substring(0, end);
	}

	private static String displayUrl(String url) {
		if (url.length() <= MAX_DISPLAY_URL_LENGTH) {
			return url;
		}

		int prefixLength = 28;
		int suffixLength = 13;
		return url.substring(0, prefixLength) + "..." + url.substring(url.length() - suffixLength);
	}

	private static boolean isTrailingPunctuation(char character) {
		return character == '.' || character == ',' || character == ';' || character == ':'
				|| character == '!' || character == '?' || character == ']' || character == '}';
	}
}
