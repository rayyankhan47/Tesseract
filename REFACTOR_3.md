# REFACTOR_3 — Vision-First Iterative Neural Architect

Tesseract v3. Rebuilds the pipeline on three premises:
1. The LLM never plans blind — it always has a concept image and a 3D mass sketch in front of it.
2. The geometric toolbox is self-extending — the LLM invents new primitives per build.
3. Every stage is critiqued against the reference image, not against an imagined ideal.

Target budget: $0.50–$1.50 per build. ~40–80 LLM calls per run. Instant placement (no tick-drip).

## Model tiering (global)

| Tier | Model | Used for |
| --- | --- | --- |
| Image gen | `imagen-3.0-generate-002` | Concept synthesis (Phase 0) |
| Heavy multimodal | `gemini-2.5-pro` | 3D mass extraction, L1 Architect, reconciler, hard critic calls |
| Workhorse | `gemini-2.5-flash` | L2/L3/L4 agents, REPL rounds, inner-loop critics |
| Cheap bulk | `gemini-2.5-flash-lite` | Material picks, simple JSON parses |
| Embeddings | `text-embedding-004` | RAG retrieval |

Fallback strategy: failure-class-specific (see Step 3), not blanket model downgrade.

---

## Step 1 — Concept Synthesis & Visual Grounding (Phase 0)

Every build starts with 4 concept images from Imagen. These become the visual north star for every subsequent agent.

### 1.1 Imagen integration

- **1.1.1** Add `ImagenClient` alongside `GeminiClient`. Single-key (reuse `GEMINI_API_KEY`). POST to `models/imagen-3.0-generate-002:predict`. Return `byte[]` PNG.
- **1.1.2** Concept prompt template. Take user prompt → expand to four stylistic variations ("minimalist", "ornate", "weathered ancient", "clean new"). Negative prompt strips cartoons, signs, text overlays.
- **1.1.3** Image cache. Key by prompt hash. Stored in `run/tesseract_cache/concepts/`. Hit the cache on identical prompts to save API spend during iteration.

### 1.2 ConceptAgent

- **1.2.1** Generate 4 concepts in parallel (one `CompletableFuture` per variation).
- **1.2.2** Auto-select best via Gemini 2.5 Pro multimodal: "which of these 4 most faithfully matches the prompt `<prompt>`? Return index 0–3." Fallback: pick index 0 if refusal.
- **1.2.3** Store all 4 + selected index in `BuildState.referenceImages`. Downstream agents see the selected one as primary, others as style siblings.

### 1.3 Reference infrastructure

- **1.3.1** `ReferenceImage` data class (bytes + prompt + source model). `BuildState.referenceImages: List<ReferenceImage>`.
- **1.3.2** Every agent that calls Gemini Vision takes a `ReferenceImage` in its context block. Critics compare current render to this reference every iteration.
- **1.3.3** Debug dump: when `tesseract.debug.renders=true`, concepts land in `run/tesseract_debug/concepts/<buildId>/`.

---

## Step 2 — 3D Mass Extraction (Phase 1)

Turn the concept image into a 16³ voxel "mass sketch". Gemini 2.5 Pro does this via multimodal reasoning, not a dedicated 3D model.

### 2.1 MassExtractionAgent

- **2.1.1** Prompt: "Given this reference image of a building, output a 16×16×16 voxel mass as a flat JSON array of `[x,y,z]` filled coordinates. Fill only the silhouette mass, not interior detail." Temperature 0.2. Max output 16k tokens.
- **2.1.2** Parse into `VoxelMass { int[][][] filled; int resolution=16 }`. Validate: must have ≥20 and ≤2048 filled voxels. Reject + retry if out of bounds.
- **2.1.3** Optional second pass: "critique this voxel mass against the reference image. Return a list of add/remove voxels to better match the silhouette." Single refinement round.

### 2.2 Mass → Blueprint bounds

