package com.rayyan.tesseract;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.literal;

public class TesseractMod implements ModInitializer {
	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger("tesseract");

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Tesseract initialized.");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
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
			);
		});
	}

	private static void sendMessage(ServerCommandSource source, String message) {
		source.sendFeedback(Text.literal(message), false);
	}
}
