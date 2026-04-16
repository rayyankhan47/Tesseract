# Tesseract — Refactor 2: Blueprint DSL, Vision Critic, and Iteration Loop

## Context

The current horizontal pipeline has each agent operate on **raw block coordinates**. Every `GenerationAgent` call emits hundreds of `{x, y, z, block}` objects in isolation, with no knowledge of the blocks already placed by prior components. This is the root cause of the coherence issues we've been fighting: floating roofs, misaligned walls, orientation guesses, massive prompts, frequent 5xx errors, bounds failures, and generally uninspired output.

Production "Cursor for X" systems never have the LLM emit the final artifact directly. They emit a higher-level representation that a deterministic layer compiles and validates. Cursor emits diffs and an Apply model reconciles them. v0 emits React components. Replit Agent emits shell commands. We have been emitting pixels.

This refactor replaces per-component coordinate generation with a **Blueprint DSL**: a structured JSON language for Minecraft architecture (walls, roofs, columns, arches, openings) whose primitives compile deterministically to block ops. On top of this we add a **vision-in-the-loop critic** that renders the compiled build as an isometric PNG, asks Gemini Vision to critique it, and emits a blueprint patch to iterate on. A final **detail agent** layers decoration (torches, trim, accents) once the structural loop converges.

The player-facing input and output are unchanged: prompt in, blocks out. Everything between `InterpretationAgent` and `PlacementAgent` is being replaced.

---

## Current → New Architecture Mapping

| Existing Code | New Role |
|---|---|
| `InterpretationAgent.java` | **Preserved as-is** — still converts prompt → `BuildSpec` |
| `PlanningAgent.java` | **Replaced** by `BlueprintPlanningAgent` — emits a `Blueprint`, not a `List<ComponentPlan>` |
| `GenerationAgent.java` | **Deleted** — no more per-component coordinate generation |
| `CriticAgent.java`, `CriticResult.java` | **Deleted** — structural correctness enforced by the compiler; aesthetic correctness by the visual critic |
| `ComponentPlan.java` | **Deleted** — replaced by blueprint `Primitive` records |
| `BuildQueueManager.java`, `PlacementAgent.java` | **Preserved** — still place block ops tick-by-tick |
| `BuildJobManager.java` | **Preserved** — still manages the per-player build lock |
| `Orchestrator.java` | **Updated** — state machine gains `BLUEPRINTING, COMPILING, RENDERING, CRITIQUING_VISUAL, PATCHING, DETAILING` |
| `OrchestratorState.java` | **Updated** — new states and transitions |
| `BuildState.java` | **Updated** — gains `Blueprint`, `CompiledBlueprint`, `iterationCount`; loses `componentPlan`, `currentComponentIndex`, `failedComponentIds` |
| `GeminiClient.java` | **Preserved as-is** — the existing multimodal overload is used by the visual critic |
| `Selection`, `SelectionManager`, `TesseractClient`, `PlanPasteClient` | **Completely unchanged** |

---

## Target Pipeline (End State)

```
prompt
  ↓
InterpretationAgent        → BuildSpec
  ↓
BlueprintPlanningAgent     → Blueprint (JSON DSL)
  ↓
BlueprintCompiler          → CompiledBlueprint { ops, primitiveBounds }
  ↓
IsoRenderer                → PNG (front + back isometric views)
  ↓
VisualCriticAgent          → Patch or { satisfied: true }
  ↓
[if !satisfied && iter<3]
BlueprintPatcher           → new Blueprint → loop back to Compiler
  ↓
DetailAgent                → appends torch/trim ops to completedOps
  ↓
PlacementAgent             → places block-by-block via BuildQueueManager
```

---

## Progress Tracker

- [x] **Step 1** — Design and define the Blueprint DSL
- [x] **Step 2** — Implement the deterministic Blueprint compiler
- [x] **Step 3** — Build the BlueprintPlanningAgent
- [x] **Step 4** — Remove the old per-component generation flow
- [x] **Step 5** — Implement the isometric block renderer
- [x] **Step 6** — Build the VisualCriticAgent and patch applier
- [x] **Step 7** — Wire the iteration loop into the Orchestrator
- [x] **Step 8** — Build the DetailAgent
- [x] **Step 9** — Update the Orchestrator state machine end-to-end
- [ ] **Step 10** — Testing, prompt tuning, and end-to-end smoke tests

---

## Step 1 — Design and Define the Blueprint DSL

The DSL is the single most important design artifact in this refactor. Everything downstream (compiler, planner prompt, critic patches, detail agent) depends on it being simple, composable, and LLM-friendly. The goals are: compact enough for Gemini to reliably emit, expressive enough to describe a cabin/tower/gate coherently, and structured enough that a dumb compiler can turn it into correct block ops without guessing.

### 1.1 — Write the schema specification document

Create `docs/BLUEPRINT_DSL.md` as the canonical reference for both humans and the Planning agent's system prompt.

#### 1.1.1 — Document the root schema

