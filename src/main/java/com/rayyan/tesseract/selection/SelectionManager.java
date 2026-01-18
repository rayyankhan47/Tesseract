package com.rayyan.tesseract.selection;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SelectionManager {
	private static final Map<UUID, Selection> BUILD_SELECTIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, Selection> CONTEXT_SELECTIONS = new ConcurrentHashMap<>();

	private SelectionManager() {}

	public static Selection getBuildSelection(UUID playerId) {
		return BUILD_SELECTIONS.computeIfAbsent(playerId, id -> new Selection());
	}

	public static Selection getContextSelection(UUID playerId) {
		return CONTEXT_SELECTIONS.computeIfAbsent(playerId, id -> new Selection());
	}

	public static void clearBuildSelection(UUID playerId) {
		BUILD_SELECTIONS.remove(playerId);
	}

	public static void clearContextSelection(UUID playerId) {
		CONTEXT_SELECTIONS.remove(playerId);
	}
}
