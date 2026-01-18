package com.rayyan.tesseract.gumloop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.rayyan.tesseract.TesseractMod;
import com.rayyan.tesseract.gumloop.GumloopPayload.BlockOp;
import com.rayyan.tesseract.gumloop.GumloopPayload.Context;
import com.rayyan.tesseract.gumloop.GumloopPayload.Origin;
import com.rayyan.tesseract.gumloop.GumloopPayload.Request;
import com.rayyan.tesseract.gumloop.GumloopPayload.Size;
import com.rayyan.tesseract.jobs.BuildQueueManager;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class GumloopClient {
	private static final int MAX_CONTEXT_BLOCKS = 500;
	private static final int MAX_BLOCKS = 600;
	private static final int DEFAULT_BUILD_HEIGHT = 12;
	private static final int LOG_BODY_PREVIEW = 240;
	private static final int POLL_INTERVAL_MS = 1000;
	private static final int MAX_POLL_ATTEMPTS = 120;
	private static final Gson GSON = new Gson();
	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	private GumloopClient() {}

	public static void sendBuildRequest(ServerPlayerEntity player, Selection buildSelection, Selection contextSelection, String prompt) {
		String webhook = System.getenv("GUMLOOP_WEBHOOK_URL");
		if (webhook == null || webhook.isBlank()) {
			player.sendMessage(Text.of("Error: GUMLOOP_WEBHOOK_URL is not set."), false);
			return;
		}

		String requestId = "req-" + System.currentTimeMillis() + "-" + player.getUuid().toString().substring(0, 8);
		long startNanos = System.nanoTime();
		Request request = buildRequest(player, buildSelection, contextSelection, prompt);
		String json = GSON.toJson(request);
		GumloopProgressManager.startDrafting(player, requestId);
		TesseractMod.LOGGER.info("Gumloop {} -> sending request (size={}, contextBlocks={}, hasScreenshot={})",
			requestId,
			request.size == null ? "unknown" : request.size.w + "x" + request.size.h + "x" + request.size.l,
			request.context == null || request.context.blocks == null ? 0 : request.context.blocks.size(),
			request.context != null && request.context.screenshot != null);

		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(URI.create(webhook))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();

		CLIENT.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, error) -> {
				if (player.getServer() == null) {
					return;
				}
				player.getServer().execute(() -> {
					long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
					if (error != null) {
						TesseractMod.LOGGER.error("Gumloop {} -> request failed after {}ms: {}", requestId, elapsedMs, error.toString());
						player.sendMessage(Text.of("Error: Gumloop request failed (request " + requestId + ")."), false);
						GumloopProgressManager.stopDrafting(player.getUuid());
						com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
						return;
					}
					if (response == null) {
						TesseractMod.LOGGER.error("Gumloop {} -> null response after {}ms.", requestId, elapsedMs);
						player.sendMessage(Text.of("Error: Gumloop response was empty (request " + requestId + ")."), false);
						GumloopProgressManager.stopDrafting(player.getUuid());
						com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
						return;
					}
					int status = response.statusCode();
					String body = response.body();
					TesseractMod.LOGGER.info("Gumloop {} -> status={} in {}ms (bodyLen={})",
						requestId,
						status,
						elapsedMs,
						body == null ? 0 : body.length());
					if (status < 200 || status >= 300) {
						TesseractMod.LOGGER.warn("Gumloop {} -> non-2xx response: {}", requestId, preview(body));
						player.sendMessage(Text.of("Error: Gumloop returned status " + status + " (request " + requestId + ")."), false);
						GumloopProgressManager.stopDrafting(player.getUuid());
						com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
						return;
					}
					String runId = extractRunId(body);
					if (runId != null) {
						TesseractMod.LOGGER.info("Gumloop {} -> received run_id {}, polling for outputs.", requestId, runId);
						pollRunForPlan(player, buildSelection, requestId, runId, webhook, 0);
						return;
					}
					PlanResult planResult = parseAndValidatePlan(body, buildSelection, requestId);
					if (planResult.error != null) {
						player.sendMessage(Text.of("Error: " + planResult.error + " (request " + requestId + ")."), false);
						GumloopProgressManager.stopDrafting(player.getUuid());
						com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
						return;
					}
					GumloopPayload.Response plan = planResult.plan;
					int count = plan.ops == null ? 0 : plan.ops.size();
					player.sendMessage(Text.of("Plan validated: " + count + " ops."), false);
					if (plan.meta != null && plan.meta.warnings != null && !plan.meta.warnings.isEmpty()) {
						player.sendMessage(Text.of("Warnings: " + String.join("; ", plan.meta.warnings)), false);
					}
					GumloopProgressManager.stopDrafting(player.getUuid());
					boolean queued = BuildQueueManager.startBuild(player, buildSelection, plan);
					if (!queued) {
						player.sendMessage(Text.of("Error: failed to start build (request " + requestId + ")."), false);
						com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
					}
				});
			});
	}

	private static PlanResult parseAndValidatePlan(String body, Selection buildSelection, String requestId) {
		if (body == null || body.isBlank()) {
			TesseractMod.LOGGER.warn("Gumloop {} -> empty response body.", requestId);
			return PlanResult.error("Gumloop returned empty response.");
		}
		BlockPos size = effectiveBuildSize(buildSelection);
		if (size == null) {
			TesseractMod.LOGGER.warn("Gumloop {} -> invalid selection size.", requestId);
			return PlanResult.error("Invalid build selection size.");
		}
		JsonObject planJson = extractPlanJson(body);
		if (planJson == null) {
			String asyncHint = detectRunMetadata(body);
			TesseractMod.LOGGER.warn("Gumloop {} -> missing plan JSON. Body preview: {}", requestId, preview(body));
			if (asyncHint != null) {
				return PlanResult.error(asyncHint);
			}
			return PlanResult.error("Could not find build plan in Gumloop response. Body preview: " + preview(body));
		}
		return parseAndValidatePlanJson(planJson, size, requestId);
	}

	private static PlanResult parseAndValidatePlanJson(JsonObject planJson, BlockPos size, String requestId) {
		String validationError = validatePlan(planJson, size, defaultPalette(), MAX_BLOCKS);
		if (validationError != null) {
			TesseractMod.LOGGER.warn("Gumloop {} -> validation failed: {}", requestId, validationError);
			return PlanResult.error(validationError);
		}
		GumloopPayload.Response plan = GSON.fromJson(planJson, GumloopPayload.Response.class);
		if (plan == null || plan.ops == null) {
			return PlanResult.error("Parsed plan is missing ops.");
		}
		if (plan.meta == null) {
			plan.meta = new GumloopPayload.Meta();
			plan.meta.blockCount = plan.ops.size();
		}
		return PlanResult.success(plan);
	}

	private static void pollRunForPlan(ServerPlayerEntity player, Selection buildSelection, String requestId, String runId, String webhook, int attempt) {
		if (player == null || player.getServer() == null) {
			return;
		}
		if (attempt >= MAX_POLL_ATTEMPTS) {
			player.getServer().execute(() -> {
				player.sendMessage(Text.of("Error: Gumloop run timed out waiting for outputs (request " + requestId + ")."), false);
				GumloopProgressManager.stopDrafting(player.getUuid());
				com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
			});
			return;
		}

		PollConfig config = parseWebhookConfig(webhook);
		URI pollUri = buildPollUri(runId, config);
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(pollUri)
			.timeout(Duration.ofSeconds(15))
			.header("Content-Type", "application/json")
			.GET();
		if (config.apiKey != null && !config.apiKey.isBlank()) {
			builder.header("Authorization", "Bearer " + config.apiKey);
		}
		HttpRequest pollRequest = builder.build();

		CLIENT.sendAsync(pollRequest, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, error) -> {
				if (player.getServer() == null) {
					return;
				}
				player.getServer().execute(() -> {
					if (error != null || response == null) {
						TesseractMod.LOGGER.warn("Gumloop {} -> poll attempt {} failed: {}", requestId, attempt, error);
						scheduleNextPoll(player, buildSelection, requestId, runId, webhook, attempt + 1);
						return;
					}
					String body = response.body();
					JsonElement root;
					try {
						root = JsonParser.parseString(body);
					} catch (JsonSyntaxException ex) {
						scheduleNextPoll(player, buildSelection, requestId, runId, webhook, attempt + 1);
						return;
					}
					if (!root.isJsonObject()) {
						scheduleNextPoll(player, buildSelection, requestId, runId, webhook, attempt + 1);
						return;
					}
					JsonObject obj = root.getAsJsonObject();
					String state = getString(obj.get("state"));
					if (state != null && state.equalsIgnoreCase("FAILED")) {
						player.sendMessage(Text.of("Error: Gumloop run failed (request " + requestId + ")."), false);
						GumloopProgressManager.stopDrafting(player.getUuid());
						com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
						return;
					}
					JsonElement outputs = obj.get("outputs");
					if (outputs != null && !outputs.isJsonNull()) {
						JsonObject planJson = findPlan(outputs, 0);
						if (planJson != null) {
							PlanResult planResult = parseAndValidatePlanJson(planJson, effectiveBuildSize(buildSelection), requestId);
							if (planResult.error != null) {
								player.sendMessage(Text.of("Error: " + planResult.error + " (request " + requestId + ")."), false);
								GumloopProgressManager.stopDrafting(player.getUuid());
								com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
								return;
							}
							GumloopPayload.Response plan = planResult.plan;
							int count = plan.ops == null ? 0 : plan.ops.size();
							player.sendMessage(Text.of("Plan validated: " + count + " ops."), false);
							if (plan.meta != null && plan.meta.warnings != null && !plan.meta.warnings.isEmpty()) {
								player.sendMessage(Text.of("Warnings: " + String.join("; ", plan.meta.warnings)), false);
							}
							GumloopProgressManager.stopDrafting(player.getUuid());
							boolean queued = BuildQueueManager.startBuild(player, buildSelection, plan);
							if (!queued) {
								player.sendMessage(Text.of("Error: failed to start build (request " + requestId + ")."), false);
								com.rayyan.tesseract.jobs.BuildJobManager.finish(player.getUuid());
							}
							return;
						}
					}
					scheduleNextPoll(player, buildSelection, requestId, runId, webhook, attempt + 1);
				});
			});
	}

	private static void scheduleNextPoll(ServerPlayerEntity player, Selection buildSelection, String requestId, String runId, String webhook, int nextAttempt) {
		CompletableFuture.delayedExecutor(POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
			.execute(() -> pollRunForPlan(player, buildSelection, requestId, runId, webhook, nextAttempt));
	}

	private static PollConfig parseWebhookConfig(String webhook) {
		PollConfig config = new PollConfig();
		try {
			URI uri = URI.create(webhook);
			Map<String, String> params = parseQueryParams(uri.getRawQuery());
			config.apiKey = params.get("api_key");
			config.userId = params.get("user_id");
		} catch (IllegalArgumentException ex) {
			// ignore and use defaults
		}
		return config;
	}

	private static URI buildPollUri(String runId, PollConfig config) {
		StringBuilder sb = new StringBuilder("https://api.gumloop.com/api/v1/get_pl_run?run_id=");
		sb.append(runId);
		if (config.userId != null && !config.userId.isBlank()) {
			sb.append("&user_id=").append(urlEncode(config.userId));
		}
		if (config.apiKey != null && !config.apiKey.isBlank()) {
			sb.append("&api_key=").append(urlEncode(config.apiKey));
		}
		return URI.create(sb.toString());
	}

	private static String extractRunId(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonElement root = JsonParser.parseString(body);
			if (!root.isJsonObject()) {
				return null;
			}
			JsonObject obj = root.getAsJsonObject();
			return getString(obj.get("run_id"));
		} catch (JsonSyntaxException ex) {
			return null;
		}
	}

	private static Map<String, String> parseQueryParams(String query) {
		Map<String, String> params = new HashMap<>();
		if (query == null || query.isBlank()) {
			return params;
		}
		String[] pairs = query.split("&");
		for (String pair : pairs) {
			int idx = pair.indexOf('=');
			if (idx <= 0) {
				continue;
			}
			String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
			String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
			params.put(key, value);
		}
		return params;
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static final class PollConfig {
		private String apiKey;
		private String userId;
	}

	private static String detectRunMetadata(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonElement root = JsonParser.parseString(body);
			if (!root.isJsonObject()) {
				return null;
			}
			JsonObject obj = root.getAsJsonObject();
			if (obj.has("run_id") && obj.has("url") && !obj.has("meta") && !obj.has("ops")) {
				return "Gumloop returned run metadata (run_id/url) instead of a plan. " +
					"Use the Webhook Trigger URL and an Output node to return the plan, " +
					"or enable synchronous webhook responses in Gumloop.";
			}
		} catch (JsonSyntaxException ex) {
			return null;
		}
		return null;
	}

	private static BlockPos effectiveBuildSize(Selection buildSelection) {
		BlockPos size = buildSelection.getSize();
		if (size == null) {
			return null;
		}
		// If both corners were clicked on the ground (same Y), the selection height is 1.
		// For our MVP UX, treat this as a 2D footprint selection and allow a default build height.
		if (size.getY() <= 1) {
			return new BlockPos(size.getX(), DEFAULT_BUILD_HEIGHT, size.getZ());
		}
		return size;
	}

	private static JsonObject extractPlanJson(String body) {
		JsonElement root;
		try {
			root = JsonParser.parseString(body);
		} catch (JsonSyntaxException ex) {
			return null;
		}
		return findPlan(root, 0);
	}

	private static JsonObject findPlan(JsonElement element, int depth) {
		if (element == null || element.isJsonNull() || depth > 6) {
			return null;
		}
		if (element.isJsonObject()) {
			JsonObject obj = element.getAsJsonObject();
			if (obj.has("meta") || obj.has("ops")) {
				return obj;
			}
			if (obj.has("response")) {
				JsonObject found = findPlan(obj.get("response"), depth + 1);
				if (found != null) {
					return found;
				}
			}
			for (String key : obj.keySet()) {
				JsonObject found = findPlan(obj.get(key), depth + 1);
				if (found != null) {
					return found;
				}
			}
			return null;
		}
		if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				JsonObject found = findPlan(child, depth + 1);
				if (found != null) {
					return found;
				}
			}
			return null;
		}
		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isString()) {
				try {
					return findPlan(JsonParser.parseString(primitive.getAsString()), depth + 1);
				} catch (JsonSyntaxException ex) {
					return null;
				}
			}
		}
		return null;
	}

	private static String validatePlan(JsonObject planJson, BlockPos size, List<String> palette, int maxBlocks) {
		if (!planJson.has("meta") || !planJson.has("ops")) {
			return "Plan missing required fields (meta, ops).";
		}
		if (!planJson.get("meta").isJsonObject()) {
			return "Plan meta must be an object.";
		}
		if (!planJson.get("ops").isJsonArray()) {
			return "Plan ops must be an array.";
		}
		JsonObject meta = planJson.getAsJsonObject("meta");
		JsonArray ops = planJson.getAsJsonArray("ops");
		Integer blockCount = getInt(meta.get("blockCount"));
		if (blockCount == null) {
			return "Plan meta.blockCount must be an integer.";
		}
		if (ops.size() > maxBlocks) {
			return "Plan has too many ops (" + ops.size() + " > " + maxBlocks + ").";
		}
		int width = size.getX();
		int height = size.getY();
		int length = size.getZ();
		for (int i = 0; i < ops.size(); i++) {
			JsonElement rawOp = ops.get(i);
			if (!rawOp.isJsonObject()) {
				return "Op " + i + " is not an object.";
			}
			JsonObject op = rawOp.getAsJsonObject();
			Integer x = getInt(op.get("x"));
			Integer y = getInt(op.get("y"));
			Integer z = getInt(op.get("z"));
			String block = getString(op.get("block"));
			if (x == null || y == null || z == null || block == null) {
				return "Op " + i + " missing x/y/z/block.";
			}
			if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) {
				return "Op " + i + " out of bounds (" + x + "," + y + "," + z + ").";
			}
			if (!palette.contains(block)) {
				return "Op " + i + " uses disallowed block: " + block;
			}
		}
		if (blockCount != ops.size()) {
			return "meta.blockCount does not match ops length.";
		}
		return null;
	}

	private static Integer getInt(JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (!primitive.isNumber()) {
			return null;
		}
		try {
			return primitive.getAsInt();
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String getString(JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (!primitive.isString()) {
			return null;
		}
		return primitive.getAsString();
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

	private static final class PlanResult {
		private final GumloopPayload.Response plan;
		private final String error;

		private PlanResult(GumloopPayload.Response plan, String error) {
			this.plan = plan;
			this.error = error;
		}

		private static PlanResult success(GumloopPayload.Response plan) {
			return new PlanResult(plan, null);
		}

		private static PlanResult error(String error) {
			return new PlanResult(null, error);
		}
	}

	private static Request buildRequest(ServerPlayerEntity player, Selection buildSelection, Selection contextSelection, String prompt) {
		Request request = new Request();
		request.prompt = prompt;
		request.origin = toOrigin(buildSelection.getMin());
		request.size = toSize(effectiveBuildSize(buildSelection));
		request.palette = defaultPalette();
		request.maxBlocks = MAX_BLOCKS;
		// 1.18.2: ServerPlayerEntity#getWorld() returns a ServerWorld on the server.
		request.context = buildContext((ServerWorld) player.getWorld(), contextSelection);
		return request;
	}

	private static Context buildContext(ServerWorld world, Selection contextSelection) {
		if (contextSelection == null || !contextSelection.isComplete()) {
			return null;
		}

		Context context = new Context();
		context.origin = toOrigin(contextSelection.getMin());
		context.size = toSize(contextSelection.getSize());
		context.blocks = captureBlocks(world, contextSelection, defaultPalette());
		if (context.blocks.isEmpty()) {
			return null;
		}
		context.screenshot = null;
		return context;
	}

	private static List<BlockOp> captureBlocks(ServerWorld world, Selection selection, List<String> palette) {
		List<BlockOp> blocks = new ArrayList<>();
		Set<String> paletteSet = new HashSet<>(palette);
		BlockPos min = selection.getMin();
		BlockPos max = selection.getMax();
		if (min == null || max == null) {
			return blocks;
		}

		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int x = min.getX(); x <= max.getX(); x++) {
			for (int y = min.getY(); y <= max.getY(); y++) {
				for (int z = min.getZ(); z <= max.getZ(); z++) {
					if (blocks.size() >= MAX_CONTEXT_BLOCKS) {
						return blocks;
					}
					cursor.set(x, y, z);
					BlockState state = world.getBlockState(cursor);
					if (state.isAir()) {
						continue;
					}
					String blockId = Registry.BLOCK.getId(state.getBlock()).toString();
					if (!paletteSet.contains(blockId)) {
						continue;
					}
					BlockOp op = new BlockOp();
					op.x = x - min.getX();
					op.y = y - min.getY();
					op.z = z - min.getZ();
					op.block = blockId;
					blocks.add(op);
				}
			}
		}
		return blocks;
	}

	private static Origin toOrigin(BlockPos pos) {
		Origin origin = new Origin();
		origin.x = pos.getX();
		origin.y = pos.getY();
		origin.z = pos.getZ();
		return origin;
	}

	private static Size toSize(BlockPos sizePos) {
		Size size = new Size();
		size.w = sizePos.getX();
		size.h = sizePos.getY();
		size.l = sizePos.getZ();
		return size;
	}

	private static List<String> defaultPalette() {
		return List.of(
			"minecraft:oak_log",
			"minecraft:oak_planks",
			"minecraft:cobblestone",
			"minecraft:stone_bricks",
			"minecraft:oak_stairs",
			"minecraft:cobblestone_stairs",
			"minecraft:stone_brick_stairs",
			"minecraft:oak_slab",
			"minecraft:cobblestone_slab",
			"minecraft:stone_brick_slab",
			"minecraft:oak_fence",
			"minecraft:cobblestone_wall",
			"minecraft:oak_door",
			"minecraft:oak_trapdoor",
			"minecraft:torch",
			"minecraft:lantern",
			"minecraft:glass"
		);
	}
}
