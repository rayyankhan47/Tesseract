# Tesseract

A Fabric mod for Minecraft 1.18.2. Describe a structure in plain English and it gets built instantly in the world.

```
/tesseract build "gothic cathedral with flying buttresses and a central spire"
```

## Architecture

A player prompt goes through an 11-phase pipeline backed by Gemini. Each phase has one responsibility. All share a `BuildState` object. No agent calls another directly. An `Orchestrator` singleton drives a strict state machine.

```
ConceptAgent          prompt → 4 concept images (Imagen 3)
MassExtractionAgent   concept image → 16³ voxel mass sketch (Gemini 2.5 Pro multimodal)
RagAgent              prompt + image caption → top-6 architectural corpus entries
L1ArchitectAgent      prompt + concept + mass + RAG → MassPlan (major masses + bounds)
L2DecomposerAgent     MassPlan → StructuralZones (foundation, body, crown, rhythm per mass)
L3ElementDesignerAgent zones → ElementSpecs (per-zone, dependency-ordered)
L4ReplAgent           per element: REPL loop (script → sandbox → render → critics → patch)
FractalTexturePipeline locked ops → weathering, WFC patterns, L-system vines, decay, cracks
SyncPlacer            final ops → world (instant, batched by 2k to avoid server freeze)
```

**The LLM never emits block coordinates.** The L4 agent writes Python-subset scripts that call a geometric toolbox (`box`, `cylinder`, `pyramid`, `arc`, `subtract`, `crenellate`, `scatter`, etc.). A sandboxed interpreter evaluates the scripts and emits `BlockOp` lists. The toolbox is self-extending: well-rated user-defined functions from each build are promoted to the permanent library.

**Every planning agent sees the concept image and voxel mass.** Agents never plan blind. Critics compare every render against the original concept image, not against an imagined ideal.

**Per-element REPL loop.** For each architectural element the L4 agent iterates up to 10 turns: write script → run sandbox → render cumulative build → 5 parallel critics → reconcile → refine or lock. Elements are processed in dependency order (foundation first, spires last). Once locked, an element is never revisited.

**Five parallel critics per element turn:** Silhouette (voxel mass coverage), Style (0–1 coherence score), Proportion (human-scale ratios), Detail (surface richness), Reference Match (concept image comparison). A Reconciler agent merges their patches.

**Fractal texture pass (non-negotiable, no LLM).** After all elements lock: 3D Perlin noise weathering, Wave Function Collapse for patterned regions (mosaics, parquet, stained glass), L-system organic growth (vines, moss), cellular-automaton decay, Bresenham crack lines.

State machine:

```
IDLE → CONCEPT_SYNTHESIZING → MASS_EXTRACTING → RAG_QUERYING
     → L1_ARCHITECTING → L2_DECOMPOSING → L3_DESIGNING
     → L4_ITERATING (inner loop per element)
     → TEXTURING → PLACING → COMPLETE
                                       (any state) → FAILED
```

Text-only fallback: if Imagen or mass extraction fails, the pipeline skips to RAG_QUERYING and continues without a concept image. Build completes at reduced quality.

All LLM calls are async (`CompletableFuture`). Callbacks re-enter the Minecraft server thread via `server.execute()` before touching world state.

## Model tiering

| Tier | Model | Used for |
|---|---|---|
| Image gen | `imagen-3.0-generate-002` | Concept synthesis |
| Heavy multimodal | `gemini-2.5-pro` | Mass extraction, L1 Architect, Reconciler |
| Workhorse | `gemini-2.5-flash` | L2/L3/L4, inner-loop critics, RAG planning |
| Cheap bulk | `gemini-2.5-flash-lite` | Material picks, simple JSON parses |
| Embeddings | `text-embedding-004` | RAG corpus retrieval |

All routed through `ModelRegistry` by `TaskKind`. No hard-coded model IDs inside agents. Fallback strategy is failure-class-specific: transient (5xx) → exponential backoff on same model; rate limit (429) → one-shot downshift; parse failure → escalate to stronger model.

## Cost

Target: **$0.50–$1.50 per build**, ~40–80 LLM calls. A hard $2.00 budget cap halts the build and ships whatever geometry is complete. Per-phase cost breakdown is logged to chat at COMPLETE via `CostTracker`.