- **2.2.1** Derive `Blueprint.Bounds` by scaling the voxel mass to the user's selected region. If no selection, default to `24×24×24` blocks.
- **2.2.2** Persist voxel mass in `BuildState.massSketch`. L1/L2 agents see it as an ASCII layer-by-layer diagram.
- **2.2.3** Render a debug PNG of the voxel mass for visual inspection (isometric projection reusing `IsoRenderer`).

### 2.3 Silhouette critic bootstrap

- **2.3.1** Every critic call in later phases receives the voxel mass + concept image as co-referenced truth.
- **2.3.2** A "silhouette drift" metric is computed after every compile pass: fraction of built voxels that land outside the mass sketch envelope. Soft target: <15%.
- **2.3.3** Drift > 40% triggers an L1 rollback to re-plan rather than continued patching.

---

## Step 3 — Model Tiering & Fallback Redesign

Replace the current "try X, fall back to Y" pattern with task-bound model selection and failure-class-specific retry chains.

### 3.1 Model registry

- **3.1.1** `ModelRegistry` static table mapping `TaskKind` enum to `{modelId, temperature, maxOutputTokens, timeoutMs}`. TaskKinds: `CONCEPT_SYNTHESIS`, `MASS_EXTRACTION`, `RAG_PLAN`, `L1_ARCHITECT`, `L2_DECOMPOSE`, `L3_ELEMENT`, `L4_REPL`, `CRITIC_INNER`, `CRITIC_RECONCILE`, `MATERIAL_PICK`.
- **3.1.2** All agents call `GeminiClient.call(TaskKind kind, prompt, ...)` — no more hard-coded model IDs inside agents.
- **3.1.3** `CostTracker` increments per-call with priced tokens. Surfaces total $/build in chat at COMPLETE.

### 3.2 Failure-class retry chains

- **3.2.1** **Transient (HTTP 5xx, socket timeout):** same model, exponential backoff 0.5s → 2s → 8s. Max 3 attempts.
- **3.2.2** **Rate limit (429) or quota:** one-shot downshift (Pro → Flash, Flash → Lite) for this call only. Next unrelated call uses the original tier.
- **3.2.3** **Parse failure / refusal / empty:** escalate upward (Lite → Flash → Pro) with a stricter reminder appended ("Return ONLY valid JSON matching schema X, no prose"). Max 2 escalations.

### 3.3 Graceful degradation

- **3.3.1** Every inner-loop critic call is tagged *optional*. If after retries it still fails, the orchestrator logs `CRITIC_SKIPPED` and proceeds with remaining critics.
- **3.3.2** If L4 REPL exhausts its budget on an element, commit the last valid op-set even if critic was unsatisfied. Log `ELEMENT_BUDGET_EXCEEDED`.
- **3.3.3** If an entire phase fails (e.g. Imagen refuses the prompt), orchestrator falls through to a text-only fallback path: skip Phase 0/1, go straight to v2-style blueprinting. This is the safety valve.

---

## Step 4 — RAG Architectural Knowledge Base

Ground planning in real architectural knowledge rather than the LLM's generic priors.

### 4.1 Knowledge corpus

- **4.1.1** Seed with ~150 curated entries in `resources/architecture_corpus.jsonl`. Fields: `id, name, style, period, region, description, canonical_dimensions, defining_features[]`.
- **4.1.2** Categories: styles (gothic, romanesque, brutalist, art deco, parametric, vernacular...), elements (buttress, cornice, pediment, oculus...), exemplar buildings (Notre Dame, Hagia Sophia, Sagrada Familia, Fallingwater...).
- **4.1.3** Numeric facts preserved as JSON ("nave_height_m": 33, "aisle_count": 2). Planners can reason over them.

### 4.2 Embedding + retrieval

- **4.2.1** On mod startup, embed all corpus entries via `text-embedding-004`. Cache embeddings to `run/tesseract_cache/embeddings.bin`.
- **4.2.2** In-memory vector store (array of float[]) with cosine similarity top-k (k=6).
- **4.2.3** Query built from user prompt + concept image caption (Gemini Vision one-liner describing the image).

