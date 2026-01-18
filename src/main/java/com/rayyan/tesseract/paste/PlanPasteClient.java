package com.rayyan.tesseract.paste;

import com.rayyan.tesseract.TesseractMod;
import com.rayyan.tesseract.gumloop.GumloopClient;
import com.rayyan.tesseract.gumloop.GumloopPayload;
import com.rayyan.tesseract.jobs.BuildJobManager;
import com.rayyan.tesseract.jobs.BuildQueueManager;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class PlanPasteClient {
	private static final HttpClient CLIENT = HttpClient.newHttpClient();
	private static final int LOG_BODY_PREVIEW = 240;

	private PlanPasteClient() {}

	public static void fetchAndBuild(ServerPlayerEntity player, Selection selection, String source) {
		if (player == null || player.getServer() == null) {
			return;
		}
		URI uri = parseSource(source);
		if (uri == null) {
			player.sendMessage(Text.of("Error: paste source must be a valid http(s) URL."), false);
			BuildJobManager.finish(player.getUuid());
			return;
		}
		String requestId = "paste-" + System.currentTimeMillis() + "-" + player.getUuid().toString().substring(0, 8);
		player.sendMessage(Text.of("Tesseract fetching plan..."), false);
		TesseractMod.LOGGER.info("Paste {} -> fetching plan from {}", requestId, uri);

		HttpRequest request = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(Duration.ofSeconds(15))
			.GET()
			.build();

		CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, error) -> {
				if (player.getServer() == null) {
					return;
				}
				player.getServer().execute(() -> {
					if (error != null) {
						TesseractMod.LOGGER.error("Paste {} -> request failed: {}", requestId, error.toString());
						player.sendMessage(Text.of("Error: failed to fetch plan (request " + requestId + ")."), false);
						BuildJobManager.finish(player.getUuid());
						return;
					}
					if (response == null) {
						player.sendMessage(Text.of("Error: plan response was empty (request " + requestId + ")."), false);
						BuildJobManager.finish(player.getUuid());
						return;
					}
					int status = response.statusCode();
					String body = response.body();
					if (status < 200 || status >= 300) {
						TesseractMod.LOGGER.warn("Paste {} -> non-2xx response: {}", requestId, preview(body));
						player.sendMessage(Text.of("Error: plan server returned status " + status + " (request " + requestId + ")."), false);
						BuildJobManager.finish(player.getUuid());
						return;
					}
					GumloopClient.PlanValidationResult result = GumloopClient.parsePlanForSelection(body, selection, requestId);
					if (result.error != null) {
						player.sendMessage(Text.of("Error: " + result.error + " (request " + requestId + ")."), false);
						BuildJobManager.finish(player.getUuid());
						return;
					}
					GumloopPayload.Response plan = result.plan;
					if (plan == null || plan.ops == null) {
						player.sendMessage(Text.of("Error: plan response missing ops (request " + requestId + ")."), false);
						BuildJobManager.finish(player.getUuid());
						return;
					}
					boolean built = BuildQueueManager.startInstantBuild(player, selection, plan);
					if (!built) {
						player.sendMessage(Text.of("Error: failed to place plan blocks (request " + requestId + ")."), false);
					}
					BuildJobManager.finish(player.getUuid());
				});
			});
	}

	private static URI parseSource(String source) {
		if (source == null) {
			return null;
		}
		String trimmed = source.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		try {
			URI uri = URI.create(trimmed);
			String scheme = uri.getScheme();
			if (scheme == null) {
				return null;
			}
			if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
				return null;
			}
			return uri;
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String preview(String body) {
		if (body == null) {
			return "<null>";
		}
		if (body.length() <= LOG_BODY_PREVIEW) {
			return body;
		}
		return body.substring(0, LOG_BODY_PREVIEW) + "...";
	}
}
