package com.rayyan.tesseract.agent;

/**
 * Immutable record of a single agent event.
 *
 * Appended to BuildState.eventLog and forwarded to the player's chat in real time.
 * The CopyOnWriteArrayList in BuildState makes it safe to read this list from any thread.
 */
public record BuildEvent(long timestamp, String agentName, String message) {

    /** Convenience factory — captures current time automatically. */
    public static BuildEvent of(String agentName, String message) {
        return new BuildEvent(System.currentTimeMillis(), agentName, message);
    }

    @Override
    public String toString() {
        return "[" + agentName + "] " + message;
    }
}
