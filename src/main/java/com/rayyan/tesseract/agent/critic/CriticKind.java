package com.rayyan.tesseract.agent.critic;

/**
 * Identifies one of the five parallel critic seats (§8.1).
 */
public enum CriticKind {
    SILHOUETTE,
    STYLE,
    PROPORTION,
    DETAIL,
    REFERENCE_MATCH
}