### 4.3 Injection into planning

- **4.3.1** L1 Architect prompt: "Reference knowledge (retrieved from corpus): `<top-6 entries>`. Use this to ground proportions and features."
- **4.3.2** `BuildState.ragContext: List<CorpusEntry>` so L2/L3 can see the same knowledge.
- **4.3.3** Citation tracking: agents can emit `"citing": ["notre_dame_facade"]` in their output; logged in the timeline.

---

## Step 5 — Hierarchical Decomposition (L1 / L2 / L3)

Three agents that progressively refine architectural intent at shrinking scales.

### 5.1 L1 ArchitectAgent

- **5.1.1** Input: prompt, concept image, voxel mass, RAG context. Output: `MassPlan { List<MajorMass> }`. Each `MajorMass` has a label, bounding box (blueprint-local), role ("central_tower", "east_wing").
- **5.1.2** Uses `gemini-2.5-pro` (heavy reasoning).
- **5.1.3** Silhouette critic run once: does the `MassPlan` envelope match the voxel mass within tolerance? If not, one retry with critique.

### 5.2 L2 DecomposerAgent

- **5.2.1** For each `MajorMass` → `ZoneSpec[]`: foundation, body, crown, rhythm (e.g. window courses at y=6,10,14). Uses `gemini-2.5-flash`.
- **5.2.2** Output: `List<StructuralZone>` per mass, with Y-ranges, feature hints, material families.
- **5.2.3** Structural critic: "does this zoning make architectural sense for the stated style?" Inner-loop, optional.

### 5.3 L3 ElementDesignerAgent

- **5.3.1** For each `StructuralZone` → `List<ElementSpec>`. An ElementSpec is a natural-language description + parameters ("pointed arch, apex at y=14, width 6, material: stone_brick with chiseled_stone_brick keystones").
- **5.3.2** Style critic per zone: "do these elements read as the requested style?"
- **5.3.3** Elements are ordered by structural dependency (base before walls before roof). This order drives L4 scheduling.

---

## Step 6 — Geometric Toolbox & Self-Extending Sandbox

This is where bound-blindness ends. L4 writes scripts; sandbox runs them; new primitives promote to the library over time.

### 6.1 Core toolbox (15 built-ins, Java implementations)

- **6.1.1** **Fills:** `box`, `cylinder`, `pyramid`, `sphere`. **Outlines:** `walls`, `frame`, `line`. **Curves:** `arc`. See full signatures in `docs/toolbox.md` (to be authored in 6.1.3).
- **6.1.2** **Composition:** `repeat`, `mirror`, `subtract`, `intersect`. **Decoration:** `crenellate`, `scatter`. Every function returns a `Set<BlockOp>`.
- **6.1.3** Each function has unit tests (`ToolboxTest`) and a worked example in `docs/toolbox.md`. The doc is fed into every L4 prompt as the tool reference.

### 6.2 Restricted Python sandbox

- **6.2.1** Embed a minimal tree-walking interpreter (custom, 300–500 LOC) that supports: literals, arithmetic, comparisons, `if`/`for`/`while` with bounded iteration count, function calls, list/dict/tuple, variable assignment. **No imports, no attribute access on non-whitelisted objects, no file/network I/O.**
- **6.2.2** Resource ceilings: 50k AST-steps per run, 10MB memory, 5s wall clock. Exceeding any is a `SandboxExceededError` handled by L4 as "script too expensive, simplify".
- **6.2.3** Toolbox functions are injected as Java-backed `NativeFn` objects. User-defined `def` functions work within the sandbox only.

### 6.3 Self-extending toolbox

- **6.3.1** L4 can write `def` functions inside its script. Those live only for this build.
- **6.3.2** After a successful build, a `ToolPromoter` agent reviews the build's `def` functions: "did any of these produce visually compelling, reusable geometry? Rate 0–1 and suggest promotion." Threshold 0.8 → added to `resources/toolbox_extensions.jsonl` with provenance (build ID, prompt).
- **6.3.3** Promoted functions are merged into the L4 prompt's tool reference on the next build, tagged as "community-contributed" with usage examples.

