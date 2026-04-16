package com.rayyan.tesseract.agent;

import java.util.List;

/**
 * Output model for PlanningAgent — one entry per named structural component.
 *
 * Mirrors the PlanningAgent output schema:
 * {
 *   "id": "comp_1",
 *   "name": "foundation",
 *   "description": "flat stone base, full footprint, 1 block tall",
 *   "build_after": []
 * }
 *
 * originX/Y/Z and sizeX/Y/Z are not present in the LLM response — they are
 * computed by PlanningAgent after parsing, using a simple spatial layout pass
 * based on the bounding box from BuildState.buildSelection.
 */
public final class ComponentPlan {
    String id;
    String name;
    String description;
    List<String> buildAfter;

    /** Offset from BuildState.placementOrigin in world coordinates. Set by PlanningAgent. */
    int originX;
    int originY;
    int originZ;

    /** Bounding box for this component. Set by PlanningAgent. */
    int sizeX;
    int sizeY;
    int sizeZ;

    /** Retry count — incremented by GenerationAgent on CriticAgent failure. */
    int retryCount;
}
