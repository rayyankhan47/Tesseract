package com.rayyan.tesseract.jobs;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildJobManager {
	private static final int DEFAULT_LOCK_TICKS = 40;
	private static final Map<UUID, Integer> ACTIVE_JOBS = new ConcurrentHashMap<>();

	private BuildJobManager() {}

	public static boolean isInProgress(UUID playerId) {
		return ACTIVE_JOBS.containsKey(playerId);
	}

	public static void start(UUID playerId) {
		ACTIVE_JOBS.put(playerId, DEFAULT_LOCK_TICKS);
	}

	public static void tick() {
		for (Map.Entry<UUID, Integer> entry : ACTIVE_JOBS.entrySet()) {
			int remaining = entry.getValue() - 1;
			if (remaining <= 0) {
				ACTIVE_JOBS.remove(entry.getKey());
			} else {
				ACTIVE_JOBS.put(entry.getKey(), remaining);
			}
		}
	}
}