---

## Step 7 — L4 Geometric REPL Agent

The load-bearing agent. Composes tools into block ops per element, with vision feedback every turn.

### 7.1 REPL loop

- **7.1.1** Per element: agent sees (concept image, element spec, mass sketch, toolbox reference, any locked elements already built). Writes a script. Sandbox runs it. Rendered via `IsoRenderer`. Critic evaluates against element spec + reference.
- **7.1.2** Agent receives critique, rewrites or adjusts, re-runs. Up to **10 turns per element**. Agent can declare `"done"` early; critic has veto.
- **7.1.3** On `"done"` + critic approval, element is **locked**: its ops are merged into the cumulative build and never revisited.

### 7.2 Element scheduling

- **7.2.1** Elements processed in dependency order (foundation → walls → roof → spires → details). Elements without spatial dependencies can run in parallel in later optimizations.
- **7.2.2** Budget: max 10 turns or $0.15 per element, whichever first. Overrun → commit the last valid op-set with a `BUDGET_EXCEEDED` marker.
- **7.2.3** On sandbox error, L4 sees the error and retries. 3 consecutive sandbox errors → fallback to a plain `box` matching the element's bounding box.

### 7.3 Cumulative compilation

- **7.3.1** `CompiledBlueprint` now builds incrementally, one locked element at a time.
- **7.3.2** After each element lock, re-render the *full* cumulative build. The next element's critic sees the growing scene, so it can assess context fit.
- **7.3.3** Overlap detection: if a new element writes to voxels already owned by a locked element, log `OVERLAP` and keep the locked owner's block (earlier-committed wins).

---

## Step 8 — Parallel Critic Swarm

Five specialized critics run in parallel after each L4 element turn. A reconciler merges their patches.

### 8.1 Critic specialization

- **8.1.1** **SilhouetteCritic:** compares render to voxel mass envelope. Returns fraction-outside-mass and suggested trimming.
- **8.1.2** **StyleCritic:** "does this read as `<style>`?" Returns 0–1 style score + textual notes. Uses `gemini-2.5-flash`.
- **8.1.3** **ProportionCritic:** checks human-scale ratios (window height vs. wall height, door width vs. wall width, golden-ratio hints for classical styles).
- **8.1.4** **DetailCritic:** surface richness — is this a flat mass with no articulation, or does it have variation? Returns density suggestion.
- **8.1.5** **ReferenceMatchCritic:** directly compares current render to concept image via multimodal Gemini. "What's in the concept image that's not in the build?"

### 8.2 Parallel dispatch

- **8.2.1** All 5 critics fire on the same rendered image via `CompletableFuture.allOf`. Timeout 8s per critic. Any that time out are skipped with `CRITIC_SKIPPED`.
- **8.2.2** Each critic returns a typed `CriticOpinion { score, summary, suggestedPatches[] }` so downstream merge is structured.
- **8.2.3** Critic outputs logged to the timeline per element for post-build replay/debug.

### 8.3 Reconciler

- **8.3.1** `ReconcilerAgent` (Gemini 2.5 Pro) receives all critic opinions + the element spec + the render. Outputs a single consolidated patch list. Resolves conflicts ("StyleCritic says add ornament, SilhouetteCritic says trim — reconcile: keep the trim, add ornament on remaining surfaces").
- **8.3.2** Reconciled patches fed back to L4 as its next-turn critique.
- **8.3.3** If mean critic score ≥ 0.85 and no hard silhouette violation, element locks immediately (skip further REPL turns).

---

## Step 9 — Fractal Texture Pass (Phase 6, non-negotiable)

After L4 geometry locks, a pure-code pass adds weathering, patterns, and organic detail. No LLM. This is where builds stop looking like tech demos and start looking alive.

### 9.1 Perlin noise weathering