- Define the top-level `Blueprint` object with fields: `name` (string), `bounds` ({sizeX, sizeY, sizeZ}), `primitives` (ordered array of primitive objects)
- State the invariants: `primitives[0]` must have no `on` reference (it's the anchor); all primitive `id` values must be unique; all `on` references must resolve to an earlier-declared primitive
- Document the coordinate system: `(0,0,0)` is the min-corner of the build region, `x/z` are horizontal, `y` is up; all primitive-local coordinates are blueprint-local (the compiler translates to world coordinates later)

#### 1.1.2 — Document every primitive type and its params

Each primitive entry in the doc must include: type name, purpose, required params, optional params, and a minimal JSON example. Cover exactly these 10 primitives in v1:

- `platform` — rectangular floor; params: `origin [x,y,z]`, `size [sx,sy,sz]`, `material`, optional `edge_material`
- `walls` — hollow box; params: `on` (parent id), `height`, `material`, optional `corner_material`, optional `openings: [{face, u_offset, v_offset?, width, height, type: door|window|gap}]`
- `wall_segment` — single flat wall between two points; params: `from [x,y,z]`, `to [x,y,z]`, `height`, `material`
- `gable_roof` — triangular roof; params: `on`, `ridge_axis: "x"|"z"`, `overhang`, `stairs_material`, `slab_material`, optional `ridge_material`
- `hip_roof` — pyramid; params: `on`, `stairs_material`, `slab_material`, optional `apex_material`
- `flat_roof` — flat top; params: `on`, `material`, optional `battlements: true`, optional `battlement_material`
- `column` — vertical pillar; params: `origin [x,y,z]`, `height`, `material`, optional `cap_material`, optional `base_material`
- `arch` — archway; params: `from [x,y,z]`, `to [x,y,z]`, `height`, `material`
- `staircase` — connects two elevations; params: `from [x,y,z]`, `to [x,y,z]`, `width`, `material` (should be a stairs block)
- `frame` — hollow rectangular box (no fill inside); params: `origin`, `size`, `material`

#### 1.1.3 — Document a full worked example

- Include a complete `cozy_oak_cabin` blueprint (foundation + walls + gable roof) as the gold-standard example
- Include a second example showing references: a `stone_watchtower` (foundation + walls + flat_roof with battlements + corner columns)
- These examples will be copy-pasted verbatim into the BlueprintPlanningAgent system prompt as few-shots

### 1.2 — Create Java data classes for the blueprint

Create these in a new package `com.rayyan.tesseract.blueprint`.

#### 1.2.1 — Define `Blueprint.java`

- Fields: `String name`, `Bounds bounds`, `List<Primitive> primitives`, `String rawJson`
- Immutable after construction; use a static `fromJson(String)` factory
- `Bounds` is a nested or sibling record: `Bounds(int sizeX, int sizeY, int sizeZ)`

#### 1.2.2 — Define `Primitive.java` and its subtypes

- Base `Primitive` as a sealed interface or abstract class with common fields: `String id`, `String type`, `String on` (nullable), `Map<String, Object> params`
- Rather than 10 subclasses, keep `params` as a `JsonObject` (Gson) so the compiler can read whatever fields each primitive type requires; this avoids brittle class hierarchies as the DSL evolves
- Add convenience getters: `getInt(key)`, `getString(key)`, `getIntArray(key)`, `getArray(key)`, each with defaults and clear error messages

#### 1.2.3 — Define supporting records

- `record Opening(String face, int uOffset, int vOffset, int width, int height, String type)` — used by `walls` primitive
- `record PrimitiveBounds(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ)` — used by the compiler to track resolved placement per primitive
- `record CompiledBlueprint(List<BlockOp> ops, Map<String, PrimitiveBounds> primitiveBounds)` — the compiler's output

### 1.3 — JSON parsing and validation

#### 1.3.1 — Create `BlueprintParser.java`

- Method: `static Blueprint parse(String json)` using Gson
- Strip markdown fences first (same pattern as `GenerationAgent.parseOps`)
- Throws `BlueprintParseException` with the raw json trimmed to 240 chars on failure

#### 1.3.2 — Schema validation

- After parsing, run structural validation: every primitive has a non-blank `id`; no duplicate ids; every non-null `on` references an earlier-declared primitive; every primitive has a known `type`
- Unknown primitive types log a warning and are skipped (forward-compatible for future primitive additions)
- Bounds sanity: `sizeX`, `sizeY`, `sizeZ` all positive and under a generous max (e.g. 128 each)

#### 1.3.3 — Provide unit-testable examples

- Copy the two worked examples from `docs/BLUEPRINT_DSL.md` into `src/test/resources/blueprints/cabin.json` and `watchtower.json`
- Write `BlueprintParserTest` that round-trips both examples (parse → serialize → parse) and checks field equality

---

## Step 2 — Implement the Deterministic Blueprint Compiler

The compiler is the heart of this refactor. It takes a `Blueprint` and produces a concrete `List<BlockOp>` using pure code — no LLM calls. Every primitive has a small dedicated compiler that emits block ops for that primitive's geometry, resolving `on` references to get absolute positions.

### 2.1 — Compiler skeleton and dependency resolution

#### 2.1.1 — Create `BlueprintCompiler.java`

- In `com.rayyan.tesseract.blueprint` package
- Public entry point: `static CompiledBlueprint compile(Blueprint bp)`
- Throws `BlueprintCompileException` on any irrecoverable error (cycles, unresolved refs, out-of-bounds, unknown type)

#### 2.1.2 — Topological sort by `on` references

- Build a dependency graph where each primitive depends on its `on` parent
- Kahn's algorithm (or simple iterative resolution) to produce a valid compile order
- Detect cycles and throw with a message naming the cycle members

#### 2.1.3 — Track resolved bounds per primitive

- Maintain a `Map<String, PrimitiveBounds>` as you compile; each primitive's resolved bounds are inserted before compiling dependents
- This map becomes part of the output `CompiledBlueprint` so later stages (critic, detail agent) can reason about component placement without re-parsing

### 2.2 — Primitive compilers

Implement the compilation logic for each primitive in a dedicated class `com.rayyan.tesseract.blueprint.PrimitiveCompilers`.

#### 2.2.1 — Structural primitives (tier 1)

- `compilePlatform(Primitive, ctx)` — fills a flat rectangular slab with `material`; if `edge_material` set, the outermost ring uses that instead
- `compileWalls(Primitive, ctx)` — resolves parent bounds, walks the perimeter at each y-level, skips blocks inside `openings`, uses `corner_material` on the four corner columns; door openings carry down to the ground, window openings are punched at `v_offset`
- `compileGableRoof(Primitive, ctx)` — computes ridge along `ridge_axis`, emits stairs on the slopes with correct `facing` property, slabs above the ridge, ridge material along the top; overhang extends past parent bounds along the non-ridge axis

#### 2.2.2 — Structural primitives (tier 2)

- `compileHipRoof(Primitive, ctx)` — pyramid of stairs converging to a centerline/apex; four triangular sides, each facing inward
- `compileFlatRoof(Primitive, ctx)` — single layer of `material`; if `battlements:true`, every other block on the perimeter is raised one block in `battlement_material`
- `compileColumn(Primitive, ctx)` — vertical line from `origin` upward `height` blocks; cap and base materials applied to top and bottom if specified
- `compileArch(Primitive, ctx)` — semicircle approximation between `from` and `to` at the given `height` using a precomputed arc table

#### 2.2.3 — Structural primitives (tier 3)

- `compileStaircase(Primitive, ctx)` — steps from `from` to `to`, stair block with correct `facing` on each step; width parameter extrudes perpendicular to step direction
- `compileFrame(Primitive, ctx)` — hollow box: only emit ops for cells on the outer shell of the box, leaving the interior empty
- `compileWallSegment(Primitive, ctx)` — a single flat wall of given height between `from` and `to` (used when `walls` is too constrained — e.g. a non-rectangular courtyard perimeter)

### 2.3 — Reference resolution via `on`

#### 2.3.1 — Define reference semantics

- When a primitive has `on: "foundation"`, its y-origin is the top face of the foundation (`foundationBounds.originY + foundationBounds.sizeY`), and its x/z extents are inherited from the foundation's footprint unless overridden
- Walls placed `on:"foundation"` auto-inherit foundation footprint; a gable roof placed `on:"walls"` auto-inherits the wall footprint plus `overhang`

#### 2.3.2 — Override via explicit params

- Any primitive may provide explicit `origin`/`size` params to override the inherited values
- Resolution order: explicit param → parent-inherited value → compile error if neither available

#### 2.3.3 — Enforce blueprint bounds

- Every emitted `BlockOp` must satisfy `0 ≤ op.x < bounds.sizeX`, same for y and z
- Out-of-bounds ops are clamped at the edge by default, but if more than 20% of a primitive's ops are clamped, the compiler emits a warning tagged with that primitive's id so the planner can be retried with a corrected blueprint

### 2.4 — Output ordering and deduplication

#### 2.4.1 — Use a keyed accumulator

- Compile into a `LinkedHashMap<BlockPos, BlockOp>` so later primitives override earlier ones at the same position (this is how torches on walls, doors in walls, windows etc. "win" against the underlying wall fill)
- Preserve insertion order so placement animation remains spatially coherent when streamed through `BuildQueueManager`

#### 2.4.2 — Emit stable deterministic output

- Compiling the same blueprint twice must produce byte-identical block op lists (no `HashSet`, no `Math.random`, no system time dependencies)
- This is required for the critic loop — we cache renders keyed by compile output; nondeterminism invalidates the cache

#### 2.4.3 — Return the full `CompiledBlueprint`

- Final return: `new CompiledBlueprint(List.copyOf(accumulator.values()), Map.copyOf(primitiveBoundsMap))`
- Log at INFO level: number of ops per primitive, total ops, total compile time

---

## Step 3 — Build the BlueprintPlanningAgent

This replaces `PlanningAgent` entirely. Instead of outputting a list of component descriptions that a downstream generator must turn into coordinates, it outputs a complete blueprint that the compiler will turn into coordinates deterministically. The prompt is the single most important piece of prose in the system.

### 3.1 — Design the system prompt

#### 3.1.1 — Teach the model the DSL schema

- The prompt must include: the root schema, every supported primitive type with its required/optional params, and the two worked examples from `docs/BLUEPRINT_DSL.md`
- Be explicit about: "Do NOT emit block coordinates. Do NOT emit `{x, y, z, block}` objects. Emit the structured blueprint only."
- Stress the compositional pattern: foundation first, walls on foundation, roof on walls, details last

#### 3.1.2 — Constrain material palette and bounds

- Pass the focused palette (already computed by `GenerationAgent.buildFocusedPalette(spec)` — move this helper into a shared utility) as the only valid block IDs
- Pass the exact `bounds` from the `BuildSpec` (sizeX, sizeY, sizeZ) and instruct the model that every primitive must fit inside them
- Explicitly forbid: placing primitives at negative coordinates, using materials outside the palette, referencing a primitive that doesn't exist

#### 3.1.3 — Provide style guidance

- Inject the `BuildSpec.style`, `BuildSpec.type`, and `BuildSpec.features` list as natural-language context
- Few-shot: show how "gothic stone gate" maps to specific primitives (twin `column`s, central `arch`, `flat_roof` with `battlements`, `torch` decorations); how "cozy oak cabin" maps to (stone `platform`, oak `walls` with windows+door, `gable_roof`)
- Mention architectural cues: stair primitives should face inward for stairs, ridge logs on gable roofs should run along the ridge, corner posts are a detail not a full primitive

### 3.2 — Implement `BlueprintPlanningAgent.java`

#### 3.2.1 — File and method signature

- Create `src/main/java/com/rayyan/tesseract/agent/BlueprintPlanningAgent.java`
- Public method: `static void run(BuildState state, GeminiClient gemini, Runnable onComplete, Consumer<String> onError)`
- Internally calls `gemini.complete(SYSTEM_PROMPT, buildUserPrompt(state))` and attaches a `whenComplete` handler

#### 3.2.2 — Prompt construction

- Static `buildUserPrompt(BuildState state)` assembles: the full `BuildSpec` JSON, the resolved bounds from `state.buildSelection`, the focused palette, and any prior critic feedback (starts null, gets appended on iteration 2+)
- Keep the user prompt under ~4k tokens — the system prompt carries the schema/examples, the user prompt carries only the task-specific context

#### 3.2.3 — Parse, validate, attach

- On Gemini response: call `BlueprintParser.parse(raw)`, then run `BlueprintCompiler.compile(blueprint)` as a pre-flight check
- If either step throws: call `onError` with a message suitable for retry (e.g. "Blueprint failed to compile: unresolved reference 'walls' in primitive 'roof'")
- On success: set `state.blueprint`, set `state.compiledBlueprint`, emit a `BuildEvent` summarizing the primitive list, call `onComplete`

### 3.3 — Extend `BuildState` for the blueprint flow

#### 3.3.1 — Add new fields

- `Blueprint blueprint` — current best blueprint (updated on each patch)
- `CompiledBlueprint compiledBlueprint` — output of the most recent compile
- `int iterationCount` — number of critic passes performed so far
- `byte[] lastRenderPng` — cached render output (for critic calls and optional debug export)

#### 3.3.2 — Remove old fields

- Delete `List<ComponentPlan> componentPlan`
- Delete `int currentComponentIndex`
- Delete `List<String> failedComponentIds`
- Update every reader in `Orchestrator.java` and elsewhere

#### 3.3.3 — Preserve placement-facing fields

- `completedOps` stays — `PlacementAgent` reads it at the end of the pipeline
- `buildSelection`, `placementOrigin` stay — the compiler outputs blueprint-local coords, but placement still applies the `placementOrigin` offset
- Web build support (`isWebBuild`, `webBuildFuture`) stays untouched

---

## Step 4 — Remove the Old Per-Component Generation Flow

The old Generation/Critic/ComponentPlan flow is now dead code. Cleanly remove it before adding more on top — mixed patterns are where bugs hide.

### 4.1 — Delete replaced agent classes

#### 4.1.1 — Remove source files

- Delete `src/main/java/com/rayyan/tesseract/agent/GenerationAgent.java`
- Delete `src/main/java/com/rayyan/tesseract/agent/CriticAgent.java`
- Delete `src/main/java/com/rayyan/tesseract/agent/CriticResult.java`
- Delete `src/main/java/com/rayyan/tesseract/agent/ComponentPlan.java`

#### 4.1.2 — Relocate the focused-palette helper

- Before deleting `GenerationAgent`, move `buildFocusedPalette(BuildSpec)` to a new `com.rayyan.tesseract.blueprint.PaletteUtils` class (so the BlueprintPlanningAgent can still use it)
- Keep the universal-block list and the spec-material expansion logic byte-identical — this function is load-bearing

#### 4.1.3 — Remove the old `PlanningAgent.java`

- Delete `PlanningAgent.java` outright — `BlueprintPlanningAgent` replaces it
- Also delete the three-space-axis layout logic inside it (the blueprint compiler does spatial layout now via `on` references)

### 4.2 — Prune `BuildState` of dead fields

#### 4.2.1 — Update the constructor

- Remove initialization of `componentPlan`, `currentComponentIndex`, `failedComponentIds`
- Initialize `iterationCount = 0`, `blueprint = null`, `compiledBlueprint = null`

#### 4.2.2 — Audit every reader

- `grep` for `componentPlan`, `currentComponentIndex`, `failedComponentIds` across the repo; delete or rewrite every reference
- Orchestrator's generation dispatch loop is the main user — that entire section is being replaced in Step 9 regardless

#### 4.2.3 — Preserve `completedOps` semantics

- `completedOps` now gets filled in one pass at the end of the iteration loop (not incrementally per component)
- Set `state.completedOps = state.compiledBlueprint.ops()` right before transitioning to PLACING
- DetailAgent (Step 8) will append its ops to this list before PLACING starts

### 4.3 — Remove old OrchestratorState values

#### 4.3.1 — Identify dead states

- `GENERATING` and `CRITIQUING` are being replaced; keep the enum entries during this step so the old code still compiles, but mark them with `@Deprecated` javadoc
- The actual enum rewrite happens in Step 9

#### 4.3.2 — Stub out old dispatch paths

- In `Orchestrator.transition()`, make `case GENERATING` and `case CRITIQUING` immediately throw `UnsupportedOperationException("old pipeline removed in Refactor 2")`
- This forces any stale caller into a clean runtime failure rather than silent wrong-path execution

#### 4.3.3 — Commit the removal in a single commit

- This step should be one clean commit titled "Refactor 2 — remove pre-blueprint generation pipeline"
- Document in the commit body what was removed and what's replacing it

---

## Step 5 — Implement the Isometric Block Renderer

The visual critic needs actual images. Minecraft's in-game screenshot is not automatable from the server thread, so we render blueprints client-free: pure Java 2D, orthographic isometric projection, each block a small sprite. Gemini Vision reads the result.

### 5.1 — Block-to-color palette

#### 5.1.1 — Create `BlockColorPalette.java`

- Location: `com.rayyan.tesseract.render.BlockColorPalette`
- Static `Color lookup(String blockId)` that strips any `[state=...]` suffix before lookup
- Returns magenta (`Color(255, 0, 255)`) for unknown blocks, logged once at DEBUG per blockId to aid palette expansion

#### 5.1.2 — Populate the palette

- Hardcode RGB values for ~80 common blocks: stone family (stone, cobblestone, stone_bricks, andesite, granite, diorite, deepslate, blackstone), wood families (oak/spruce/birch/jungle/acacia/dark_oak for planks, logs, stairs, slabs, doors, trapdoors), glass (clear + panes), metal (iron, gold, netherite blocks and bars), natural (dirt, grass, sand, gravel), and lighting (torch, lantern, glowstone)
- Stairs and slabs inherit the base material color so the isometric look remains readable
- Keep the list as a private static `Map<String, Color>` initialized in a static block

#### 5.1.3 — Slight per-face shading

- The renderer draws three visible faces per block (top, left, right); apply a brightness multiplier: top = 1.0, left = 0.82, right = 0.66
- This gives the render enough depth cue that Gemini Vision can actually read the geometry

### 5.2 — Isometric projection renderer

#### 5.2.1 — Create `IsoRenderer.java`

- Location: `com.rayyan.tesseract.render.IsoRenderer`
- Public method: `static byte[] renderPng(List<BlockOp> ops, Bounds bounds, int pixelsPerBlock)` → PNG byte array
- Uses `BufferedImage`, `Graphics2D`, `ImageIO.write(bufferedImage, "png", outputStream)`

#### 5.2.2 — Projection math

- For each block at `(x, y, z)`: screen coordinates are `sx = (x - z) * pixelsPerBlock` and `sy = (x + z) * pixelsPerBlock / 2 - y * pixelsPerBlock`
- Compute the image size from the bounding box corners: screen-x range and screen-y range → image width × height + padding
- Draw a background color (light gray) so the compiled shape stands out

#### 5.2.3 — Back-to-front draw order

- Sort ops by `(x + z - y)` ascending; drawing in that order means closer blocks naturally occlude farther ones
- For each op: draw three parallelograms (top diamond, left quad, right quad) with the three shaded variants of the block's palette color; 1-pixel darker outline per face to separate adjacent blocks

### 5.3 — Multi-angle composite

#### 5.3.1 — Render a second angle

- After the primary render, re-render with the x and z axes swapped (effectively a 90° rotation around y); this gives Gemini a second view that exposes geometry hidden behind the primary view

#### 5.3.2 — Stitch side-by-side

- Composite the two renders horizontally with a 16px separator band into a single output PNG
- Annotate each half with a tiny text label ("front" / "back") rendered with `Graphics2D.drawString`

#### 5.3.3 — Write a debug copy to disk

- If a `tesseract.debug.renders` system property is set, also write the PNG to `run/tesseract_debug/{buildId}_iter{N}.png` for offline inspection while iterating on prompts
- Silent no-op if the directory doesn't exist or the property is unset

---

## Step 6 — Build the VisualCriticAgent and Patch Applier

The critic is the feedback loop's brain. It sees the compiled render, the original prompt, and the current blueprint, and emits a structured patch that (hopefully) makes the next iteration look better.

### 6.1 — Define the critique output schema

#### 6.1.1 — Critic response JSON

- Shape:
  ```json
  {
    "satisfied": false,
    "issues": [
      "The roof is too shallow relative to the wall height.",
      "The front door is off-center."
    ],
    "patch": [
      { "op": "modify", "id": "roof", "field": "ridge_axis", "value": "z" },
      { "op": "modify", "id": "walls", "field": "openings[0].u_offset", "value": 4 }
    ]
  }
  ```
- `satisfied: true` exits the iteration loop immediately
- `issues` is human-readable and surfaced to the player as build events

#### 6.1.2 — Patch op types

- `modify` — change one field on an existing primitive; `field` uses dot-path notation (e.g. `openings[0].u_offset`)
- `add` — insert a new primitive (full primitive object passed as `primitive` field)
- `remove` — delete a primitive by `id`
- `replace` — swap one primitive for another in-place, preserving its id

#### 6.1.3 — Constraints in the prompt

- Critic is told: the patch must not violate blueprint bounds; patches should be minimal (prefer `modify` over `replace`); never reference a primitive id that doesn't exist; if the build already looks good, set `satisfied: true` and emit an empty patch array

### 6.2 — Implement `VisualCriticAgent.java`

#### 6.2.1 — File and method signature

- Location: `src/main/java/com/rayyan/tesseract/agent/VisualCriticAgent.java`
- Public method: `static void run(BuildState state, GeminiClient gemini, Consumer<Critique> onComplete, Consumer<String> onError)`
- `Critique` is a new record in the `agent` package: `record Critique(boolean satisfied, List<String> issues, List<Patch> patch)`

#### 6.2.2 — Multimodal Gemini call

- System prompt frames the model as a senior architectural critic reviewing an in-progress Minecraft build
- User prompt bundles: the original prompt, the full BuildSpec, the current Blueprint JSON, and "here is what the current blueprint looks like"
- Attach `state.lastRenderPng` via the existing multimodal overload `gemini.complete(systemPrompt, userPrompt, imageBytes, "image/png")`

#### 6.2.3 — Parse and validate

- Parse response into `Critique`; on malformed JSON, treat as `satisfied=true, issues=[], patch=[]` and log a WARN (this is a fail-soft: a broken critic shouldn't stop a decent build from placing)
- If `patch` references a primitive id that doesn't exist, drop that patch entry and log; do NOT fail the whole iteration over one bad patch

### 6.3 — Apply patches to the blueprint

#### 6.3.1 — Create `BlueprintPatcher.java`

- Location: `com.rayyan.tesseract.blueprint.BlueprintPatcher`
- Public method: `static Blueprint apply(Blueprint source, List<Patch> patch)` returning a new (never-mutating) Blueprint

#### 6.3.2 — Handle each patch op

- `modify` — walk the dot-path on the matching primitive's `params` (e.g. `openings[0].u_offset`), write the new value
- `add` — append the new primitive at the end of the list
- `remove` — drop by id; if any other primitive's `on` referenced it, demote those to no-op primitives with a logged warning
- `replace` — swap the primitive body while preserving id and list position

#### 6.3.3 — Re-validate after patching

- Run `BlueprintParser.validate(patched)` followed by `BlueprintCompiler.compile(patched)` as a pre-flight
- If the patched blueprint no longer compiles, discard the patch entirely and keep the prior blueprint; log the compile error and let the iteration loop decide whether to try another pass

---

## Step 7 — Wire the Iteration Loop into the Orchestrator

This is where the pieces become a self-correcting system. Up to 3 passes of compile → render → critic → patch. Every pass either converges (satisfied=true), times out (total iteration time exceeds a cap), or hits max iterations and proceeds with the best-so-far.

### 7.1 — Loop controller in `Orchestrator`

#### 7.1.1 — Add loop constants

- `private static final int MAX_ITERATIONS = 3;`
- `private static final long ITERATION_BUDGET_MS = 90_000L;` — total wall-clock cap across all critic passes; if exceeded we stop iterating and proceed with the current blueprint
- Fields on `BuildState`: `iterationCount` (already added in 3.3.1) and `long iterationStartMs`

#### 7.1.2 — The loop structure

- On entering `COMPILING`: compile the blueprint into `state.compiledBlueprint`
- On entering `RENDERING`: render `state.compiledBlueprint.ops()` into `state.lastRenderPng`
- On entering `CRITIQUING_VISUAL`: call `VisualCriticAgent.run(...)`; when it returns:
  - If `satisfied=true` OR `iterationCount+1 >= MAX_ITERATIONS` OR budget exceeded: transition to `DETAILING`
  - Else: transition to `PATCHING`
- On entering `PATCHING`: call `BlueprintPatcher.apply(...)`, store new blueprint, `iterationCount++`, transition back to `COMPILING`

#### 7.1.3 — Safety rails

- If `COMPILING` fails (compile exception): transition to `FAILED` with the compiler's error message
- If `RENDERING` fails (e.g. IO error): log and skip to `DETAILING` with the last good blueprint (don't waste the build)
- If `PATCHING` returns an identical blueprint (critic gave a no-op patch): treat as satisfied and exit the loop

### 7.2 — Event feedback and progress

#### 7.2.1 — Emit per-stage events

- Every transition inside the loop emits a `BuildEvent` so the player sees progress:
  - "Compiling blueprint..."
  - "Rendering iso view (pass 1/3)..."
  - "Critic: roof is too low — adjusting blueprint"
  - "Compiling patched blueprint..."
  - "Build looks coherent — finalizing"

#### 7.2.2 — Progress bar integration

- Update `AgentProgressManager` to show a sub-label for the iteration state (e.g. "critiquing pass 2/3")
- The triangle-wave animation continues; only the label changes

#### 7.2.3 — Debug log of iteration artifacts

- On each iteration, if the debug system property is set (see 5.3.3), dump to disk:
  - `iterN_blueprint.json` — the blueprint used this pass
  - `iterN_render.png` — the isometric render shown to the critic
  - `iterN_critique.json` — the critic's raw response

### 7.3 — Fallback handling

#### 7.3.1 — Critic-call failure

- If `VisualCriticAgent.run(...)` calls `onError`: log a WARN, skip to `DETAILING` with the current blueprint — a flaky critic shouldn't abort an otherwise-valid build

#### 7.3.2 — Empty ops after compile

- If `compiledBlueprint.ops()` is empty: this is a fatal bug in the blueprint; transition to `FAILED` with a message telling the player to try a different prompt (never silently place nothing)

#### 7.3.3 — Single-iteration fast path

- If `MAX_ITERATIONS == 1`: skip `CRITIQUING_VISUAL` entirely and go `COMPILING → RENDERING → DETAILING`
- This is a runtime toggle (`tesseract.iterate` system property) so you can A/B test the critic's value during demos

---

## Step 8 — Build the DetailAgent

Once the structural blueprint is stable, the DetailAgent layers decoration on top: torches on walls near doors, window flowerboxes, corner banners, interior furniture. This is where "nice" becomes "genuinely charming" — small but high-impact.

### 8.1 — Detail output schema

#### 8.1.1 — Detail JSON shape

- A flat array of detail objects:
  ```json
  [
    { "type": "torch", "pos": [3, 3, 0], "face": "south", "block": "minecraft:torch" },
    { "type": "decoration", "pos": [4, 1, 1], "block": "minecraft:flower_pot" },
    { "type": "fill_line", "from": [0,1,3], "to": [0,1,6], "block": "minecraft:oak_fence" }
  ]
  ```
- Each detail compiles to one or more `BlockOp` via a small dedicated compiler (mirror of primitives)

#### 8.1.2 — Supported detail types

- `torch` — wall-mounted or standing light
- `decoration` — single-block placement (flower_pot, lantern, chest, crafting_table, etc.)
- `fill_line` — run of identical blocks between two points (fences, hanging banners)
- `sign` — sign block with optional text

#### 8.1.3 — Additive-only rule

- Details never remove or overwrite structural ops; if a detail op's position collides with an existing structural op, drop it and log
- The exception: `torch` and `lantern` may be placed on wall surfaces (the wall op is already there, the torch attaches); this is handled by placing the torch one block adjacent to the wall, not on it

### 8.2 — Implement `DetailAgent.java`

#### 8.2.1 — File and method signature

- Location: `src/main/java/com/rayyan/tesseract/agent/DetailAgent.java`
- Public method: `static void run(BuildState state, GeminiClient gemini, Runnable onComplete, Consumer<String> onError)`
- Called after the iteration loop converges (state `DETAILING`)

#### 8.2.2 — Prompt design

- System prompt: "You are the detail pass for a Minecraft builder. The structure has been built. Your job is to add small, high-impact decorations — torches, trim, signs, flowerpots — that make the build feel crafted, not generated."
- User prompt: original player prompt, final blueprint JSON, primitiveBounds map (so the model knows where doors/windows are), and the rendered image (multimodal)

#### 8.2.3 — Parse and merge

- Parse detail JSON into `List<Detail>`, compile each to `List<BlockOp>`, append to `state.compiledBlueprint.ops()` via a new list (keep the compiled ops immutable; produce a combined list on `BuildState.completedOps`)
- On parse failure: log WARN, call `onComplete` with no added details (this is additive so zero details is fine)

### 8.3 — Bounds and overlap filtering

#### 8.3.1 — Bounds check

- Drop any detail op outside the blueprint `bounds` silently (with a single aggregate log line at the end: "dropped N out-of-bounds details")

#### 8.3.2 — Structural overlap check

- For each detail op: look up whether a structural op already exists at `(x, y, z)`; if yes and the detail `type` is not one of the allowed-overlap list (`torch`, `lantern`, `sign` — these intentionally attach to structural blocks adjacent), drop it
- Keep a running count of drops per reason; surface the counts in the final build event

#### 8.3.3 — Finalization

- Set `state.completedOps = merge(state.compiledBlueprint.ops(), acceptedDetailOps)` (structural first so blocks place in a sensible order)
- Transition to `PLACING`; from here the existing `PlacementAgent` + `BuildQueueManager` flow handles everything exactly as before

---

## Step 9 — Update the Orchestrator State Machine End-to-End

With all the new agents in place, the state machine needs to reflect the real flow. This step rewires `OrchestratorState`, `Orchestrator.transition()`, and makes sure every transition is a clean, documented move.

### 9.1 — New state enum and transitions

#### 9.1.1 — Rewrite `OrchestratorState.java`

- Enum values: `IDLE, INTERPRETING, BLUEPRINTING, COMPILING, RENDERING, CRITIQUING_VISUAL, PATCHING, DETAILING, PLACING, COMPLETE, FAILED`
- Remove the deprecated `GENERATING` and `CRITIQUING` entries
- Update the file-header javadoc to document the new legal transition set

#### 9.1.2 — Define legal transitions

- `IDLE → INTERPRETING`
- `INTERPRETING → BLUEPRINTING`
- `BLUEPRINTING → COMPILING`
- `COMPILING → RENDERING`
- `COMPILING → FAILED` (compile error)
- `RENDERING → CRITIQUING_VISUAL`
- `RENDERING → DETAILING` (single-iteration fast path)
- `CRITIQUING_VISUAL → PATCHING` (not satisfied)
- `CRITIQUING_VISUAL → DETAILING` (satisfied or max iterations)
- `PATCHING → COMPILING` (loop)
- `DETAILING → PLACING`
- `PLACING → COMPLETE`
- `any → FAILED`

#### 9.1.3 — Update `assertTransition(...)`

- Implement the new allowed-set switch statement mirroring 9.1.2
- Keep the failure message clear: "Illegal Orchestrator state transition: X → Y"

### 9.2 — Rewire `Orchestrator.transition(...)`

#### 9.2.1 — Replace the old dispatch

- Remove all `case GENERATING` and `case CRITIQUING` code paths
- Add `case BLUEPRINTING`, `case COMPILING`, `case RENDERING`, `case CRITIQUING_VISUAL`, `case PATCHING`, `case DETAILING`
- Each one calls its respective agent/compiler/renderer and transitions on completion (same `whenComplete → server.execute → transition` pattern)

#### 9.2.2 — `COMPILING` and `RENDERING` are synchronous

- These don't make Gemini calls, so they execute inline on the server thread; catch exceptions and route to `FAILED`
- Iteration budget check happens inside `CRITIQUING_VISUAL` after the critic returns

#### 9.2.3 — Preserve web-build and cancellation behavior

- `runWebBuild(...)` still returns a `CompletableFuture<String>` populated at `COMPLETE`
- `cancelBuild(playerId)` still transitions to `FAILED` and removes the state
- `reset()` (used on world switch) still clears everything including any partial iteration state

### 9.3 — Wire `AgentProgressManager` to new states

#### 9.3.1 — Extend the stage map

- Add stage label mappings for all new states: "blueprint drafting", "compiling...", "rendering view", "visual critic (pass N)", "patching blueprint", "decoration pass", "placing blocks"
- Keep the same boss-bar purple theme

#### 9.3.2 — Hide on terminal states

- `COMPLETE` and `FAILED` hide the bar; `COMPLETE` flashes green for 2 seconds first
- Ensure no leaked bars after cancellation or world switch

#### 9.3.3 — Log the full state timeline on completion

- At COMPLETE, log (INFO) a one-line timeline: "IDLE → INTERPRETING(1.2s) → BLUEPRINTING(4.3s) → COMPILING(0.1s) → RENDERING(0.3s) → CRITIQUING_VISUAL(2.1s) → PATCHING(0.0s) → COMPILING(0.1s) → RENDERING(0.3s) → CRITIQUING_VISUAL(1.8s) → DETAILING(3.0s) → PLACING(18.5s) → COMPLETE"
- Invaluable for debugging demo builds

---

## Step 10 — Testing, Prompt Tuning, and End-to-End Smoke Tests

Everything above could be structurally correct and still produce ugly builds — the prompts are the other half of the system. Iterate until the demo builds land.

### 10.1 — Unit tests for the compiler

#### 10.1.1 — Primitive-level tests

- `BlueprintCompilerTest`: for each primitive type (`platform`, `walls`, `gable_roof`, `column`, `staircase`), construct a minimal blueprint and assert the output block ops match expected coordinates
- Use the two canonical examples from `src/test/resources/blueprints/` as full-blueprint golden tests

#### 10.1.2 — Reference resolution tests

- Walls on foundation → walls inherit foundation footprint
- Roof on walls → roof inherits walls footprint with overhang
- Cyclic reference → throws `BlueprintCompileException`
- Missing reference → throws `BlueprintCompileException`

#### 10.1.3 — Bounds enforcement tests

- Primitive with origin outside bounds: clamped, warning emitted
- Primitive whose size pushes past bounds: clamped at the edge
- Entirely out-of-bounds primitive: dropped with warning

### 10.2 — Integration smoke tests

#### 10.2.1 — Three canonical prompts

- `/tesseract build "small cozy oak cabin with stone foundation and gable roof"` — expect: coherent cabin, walls not floating, roof over walls, door visible
- `/tesseract build "stone watchtower with battlements and torches"` — expect: square tower, crenellated top, torches placed around the perimeter
- `/tesseract build "gothic stone gate with twin towers and central arch"` — expect: two towers flanking an arched entrance, optional flat_roof with battlements

#### 10.2.2 — Run each 3 times

- Each prompt is run three times with the same build anchor; result is inspected in-game
- Track: structural coherence (pass/fail), visual quality (1-5), time to complete, iteration count consumed
- Record results in a new file `docs/SMOKE_TEST_RESULTS.md`

#### 10.2.3 — Compare against the old pipeline

- Before deleting the old branch's builds: record screenshots of the same three prompts from the old coordinate-based pipeline
- Place them side-by-side with the new pipeline's output for the interview demo — the comparison is the story

### 10.3 — Prompt tuning pass

#### 10.3.1 — BlueprintPlanningAgent prompt refinement

- Review all iter0_blueprint.json dumps; identify recurring failure modes (e.g., "always forgets corner columns on watchtowers", "places doors on wrong face")
- Add targeted counter-examples or explicit rules to the system prompt until the failure mode disappears
- Never inflate the prompt past ~6k tokens total — if something's not helping, cut it

#### 10.3.2 — VisualCriticAgent prompt refinement

- Review all iterN_critique.json dumps; identify useless critiques (e.g., "make it more architectural") and destructive patches (remove a critical primitive)
- Add explicit rules: "do not remove the foundation", "patches must name concrete issues", "prefer modify over replace"
- Calibrate strictness: if the critic never says `satisfied: true`, loosen it; if it always says satisfied on iteration 1, tighten it

#### 10.3.3 — DetailAgent prompt refinement

- Review which details get dropped (bounds violations, overlap rejections)
- If >30% of details are being rejected, the prompt needs to teach the model the primitive-bounds system better
- Iterate until detail pass reliably adds 5-15 high-quality decoration ops per build

---

## Out of Scope (Deliberately)

The following ideas are intentionally deferred so this refactor can ship and stay readable. Note them in the interview writeup under "what I would build next."

- **RAG / exemplar library** — hand-curated blueprint library embedded for retrieval, feeding the planner real-world reference structures for the requested archetype. Highest-impact next addition after this refactor lands.
- **Specialist sub-agents** — splitting `BlueprintPlanningAgent` into `FoundationAgent`, `WallsAgent`, `RoofAgent`, etc. with narrow prompts. Valuable once the monolithic planner's quality ceiling is measured.
- **Terrain integration** — reading the world blocks under the build anchor and adapting the foundation to slope/water/etc.
- **Interior furniture agent** — currently the DetailAgent only decorates exteriors; a separate pass for interiors (tables, chairs, beds, bookshelves).
- **Style-transfer mode** — user uploads a reference screenshot of a build they like and the system tries to match the style.

---

## Architectural Notes for the Implementing Agent

- **Coordinate systems**: blueprint primitives emit blueprint-local coords (origin at `0,0,0`). `PlacementAgent` applies the `state.placementOrigin` offset at placement time. Do not mix these two — it's a silent source of alignment bugs.
- **Determinism**: every compiler and patcher method must be deterministic. No `HashSet`, no `Math.random`, no `System.currentTimeMillis`. Tests and the render cache depend on this.
- **Keep `InterpretationAgent`, `PlacementAgent`, `BuildQueueManager`, `BuildJobManager` untouched**. They work. Everything new plugs in between them.
- **`@Deprecated` bridges**: while migrating, prefer leaving the old enum values with `@Deprecated` for one commit so the build is never red, then remove them in the next commit.
- **Commit granularity**: each of the 10 steps is roughly one commit. Steps 1-4 can each be their own commit; Step 7 should be broken into two (loop wiring, then debug/logging).
- **Follow the project-wide rule**: commit and push after every substep; pause for review after every major step.
