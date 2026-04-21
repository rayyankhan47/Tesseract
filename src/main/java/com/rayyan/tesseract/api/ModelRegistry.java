package com.rayyan.tesseract.api;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical {@link TaskKind} → {@link ModelSpec} mapping for the v3 pipeline.
 *
 * <p>Per REFACTOR_3 §3.1 — every LLM call site resolves its model, sampling,
 * token cap, timeout, and escalation/downshift targets through this registry.
 * Agents never hard-code model IDs.
 *
 * <p>Tiering:
 * <ul>
 *   <li>Heavy multimodal (Pro): {@code MASS_EXTRACTION}, {@code L1_ARCHITECT},
 *       {@code CRITIC_RECONCILE}, {@code CONCEPT_SELECT}.</li>
 *   <li>Workhorse (Flash): {@code L2_DECOMPOSE}, {@code L3_ELEMENT},
 *       {@code L4_REPL}, {@code CRITIC_INNER}, {@code RAG_PLAN},
 *       {@code IMAGE_CAPTION}, {@code TOOL_PROMOTE}.</li>
 *   <li>Cheap bulk (Flash-Lite): {@code MATERIAL_PICK}.</li>
 * </ul>
 *
 * <p>Pricing is USD / million tokens and tracks the public Gemini pricing
 * page as of 2026-04; callers should treat these as best-effort estimates,
 * not billing truth.
 */
public final class ModelRegistry {

    public static final String MODEL_PRO  = "gemini-2.5-pro";
    public static final String MODEL_FLASH = "gemini-2.5-flash";
    public static final String MODEL_LITE  = "gemini-2.5-flash-lite";

    // USD / million tokens — updated from pricing reference.
    private static final double PRO_IN   = 1.25,  PRO_OUT   = 5.00;
    private static final double FLASH_IN = 0.30,  FLASH_OUT = 2.50;
    private static final double LITE_IN  = 0.10,  LITE_OUT  = 0.40;

    private static final Map<TaskKind, ModelSpec> SPECS = new EnumMap<>(TaskKind.class);

    static {
        // Baseline entries — populated eagerly at class init so agents never
        // see a missing TaskKind at runtime.

        // ---- Heavy multimodal (Pro) ----
        SPECS.put(TaskKind.MASS_EXTRACTION, new ModelSpec(
                MODEL_PRO, 0.2, 16_384, 90_000L,
                MODEL_FLASH,                       // 429 downshift target
                List.of(MODEL_PRO),                // no escalation — already top tier
                false, PRO_IN, PRO_OUT));

        SPECS.put(TaskKind.CONCEPT_SELECT, new ModelSpec(
                MODEL_PRO, 0.3, 256, 60_000L,
                MODEL_FLASH,
                List.of(MODEL_PRO),
                false, PRO_IN, PRO_OUT));

        SPECS.put(TaskKind.L1_ARCHITECT, new ModelSpec(
                MODEL_PRO, 0.6, 8_192, 90_000L,
                MODEL_FLASH,
                List.of(MODEL_PRO),
                false, PRO_IN, PRO_OUT));

        SPECS.put(TaskKind.CRITIC_RECONCILE, new ModelSpec(
                MODEL_PRO, 0.4, 4_096, 60_000L,
                MODEL_FLASH,
                List.of(MODEL_PRO),
                false, PRO_IN, PRO_OUT));

        // ---- Workhorse (Flash) ----
        SPECS.put(TaskKind.L2_DECOMPOSE, new ModelSpec(
                MODEL_FLASH, 0.5, 4_096, 60_000L,
                MODEL_LITE,
                List.of(MODEL_FLASH, MODEL_PRO),
                false, FLASH_IN, FLASH_OUT));

        SPECS.put(TaskKind.L3_ELEMENT, new ModelSpec(
                MODEL_FLASH, 0.55, 6_144, 60_000L,
                MODEL_LITE,
                List.of(MODEL_FLASH, MODEL_PRO),
                false, FLASH_IN, FLASH_OUT));

        SPECS.put(TaskKind.L4_REPL, new ModelSpec(
                MODEL_FLASH, 0.6, 6_144, 60_000L,
                MODEL_LITE,
                List.of(MODEL_FLASH, MODEL_PRO),
                false, FLASH_IN, FLASH_OUT));

        SPECS.put(TaskKind.CRITIC_INNER, new ModelSpec(
                MODEL_FLASH, 0.3, 2_048, 15_000L,
                MODEL_LITE,
                List.of(MODEL_FLASH),
                true /* optional — §3.3.1 */, FLASH_IN, FLASH_OUT));

        SPECS.put(TaskKind.RAG_PLAN, new ModelSpec(
                MODEL_FLASH, 0.4, 512, 30_000L,
                MODEL_LITE,
                List.of(MODEL_FLASH),
                false, FLASH_IN, FLASH_OUT));

        SPECS.put(TaskKind.IMAGE_CAPTION, new ModelSpec(
                MODEL_FLASH, 0.2, 256, 30_000L,
                MODEL_LITE,
                List.of(MODEL_FLASH),
                false, FLASH_IN, FLASH_OUT));

        SPECS.put(TaskKind.TOOL_PROMOTE, new ModelSpec(
                MODEL_FLASH, 0.3, 1_024, 30_000L,
                MODEL_LITE,
                List.of(MODEL_FLASH),
                true, FLASH_IN, FLASH_OUT));

        // ---- Cheap bulk (Lite) ----
        SPECS.put(TaskKind.MATERIAL_PICK, new ModelSpec(
                MODEL_LITE, 0.2, 512, 20_000L,
                MODEL_LITE,
                List.of(MODEL_LITE, MODEL_FLASH),
                false, LITE_IN, LITE_OUT));

        // ---- Symbolic / non-LLM entries for symmetry ----
        SPECS.put(TaskKind.CONCEPT_SYNTHESIS, new ModelSpec(
                "imagen-3.0-generate-002", null, null, 90_000L,
                null, List.of("imagen-3.0-generate-002"),
                false, 0.0, 0.0));
    }

    private ModelRegistry() {}

    public static ModelSpec get(TaskKind kind) {
        ModelSpec spec = SPECS.get(kind);
        if (spec == null) {
            throw new IllegalStateException("No ModelSpec registered for TaskKind=" + kind);
        }
        return spec;
    }
}
