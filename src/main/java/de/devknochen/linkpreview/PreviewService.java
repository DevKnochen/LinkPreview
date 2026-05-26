package de.devknochen.linkpreview;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public final class PreviewService {
	private static final Gson GSON = new Gson();
	public static final int REQUEST_TIMEOUT_MILLIS = 5000;
	private static final int MAX_CACHE_ENTRIES = 128;
	private static final String PREVIEW_ENDPOINT = "https://linkpreview-worker.knochenn.de/preview";
	static final String CLIENT_TOKEN = "lp_a5108d4106aa4db3bad90438006ff7c69e35b8bbc65f4dd3927c1c9dc93664f2";

	private final HttpClient httpClient;
	private final Map<String, PreviewResponse> cache;
	private final Map<String, CompletableFuture<Optional<PreviewResponse>>> inFlight = new ConcurrentHashMap<>();

	public PreviewService(HttpClient httpClient) {
		this.httpClient = httpClient;
		this.cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, PreviewResponse> eldest) {
				return size() > MAX_CACHE_ENTRIES;
			}
		});
	}

	public CompletableFuture<Optional<PreviewResponse>> fetch(String url) {
		PreviewResponse cached = cache.get(url);
		if (cached != null) {
			return CompletableFuture.completedFuture(Optional.of(cached));
		}

		return inFlight.computeIfAbsent(url, this::requestPreview);
	}

	public HttpClient httpClient() {
		return httpClient;
	}

	public static URI workerUri(String path, String url) {
		String endpoint = "https://linkpreview-worker.knochenn.de" + path;
		return URI.create(endpoint + "?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8));
	}

	private CompletableFuture<Optional<PreviewResponse>> requestPreview(String url) {
		HttpRequest request;

		try {
			request = HttpRequest.newBuilder(workerUri(url))
					.timeout(Duration.ofMillis(REQUEST_TIMEOUT_MILLIS))
					.header("Accept", "application/json")
					.header("X-LinkPreview-Client", CLIENT_TOKEN)
					.GET()
					.build();
		} catch (IllegalArgumentException exception) {
			return CompletableFuture.completedFuture(Optional.empty());
		}

		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> parseResponse(url, response))
				.exceptionally(exception -> {
					LinkPreviewClient.LOGGER.debug("Link preview request failed for {}", url, exception);
					return Optional.empty();
				})
				.whenComplete((preview, exception) -> inFlight.remove(url));
	}

	private Optional<PreviewResponse> parseResponse(String url, HttpResponse<String> response) {
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			LinkPreviewClient.LOGGER.warn("LinkPreview Worker returned HTTP {} for {}: {}", response.statusCode(), url, limit(response.body(), 300));
			return Optional.empty();
		}

		try {
			PreviewResponse preview = GSON.fromJson(response.body(), PreviewResponse.class);

			if (preview == null || preview.isEmpty()) {
				LinkPreviewClient.LOGGER.warn("LinkPreview Worker returned empty metadata for {}: {}", url, limit(response.body(), 300));
				return Optional.empty();
			}

			cache.put(url, preview);
			return Optional.of(preview);
		} catch (JsonSyntaxException exception) {
			LinkPreviewClient.LOGGER.warn("LinkPreview Worker returned invalid JSON for {}: {}", url, limit(response.body(), 300), exception);
			return Optional.empty();
		}
	}

	private static String limit(String text, int maxLength) {
		if (text == null) {
			return "";
		}

		String normalized = text.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= maxLength) {
			return normalized;
		}

		return normalized.substring(0, maxLength - 3) + "...";
	}

	private URI workerUri(String url) {
		String separator = PREVIEW_ENDPOINT.contains("?") ? "&" : "?";
		return URI.create(PREVIEW_ENDPOINT + separator + "url=" + URLEncoder.encode(url, StandardCharsets.UTF_8));
	}
}
