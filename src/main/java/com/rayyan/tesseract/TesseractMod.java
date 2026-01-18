package com.rayyan.tesseract;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rayyan.tesseract.network.SelectionNetworking;
import com.rayyan.tesseract.selection.Selection;
import com.rayyan.tesseract.selection.SelectionManager;
import io.netty.buffer.Unpooled;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

public class TesseractMod implements ModInitializer {
	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger("tesseract");
	private static final Item BUILD_WAND = Items.WOODEN_AXE;
	private static final Item CONTEXT_WAND = Items.GOLDEN_AXE;

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Tesseract initialized.");

		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
			dispatcher.register(literal("tesseract")
				.executes(context -> {
					sendMessage(context.getSource(), "Tesseract loaded. Use /tesseract help.");
					return 1;
				})
				.then(literal("help")
					.executes(context -> {
						sendMessage(context.getSource(), "Commands: /tesseract build <prompt>, /tesseract clear, /tesseract context clear");
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
			);
		});

		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (hand != Hand.MAIN_HAND) {
				return ActionResult.PASS;
			}
			Item held = player.getStackInHand(hand).getItem();
			if (held == BUILD_WAND) {
				handleCornerClick(player.getUuid(), world, pos, true);
				return ActionResult.SUCCESS;
			}
			if (held == CONTEXT_WAND) {
				handleCornerClick(player.getUuid(), world, pos, false);
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (hand != Hand.MAIN_HAND) {
				return ActionResult.PASS;
			}
			Item held = player.getStackInHand(hand).getItem();
			BlockPos pos = hitResult.getBlockPos();
			if (held == BUILD_WAND) {
				handleCornerClick(player.getUuid(), world, pos, true);
				return ActionResult.SUCCESS;
			}
			if (held == CONTEXT_WAND) {
				handleCornerClick(player.getUuid(), world, pos, false);
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});
	}

	private static void sendMessage(ServerCommandSource source, String message) {
		source.sendFeedback(Text.of(message), false);
	}

	private static void handleCornerClick(UUID playerId, World world, BlockPos pos, boolean isBuild) {
		Selection selection = isBuild
			? SelectionManager.getBuildSelection(playerId)
			: SelectionManager.getContextSelection(playerId);

		boolean isCornerA;
		if (selection.getCornerA() == null) {
			// First click: set Corner 1
			isCornerA = true;
			selection.setCornerA(pos);
			selection.clearCornerB();
		} else if (selection.getCornerB() == null) {
			// Second click: set Corner 2
			isCornerA = false;
			selection.setCornerB(pos);
		} else {
			// Both corners set: reset and start new selection (Corner 1)
			isCornerA = true;
			selection.setCornerA(pos);
			selection.clearCornerB();
		}
		if (!world.isClient) {
			ServerPlayerEntity player = getServerPlayer(world, playerId);
			if (player != null) {
				sendMessage(player.getCommandSource(), formatCornerMessage(isBuild, isCornerA, pos));
				sendSelectionToClient(player, isBuild);
			}
		}
	}

	private static String formatCornerMessage(boolean isBuild, boolean isCornerA, BlockPos pos) {
		String which = isCornerA ? "Corner 1" : "Corner 2";
		String type = isBuild ? "Build" : "Context";
		return type + " " + which + " set: " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
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
