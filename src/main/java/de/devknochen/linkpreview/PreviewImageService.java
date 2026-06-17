package de.devknochen.linkpreview;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PreviewImageService {
	private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
	private static final Pattern YOUTUBE_ID = Pattern.compile("(?:youtu\\.be/|(?:www\\.|m\\.)?youtube\\.com/(?:watch\\?[^#]*?v=|embed/|shorts/))([A-Za-z0-9_-]{11})");

	private final HttpClient httpClient;
	private final ConcurrentHashMap<String, CompletableFuture<Optional<byte[]>>> inFlight = new ConcurrentHashMap<>();

	public PreviewImageService(HttpClient httpClient) {
		this.httpClient = httpClient;
	}

	public CompletableFuture<Optional<byte[]>> fetch(String pageUrl) {
		return inFlight.computeIfAbsent(pageUrl, this::requestImage);
	}

	private CompletableFuture<Optional<byte[]>> requestImage(String pageUrl) {
		return youtubeId(pageUrl)
				.map(videoId -> requestYouTubeImage(videoId)
						.thenCompose(image -> image.isPresent() ? CompletableFuture.completedFuture(image) : requestWorkerImage(pageUrl))
						.whenComplete((ignoredResult, ignoredException) -> inFlight.remove(pageUrl)))
				.orElseGet(() -> requestWorkerImage(pageUrl));
	}

	private CompletableFuture<Optional<byte[]>> requestWorkerImage(String pageUrl) {
		HttpRequest request = HttpRequest.newBuilder(PreviewService.workerUri("/image", pageUrl))
				.timeout(Duration.ofMillis(PreviewService.REQUEST_TIMEOUT_MILLIS))
				.header("Accept", "image/png,image/jpeg,image/gif,*/*;q=0.5")
				.header("Authorization", PreviewService.CLIENT_AUTHORIZATION)
				.header(PreviewService.CLIENT_HEADER, PreviewService.CLIENT_TOKEN)
				.GET()
				.build();

		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
				.thenApply(this::parseResponse)
				.exceptionally(failure -> {
					LinkPreviewClient.LOGGER.debug("Link preview image request failed for {}", pageUrl, failure);
					return Optional.empty();
				})
				.whenComplete((ignoredResult, ignoredException) -> inFlight.remove(pageUrl));
	}

	private CompletableFuture<Optional<byte[]>> requestYouTubeImage(String videoId) {
		return requestImageUrl("https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg")
				.thenCompose(image -> image.isPresent() ? CompletableFuture.completedFuture(image) : requestImageUrl("https://i.ytimg.com/vi/" + videoId + "/hq720.jpg"))
				.thenCompose(image -> image.isPresent() ? CompletableFuture.completedFuture(image) : requestImageUrl("https://i.ytimg.com/vi/" + videoId + "/sddefault.jpg"));
	}

	private CompletableFuture<Optional<byte[]>> requestImageUrl(String url) {
		HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(Duration.ofMillis(PreviewService.REQUEST_TIMEOUT_MILLIS))
				.header("Accept", "image/jpeg,image/png,image/gif,*/*;q=0.5")
				.GET()
				.build();

		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
				.thenApply(this::parseResponse)
				.exceptionally(failure -> {
					LinkPreviewClient.LOGGER.debug("Link preview image request failed for {}", url, failure);
					return Optional.empty();
				});
	}

	private static Optional<String> youtubeId(String pageUrl) {
		Matcher matcher = YOUTUBE_ID.matcher(pageUrl);
		return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	private Optional<byte[]> parseResponse(HttpResponse<byte[]> response) {
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			LinkPreviewClient.LOGGER.warn("LinkPreview image request returned HTTP {}", response.statusCode());
			return Optional.empty();
		}

		String contentType = response.headers().firstValue("content-type").orElse("");
		if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
			LinkPreviewClient.LOGGER.warn("LinkPreview image request returned non-image content type: {}", contentType);
			return Optional.empty();
		}

		byte[] body = response.body();
		if (body.length == 0 || body.length > MAX_IMAGE_BYTES) {
			LinkPreviewClient.LOGGER.warn("LinkPreview image request returned invalid size: {} bytes", body.length);
			return Optional.empty();
		}

		return Optional.of(body);
	}
}
