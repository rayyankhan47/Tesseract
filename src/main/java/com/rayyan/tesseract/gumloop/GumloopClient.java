package com.rayyan.tesseract.gumloop;

import com.google.gson.Gson;
import com.rayyan.tesseract.gumloop.GumloopPayload.BlockOp;
import com.rayyan.tesseract.gumloop.GumloopPayload.Context;
import com.rayyan.tesseract.gumloop.GumloopPayload.Origin;
import com.rayyan.tesseract.gumloop.GumloopPayload.Request;
import com.rayyan.tesseract.gumloop.GumloopPayload.Size;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class GumloopClient {
	private static final int MAX_CONTEXT_BLOCKS = 500;
	private static final int MAX_BLOCKS = 8000;
	private static final Gson GSON = new Gson();
	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	private GumloopClient() {}

	public static void sendBuildRequest(ServerPlayerEntity player, Selection buildSelection, Selection contextSelection, String prompt) {
		String webhook = System.getenv("GUMLOOP_WEBHOOK_URL");
		if (webhook == null || webhook.isBlank()) {
			player.sendMessage(Text.of("Error: GUMLOOP_WEBHOOK_URL is not set."), false);
			return;
		}

		Request request = buildRequest(player, buildSelection, contextSelection, prompt);
		String json = GSON.toJson(request);

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
					if (error != null) {
						player.sendMessage(Text.of("Error: Gumloop request failed: " + error.getMessage()), false);
						return;
					}
					int status = response.statusCode();
					if (status < 200 || status >= 300) {
						player.sendMessage(Text.of("Error: Gumloop returned status " + status), false);
						return;
					}
					player.sendMessage(Text.of("Gumloop response received (" + response.body().length() + " chars)."), false);
				});
			});
	}

	private static Request buildRequest(ServerPlayerEntity player, Selection buildSelection, Selection contextSelection, String prompt) {
		Request request = new Request();
		request.prompt = prompt;
		request.origin = toOrigin(buildSelection.getMin());
		request.size = toSize(buildSelection.getSize());
		request.palette = defaultPalette();
		request.maxBlocks = MAX_BLOCKS;
		request.context = buildContext(player.getServerWorld(), contextSelection);
		return request;
	}

	private static Context buildContext(ServerWorld world, Selection contextSelection) {
		if (contextSelection == null || !contextSelection.isComplete()) {
			return null;
		}

		Context context = new Context();
		context.origin = toOrigin(contextSelection.getMin());
		context.size = toSize(contextSelection.getSize());
		context.blocks = captureBlocks(world, contextSelection);
		context.screenshot = null;
		return context;
	}

	private static List<BlockOp> captureBlocks(ServerWorld world, Selection selection) {
		List<BlockOp> blocks = new ArrayList<>();
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
					BlockOp op = new BlockOp();
					op.x = x - min.getX();
					op.y = y - min.getY();
					op.z = z - min.getZ();
					op.block = Registry.BLOCK.getId(state.getBlock()).toString();
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