- **9.1.1** `WeatheringPass` class. Sample 3D Perlin noise over each block. Above threshold → substitute weathered variant (`stone_brick` → `cracked_stone_brick` → `mossy_stone_brick`). Threshold and substitution palette tagged per-material in `resources/weathering_palette.json`.
- **9.1.2** "Age" parameter 0.0–1.0 set by L1 Architect ("this should read as ancient" → 0.9). Controls noise density and palette depth.
- **9.1.3** Region-aware: low-altitude areas moss more, high-altitude weathers less, interior surfaces left clean.

### 9.2 Wave Function Collapse for patterned regions

- **9.2.1** `WFCPass` with tile rulesets in `resources/wfc_tilesets/` (mosaic floor, stained glass, parquet, brick patterns). L3 Element Designer can tag a region with a tileset.
- **9.2.2** Run WFC on each tagged region. 2D collapse for surfaces (floors, walls, ceilings). Produces block-level pattern data consistent with adjacent tiles.
- **9.2.3** Seed derived from build ID + region ID for determinism across retries.

### 9.3 L-system organic details

- **9.3.1** `LSystemPass` seeds vine/ivy/moss colonies on eligible surfaces (vertical stone faces, outdoor, age > 0.5). Uses simple L-system rules ("F → F[+F][-F]F") with bounded iteration depth (5).
- **9.3.2** Cellular-automaton decay pass: scatter initial damage seeds, run 3–5 CA steps, produce cracked/broken block clusters.
- **9.3.3** Crack generation: Bresenham-style fracture lines on large unbroken surfaces for scale and age.

---

## Step 10 — State Machine & Orchestrator

New states, new legal transitions, new `BuildState` shape.

### 10.1 OrchestratorState

- **10.1.1** New states: `IDLE`, `CONCEPT_SYNTHESIZING`, `MASS_EXTRACTING`, `RAG_QUERYING`, `L1_ARCHITECTING`, `L2_DECOMPOSING`, `L3_DESIGNING`, `L4_ITERATING` (per element), `TEXTURING`, `PLACING`, `COMPLETE`, `FAILED`.
- **10.1.2** Legal transitions table in `OrchestratorState.assertTransition`. `L4_ITERATING` loops on itself per element; `L3_DESIGNING → L4_ITERATING` starts element scheduling; `L4_ITERATING → TEXTURING` triggers when all elements locked.
- **10.1.3** Timeline logging records every state entry + duration. Surfaces at COMPLETE as a latency breakdown.

### 10.2 BuildState expansion

- **10.2.1** New fields: `referenceImages`, `selectedConceptIndex`, `massSketch`, `ragContext`, `massPlan`, `zoneSpecs`, `elementSpecs`, `elementLocks`, `customToolbox`, `costSoFar`.
- **10.2.2** Remove v2 fields: `iterationCount`, `lastRenderPng` (replaced by per-element renders in `elementLocks`).
- **10.2.3** All collections `CopyOnWriteArrayList` or similar for async safety.

### 10.3 Orchestrator rewiring

- **10.3.1** New phase dispatch: linear through phases 0→9, with L4 as the inner per-element loop. No more monolithic "critique → patch → recompile".
- **10.3.2** Global budget guard: if `costSoFar > 2.00` USD, halt and ship whatever is complete. Surface `$X.XX spent, build shipped early` to chat.
- **10.3.3** All async completions still trampoline through `server.execute()` before touching world state. Unchanged.

---

## Step 11 — Instant Placement

No more tick-drip. Whole build appears at once.

### 11.1 Replace BuildQueueManager

- **11.1.1** Delete or deprecate tick-drip logic. Add `SyncPlacer` class.
- **11.1.2** `SyncPlacer.placeAll(ops, world, origin)`: iterates ops, calls `world.setBlockState` for each on the server thread. No queueing.
- **11.1.3** For large builds (>10k ops), chunk placement into batches of 2000 ops separated by a single-tick yield to avoid a multi-second freeze. Still effectively instant visually.

### 11.2 World commit

