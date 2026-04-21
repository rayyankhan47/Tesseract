package com.rayyan.tesseract;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rayyan.tesseract.agent.Orchestrator;
import com.rayyan.tesseract.jobs.BuildJobManager;
import com.rayyan.tesseract.network.SelectionNetworking;
import com.rayyan.tesseract.paste.PlanPasteClient;
import com.rayyan.tesseract.rag.RagStore;
import com.rayyan.tesseract.selection.Selection;
import com.rayyan.tesseract.selection.SelectionManager;
import io.netty.buffer.Unpooled;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class TesseractMod implements ModInitializer {
	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger("tesseract");
	private static final Item BUILD_WAND = Items.WOODEN_AXE;
	private static final Item CONTEXT_WAND = Items.GOLDEN_AXE;
	private static final int MAX_REGION_SIZE = 32; // still used by /tesseract paste
	private static final String DEMO_CABIN_PROMPT = "Small cozy oak cabin with a peaked roof and a stone foundation";
	private static final String DEMO_GATE_PROMPT = "Gothic stone gate entrance with torches and a central arch";

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Tesseract initialized.");
		startEmbeddedHttpServer();

		// §4.2 — warm the RAG vector store asynchronously. First launch embeds
		// ~150 corpus entries via text-embedding-004 and caches the vectors to
		// run/tesseract_cache/embeddings.bin; subsequent launches hit the cache
		// and add only diffed rows. Failure is non-fatal (builds fall through
		// to text-only via BuildState.textOnlyFallback).
		RagStore.initAsync().whenComplete((store, err) -> {
			if (err != null) {
				LOGGER.warn("RagStore init failed: {}", err.getMessage());
			} else if (store != null && store.isReady()) {
				LOGGER.info("RagStore ready — {} corpus entries available for retrieval.", store.size());
			} else if (store != null) {
				LOGGER.warn("RagStore init degraded — {} (corpus size={})",
						store.bootstrapError(), store.size());
			}
		});

		// Clear all active build state on each new world load so stale locks from a
		// previous session (or failed build) don't block the player from starting a new build.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			BuildJobManager.clear();
			Orchestrator.getInstance().reset();
			LOGGER.info("Tesseract: cleared build state for new world.");
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
			dispatcher.register(literal("tesseract")
				.executes(context -> {
					sendMessage(context.getSource(), "Tesseract loaded. Use /tesseract help.");
					return 1;
				})
				.then(literal("help")
					.executes(context -> {
						sendMessage(context.getSource(), "Punch the ground with a wooden axe to set a build anchor, then /tesseract build <prompt>. Golden axe = context region. /tesseract clear to reset anchor.");
						return 1;
					})
				)
				.then(literal("clear")
					.executes(context -> {
						ServerPlayerEntity player = context.getSource().getPlayer();
						SelectionManager.clearBuildSelection(player.getUuid());
						sendSelectionToClient(player, true);
						sendMessage(context.getSource(), "Build selection cleared.");
						return 1;
					})
				)
				.then(literal("context")
					.then(literal("clear")
						.executes(context -> {
							ServerPlayerEntity player = context.getSource().getPlayer();
							SelectionManager.clearContextSelection(player.getUuid());
							sendSelectionToClient(player, false);
							sendMessage(context.getSource(), "Context selection cleared.");
							return 1;
						})
					)
				)
				.then(literal("build")
					.then(argument("prompt", StringArgumentType.greedyString())
						.executes(context -> {
							ServerPlayerEntity player = context.getSource().getPlayer();
							String prompt = StringArgumentType.getString(context, "prompt");
							return startBuild(context.getSource(), player, prompt);
						})
					)
				)
				.then(literal("paste")
					.then(argument("source", StringArgumentType.greedyString())
						.executes(context -> {
							ServerPlayerEntity player = context.getSource().getPlayer();
							String source = StringArgumentType.getString(context, "source");
							return startPaste(context.getSource(), player, source);
						})
					)
				)
				.then(literal("demo")
					.then(literal("cabin")
						.executes(context -> startBuild(context.getSource(), context.getSource().getPlayer(), DEMO_CABIN_PROMPT))
					)
					.then(literal("gate")
						.executes(context -> startBuild(context.getSource(), context.getSource().getPlayer(), DEMO_GATE_PROMPT))
					)
				)
			);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			BuildJobManager.tick();
			com.rayyan.tesseract.agent.Orchestrator.getInstance().tick(server);
		});

		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClient || hand != Hand.MAIN_HAND) return ActionResult.PASS;
			Item held = player.getStackInHand(hand).getItem();
			if (held == BUILD_WAND) {
				setBuildAnchor(player.getUuid(), world, pos);
				return ActionResult.SUCCESS;
			}
			if (held == CONTEXT_WAND) {
				handleCornerClick(player.getUuid(), world, pos, false);
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient || hand != Hand.MAIN_HAND) return ActionResult.PASS;
			Item held = player.getStackInHand(hand).getItem();
			BlockPos pos = hitResult.getBlockPos();
			if (held == BUILD_WAND) {
				setBuildAnchor(player.getUuid(), world, pos);
				return ActionResult.SUCCESS;
			}
			if (held == CONTEXT_WAND) {
				handleCornerClick(player.getUuid(), world, pos, false);
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});
	}

	// -------------------------------------------------------------------------
	// Embedded HTTP server — port 4891 (web dashboard → mod bridge)
	// -------------------------------------------------------------------------

	/**
	 * Starts a minimal HTTP server on port 4891.
	 *
	 * POST /build  — body: {"prompt":"...","imageBase64":"...","imageMimeType":"..."}
	 *              — response: BuildPlan JSON for plan_server.py to store
	 *
	 * Port 4890 is already used by tools/plan_server.py and must not be disturbed.
	 */
	private static void startEmbeddedHttpServer() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(4891), 0);
			server.createContext("/build", exchange -> {
				if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
					sendHttpResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
					return;
				}
				try {
					byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
					JsonObject req = JsonParser.parseString(new String(bodyBytes, StandardCharsets.UTF_8))
							.getAsJsonObject();

					String prompt = req.has("prompt") ? req.get("prompt").getAsString() : null;
					if (prompt == null || prompt.isBlank()) {
						sendHttpResponse(exchange, 400, "{\"error\":\"Missing prompt\"}");
						return;
					}

					byte[] imageBytes = null;
					String imageMimeType = null;
					if (req.has("imageBase64") && !req.get("imageBase64").isJsonNull()) {
						imageBytes = Base64.getDecoder().decode(req.get("imageBase64").getAsString());
						imageMimeType = req.has("imageMimeType")
								? req.get("imageMimeType").getAsString()
								: "image/png";
					}

					String result = Orchestrator.getInstance().runWebBuild(prompt, imageBytes, imageMimeType);
					sendHttpResponse(exchange, 200, result);

				} catch (IllegalStateException e) {
					LOGGER.warn("Web build rejected: {}", e.getMessage());
					sendHttpResponse(exchange, 503, "{\"error\":\"" + e.getMessage() + "\"}");
				} catch (Exception e) {
					LOGGER.error("Web build failed", e);
					sendHttpResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
				}
			});
			server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
			server.start();
			LOGGER.info("Tesseract HTTP server listening on port 4891.");
		} catch (IOException e) {
			LOGGER.error("Failed to start embedded HTTP server on port 4891", e);
		}
	}

	private static void sendHttpResponse(HttpExchange exchange, int status, String body) {
		try {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.getResponseBody().close();
		} catch (IOException e) {
			LOGGER.error("Failed to send HTTP response", e);
		}
	}

	private static void sendMessage(ServerCommandSource source, String message) {
		source.sendFeedback(Text.of(message), false);
	}

	private static int startBuild(ServerCommandSource source, ServerPlayerEntity player, String prompt) {
		if (player == null) {
			sendMessage(source, "Error: player not found.");
			return 0;
		}
		if (BuildJobManager.isInProgress(player.getUuid())) {
			sendMessage(source, "Build already in progress. Please wait.");
			return 0;
		}
		Selection selection = SelectionManager.getBuildSelection(player.getUuid());
		if (selection == null || selection.getCornerA() == null) {
			sendMessage(source, "Error: no build anchor set. Punch the ground with a wooden axe first.");
			return 0;
		}
		BlockPos anchor = selection.getCornerA();

		BuildJobManager.start(player.getUuid());
		sendMessage(source, "Tesseract drafting: \"" + prompt + "\"");
		sendMessage(source, "Anchor: " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ() + " — size will be determined by the model.");

		Selection contextSelection = SelectionManager.getContextSelection(player.getUuid());
		if (contextSelection != null && contextSelection.isComplete()) {
			sendMessage(source, "Context attached (cyan selection).");
		}
		try {
			Orchestrator.getInstance().run(player, selection, contextSelection, prompt, null, null);
		} catch (Exception e) {
			LOGGER.error("Orchestrator.run() threw unexpectedly", e);
			sendMessage(source, "Tesseract error: " + e.getMessage());
			BuildJobManager.finish(player.getUuid());
			return 0;
		}
		return 1;
	}

	private static int startPaste(ServerCommandSource source, ServerPlayerEntity player, String sourceUrl) {
		if (player == null) {
			sendMessage(source, "Error: player not found.");
			return 0;
		}
		if (BuildJobManager.isInProgress(player.getUuid())) {
			sendMessage(source, "Build already in progress. Please wait.");
			return 0;
		}
		Selection selection = SelectionManager.getBuildSelection(player.getUuid());
		if (selection == null || !selection.isComplete()) {
			sendMessage(source, "Error: you haven't selected a region yet. Select two corners first.");
			return 0;
		}
		BlockPos size = selection.getSize();
		if (size == null) {
			sendMessage(source, "Error: invalid selection.");
			return 0;
		}
		if (size.getX() > MAX_REGION_SIZE || size.getY() > MAX_REGION_SIZE || size.getZ() > MAX_REGION_SIZE) {
			sendMessage(source, "Error: selected region is too large (max 32x32x32).");
			return 0;
		}
		BuildJobManager.start(player.getUuid());
		sendMessage(source, "Tesseract paste: " + sourceUrl);
		PlanPasteClient.fetchAndBuild(player, selection, sourceUrl);
		return 1;
	}

	/**
	 * Sets the build anchor to the punched block and spawns a ring of particles
	 * so the player can see where their build will be centered.
	 */
	private static void setBuildAnchor(UUID playerId, World world, BlockPos anchor) {
		Selection selection = SelectionManager.getBuildSelection(playerId);
		// Store as cornerA == cornerB so isComplete() returns true but getSize() == (1,1,1).
		// The Orchestrator will replace this with the real selection after interpretation.
		selection.setCornerA(anchor);
		selection.setCornerB(anchor);

		ServerPlayerEntity player = getServerPlayer(world, playerId);
		if (player != null) {
			sendMessage(player.getCommandSource(),
				"Build anchor set at " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ()
				+ " — run /tesseract build <prompt>");
			sendSelectionToClient(player, true);

			// Ring of END_ROD particles as visual confirmation
			ServerWorld sw = (ServerWorld) world;
			for (int deg = 0; deg < 360; deg += 18) {
				double rad = Math.toRadians(deg);
				double px = anchor.getX() + 0.5 + 3.5 * Math.cos(rad);
				double pz = anchor.getZ() + 0.5 + 3.5 * Math.sin(rad);
				sw.spawnParticles(ParticleTypes.END_ROD, px, anchor.getY() + 0.1, pz, 1, 0, 0.08, 0, 0.01);
			}
		}
	}

	/** Two-corner selection used only by the context (golden axe) wand. */
	private static void handleCornerClick(UUID playerId, World world, BlockPos pos, boolean isBuild) {
		Selection selection = isBuild
			? SelectionManager.getBuildSelection(playerId)
			: SelectionManager.getContextSelection(playerId);

		boolean isCornerA;
		if (selection.getCornerA() == null) {
			isCornerA = true;
			selection.setCornerA(pos);
			selection.clearCornerB();
		} else if (selection.getCornerB() == null) {
			isCornerA = false;
			selection.setCornerB(pos);
		} else {
			isCornerA = true;
			selection.setCornerA(pos);
			selection.clearCornerB();
		}
		ServerPlayerEntity player = getServerPlayer(world, playerId);
		if (player != null) {
			String which = isCornerA ? "Corner 1" : "Corner 2";
			sendMessage(player.getCommandSource(),
				"Context " + which + " set: " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
			sendSelectionToClient(player, isBuild);
		}
	}

	private static ServerPlayerEntity getServerPlayer(World world, UUID playerId) {
		if (world.getServer() == null) {
			return null;
		}
		return world.getServer().getPlayerManager().getPlayer(playerId);
	}

	private static void sendSelectionToClient(ServerPlayerEntity player, boolean isBuild) {
		Selection selection = isBuild
			? SelectionManager.getBuildSelection(player.getUuid())
			: SelectionManager.getContextSelection(player.getUuid());

		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		SelectionNetworking.writeSelection(buf, isBuild, selection);
		ServerPlayNetworking.send(player, SelectionNetworking.SELECTION_UPDATE, buf);
	}
}
