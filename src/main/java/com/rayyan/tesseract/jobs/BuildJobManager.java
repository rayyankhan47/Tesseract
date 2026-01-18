package com.rayyan.tesseract.jobs;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildJobManager {
	private static final long DEFAULT_LOCK_MILLIS = 5 * 60 * 1000L;
	private static final Map<UUID, Long> ACTIVE_JOBS = new ConcurrentHashMap<>();

	private BuildJobManager() {}

	public static boolean isInProgress(UUID playerId) {
		Long expiresAt = ACTIVE_JOBS.get(playerId);
		if (expiresAt == null) {
			return false;
		}
		if (System.currentTimeMillis() > expiresAt) {
			ACTIVE_JOBS.remove(playerId);
			return false;
		}
		return true;
	}

	public static void start(UUID playerId) {
		ACTIVE_JOBS.put(playerId, System.currentTimeMillis() + DEFAULT_LOCK_MILLIS);
	}

	public static void finish(UUID playerId) {
		ACTIVE_JOBS.remove(playerId);
	}

	public static void tick() {
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, Long> entry : ACTIVE_JOBS.entrySet()) {
			if (now > entry.getValue()) {
				ACTIVE_JOBS.remove(entry.getKey());
			}
		}
	}
}