- **11.2.1** Placement runs synchronously in the `PLACING` state. Orchestrator transitions to `COMPLETE` after the last `setBlockState` returns.
- **11.2.2** Progress feedback: chat message "placed N blocks" at end, not during (since there's no tick-drip to narrate).
- **11.2.3** If a `setBlockState` throws (unknown block id etc.), log and continue. Final chat reports failure count.

---

## Step 12 — Testing, Prompt Tuning, Cost Audit

### 12.1 Smoke tests

- **12.1.1** Three canonical prompts run end-to-end: "gothic cathedral", "brutalist bunker", "art-nouveau pavilion". Must complete in under 4 minutes each, under $1.50 each.
- **12.1.2** Regression: rerun each prompt twice. Visual outputs should be meaningfully varied (not identical) but stylistically consistent.
- **12.1.3** Compare v2 vs v3 outputs side-by-side for subjective build quality sign-off.

### 12.2 Prompt tuning pass

- **12.2.1** Agent-by-agent prompt audit. Each prompt gets: explicit schema, few-shot examples, negative examples, output length cap.
- **12.2.2** Critics calibrated against a held-out set of "good build" reference renders.
- **12.2.3** L4 prompt gets the fullest worked example: a gothic tower script from concept → mass → element spec → script → render.

### 12.3 Cost audit

- **12.3.1** Per-phase spend reported at COMPLETE. Highlight outliers.
- **12.3.2** Set per-phase soft ceilings; breaching emits a warning but doesn't fail.
- **12.3.3** If total build cost > target by >50%, flag for prompt tuning rather than model downgrade.

---

## Definition of done

- Every step's substeps green-ticked.
- Three smoke-test prompts produce subjectively superior builds to v2.
- No state-machine illegal transitions over a 10-build run.
- Total average spend within $0.50–$1.50 band.
- Fractal texture pass visibly improves the output (compare weathered/non-weathered renders).
- Self-extending toolbox has promoted at least 3 user-defined functions to the permanent library.

---

## Execution progress

Legend: [x] complete · [~] in progress · [ ] pending

- [x] Step 1 — Concept Synthesis & Visual Grounding
  - [x] 1.1 Imagen integration
  - [x] 1.2 ConceptAgent
  - [x] 1.3 Reference infrastructure
- [x] Step 2 — 3D Mass Extraction
  - [x] 2.1 MassExtractionAgent
  - [x] 2.2 Mass → Blueprint bounds
  - [x] 2.3 Silhouette critic bootstrap
- [x] Step 3 — Model Tiering & Fallback Redesign
  - [x] 3.1 Model registry + TaskKind + CostTracker
  - [x] 3.2 Failure-class retry chains
  - [x] 3.3 Graceful degradation
- [x] Step 4 — RAG Architectural Knowledge Base
  - [x] 4.1 Knowledge corpus
  - [x] 4.2 Embedding + retrieval
  - [x] 4.3 Injection into planning
- [x] Step 5 — Hierarchical Decomposition
  - [x] 5.1 L1 ArchitectAgent
  - [x] 5.2 L2 DecomposerAgent
  - [x] 5.3 L3 ElementDesignerAgent
- [x] Step 6 — Geometric Toolbox & Sandbox
  - [x] 6.1 Core toolbox
  - [x] 6.2 Restricted Python sandbox
  - [x] 6.3 Self-extending toolbox
- [x] Step 7 — L4 REPL Agent
  - [x] 7.1 REPL loop
  - [x] 7.2 Element scheduling
  - [x] 7.3 Cumulative compilation
- [x] Step 8 — Parallel Critic Swarm
  - [x] 8.1 Critic specialization (5 seats)
  - [x] 8.2 Parallel dispatch
  - [x] 8.3 ReconcilerAgent
- [ ] Step 9 — Fractal Texture Pass
- [ ] Step 10 — State Machine & Orchestrator
- [ ] Step 11 — Instant Placement
- [ ] Step 12 — Testing, Prompt Tuning, Cost Audit