## Setup

**Prerequisites:** Minecraft Java Edition 1.18.2, Java 17, Python 3.8+.

**1. Add your Gemini API key.**

Create `.env` in the project root:

```
GEMINI_API_KEY=your_key_here
```

**2. Start three services** (three terminals):

```bash
# Terminal 1 — plan store (port 4890)
python3 tools/plan_server.py

# Terminal 2 — web dashboard (port 5173)
cd web && python3 server.py

# Terminal 3 — Minecraft server/client
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE_USER_HOME=/path/to/.gradle-jdk17 ./gradlew runClient
```

On first run the mod embeds the 150-entry architectural corpus and caches embeddings to `run/tesseract_cache/embeddings.bin`. This takes ~5 seconds and is skipped on subsequent runs.

## Usage

**In-game build:**
1. Punch the ground with a wooden axe to set the build anchor.
2. Run `/tesseract build <prompt>`.
3. Agent progress is logged to in-game chat in real time. Cost summary appears at COMPLETE.

**Web dashboard:**
1. Open `http://localhost:5173`, enter a prompt, optionally attach a reference image.
2. Click Generate. Copy the returned URL.
3. Run `/tesseract paste <url>` in-game with a region selected.

**Shortcuts:** `/tesseract demo cabin` and `/tesseract demo gate` run preset prompts.

## Debugging

```bash
# Dump concept PNGs, voxel mass renders, and per-element isometric renders to disk
./gradlew runClient -Dtesseract.debug.renders=true
# → run/tesseract_debug/<buildId>/concept_<n>.png
# → run/tesseract_debug/<buildId>/mass.png
# → run/tesseract_debug/<buildId>/<element>_turn<n>.png
```

Concept images are cached by prompt hash at `run/tesseract_cache/concepts/`. Corpus embeddings are cached at `run/tesseract_cache/embeddings.bin`. Delete either to force regeneration.

Promoted toolbox extensions (user-defined geometry functions rated ≥0.8 by `ToolPromoter`) accumulate in `resources/toolbox_extensions.jsonl`.

## Project structure

```
src/main/java/com/rayyan/tesseract/
  agent/       Orchestrator, OrchestratorState, BuildState, all agents
               L1ArchitectAgent, L2DecomposerAgent, L3ElementDesignerAgent
               L4ReplAgent, ElementScheduler, CumulativeBuild
               ConceptAgent, MassExtractionAgent, RagAgent
               CriticSwarm, ReconcilerAgent (+ 5 specialist critics)
  blueprint/   MassPlan, StructuralZone, ElementSpec, VoxelMass
               Toolbox (15 geometric functions), CompositionOps
  render/      IsoRenderer, BlockColorPalette, VoxelMassRenderer
  api/         GeminiClient, ImagenClient, EmbeddingClient
               ModelRegistry, TaskKind, CostTracker
  sandbox/     Restricted Python-subset interpreter (Lexer, Parser, Interpreter,
               SandboxLimits, NativeFn, ToolPromoter)
  texture/     FractalTexturePipeline, WeatheringPass, WFCPass,
               LSystemPass, DecayPass, CrackPass
  jobs/        BuildJobManager, SyncPlacer
  paste/       PlanPasteClient
  selection/   Region selection (wooden axe / golden axe)
  TesseractMod.java
resources/
  architecture_corpus.jsonl   150 architectural knowledge entries
  weathering_palette.json     Material substitution tables
  wfc_tilesets/               Wave Function Collapse tile rulesets
  toolbox_extensions.jsonl    Self-promoted geometry functions (grows over time)
tools/
  plan_server.py
web/
  server.py / index.html / app.js / styles.css
```

## Ports

| Port | Service |
|---|---|
| 4890 | plan store |
| 4891 | mod HTTP endpoint (`POST /build`) |
| 5173 | web dashboard |

## Constraints

- Minecraft 1.18.2 only
- One concurrent build per player, one concurrent web build
- Hard $2.00 per-build budget cap (soft target $0.50–$1.50)
- Per-element L4 REPL: max 10 turns, $0.15 cap
- Fractal texture pass is deterministic (seeded by build ID)
