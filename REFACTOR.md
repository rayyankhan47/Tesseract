# Tesseract — Horizontal Multi-Agent Architecture Refactor

## Context

The current system makes a single monolithic LLM call via Gumloop's no-code pipeline. One call must simultaneously understand creative intent, decompose structure into components, and generate every block coordinate. This compresses quality: the token budget is consumed by coordinates, leaving almost nothing for architectural detail or structural reasoning.

The refactor replaces that single call with a five-agent horizontal pipeline (Interpretation → Planning → Generation → Critic → Placement), coordinated by an Orchestrator that owns a strict state machine and shared BuildState object. Gumloop is removed entirely. All LLM calls go directly to the Gemini API.

The input and output of the system are unchanged: player types a prompt, blocks appear in the world. Everything in between is being restructured.

---

## Current → New Architecture Mapping

| Existing Code | New Role |
|---|---|
| `GumloopClient.java` | Replaced by `Orchestrator` + 5 agent classes |
| `GumloopPayload.java` | Replaced by `BuildState`, `BuildSpec`, `ComponentPlan`, `BlockOp` |
| `GumloopProgressManager.java` | Adapted into `AgentProgressManager` (events from Orchestrator) |
| `BuildQueueManager.java` | Wrapped as `PlacementAgent` — logic mostly preserved |
| `BuildJobManager.java` | Preserved as-is (player lock / timeout) |
| `TesseractMod.java` `startBuild()` | `startBuild()` now delegates to `Orchestrator.run()` |
| `PlanPasteClient.java` | Preserved as-is (paste path bypasses agents) |
| `Selection`, `SelectionManager`, `SelectionNetworking`, `TesseractClient` | Completely unchanged |

---

## Progress Tracker

- [x] **Step 1** — Remove Gumloop, build Gemini client
- [x] **Step 2** — Define BuildState and event system
- [x] **Step 3** — Build the Orchestrator and state machine
- [x] **Step 4** — Build InterpretationAgent
- [ ] **Step 5** — Build PlanningAgent
- [ ] **Step 6** — Build GenerationAgent
- [ ] **Step 7** — Build CriticAgent
- [ ] **Step 8** — Extract PlacementAgent
- [ ] **Step 9** — Wire everything end-to-end and test
- [ ] **Step 10** — Add image reference support (multimodal)

---

## Step 1 — Remove Gumloop, Build Gemini Client

Remove all Gumloop infrastructure from both the Java mod and the Python web server, and replace it with a minimal direct Gemini REST client that all agents will share.

### 1.1 — Delete Gumloop files from the Java mod

- [x] Delete `src/main/java/com/rayyan/tesseract/gumloop/GumloopClient.java`
- [ ] Delete `src/main/java/com/rayyan/tesseract/gumloop/GumloopPayload.java` *(kept — still used by BuildQueueManager/PlanPasteClient; superseded in Step 2)*
- [x] Delete `src/main/java/com/rayyan/tesseract/gumloop/GumloopProgressManager.java`
- [x] Remove all `import com.rayyan.tesseract.gumloop.*` references from `TesseractMod.java` and `PlanPasteClient.java`
- [x] Remove all `GUMLOOP_WEBHOOK_URL` references from the Java codebase

### 1.2 — Create `GeminiClient.java`

Create `src/main/java/com/rayyan/tesseract/api/GeminiClient.java`:

- [x] Reads `GEMINI_API_KEY` from environment at construction time; throws `IllegalStateException` clearly if missing
- [x] Exposes method: `String complete(String systemPrompt, String userPrompt)` — makes a POST to `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent`, returns the raw text of the first candidate
- [x] Exposes overload: `String complete(String systemPrompt, String userPrompt, byte[] imageBytes, String mimeType)` — same call but adds an `inlineData` part to the `contents` array for multimodal requests; used by InterpretationAgent when a reference image is present
- [x] Uses the same `java.net.http.HttpClient` already in the codebase; Gson for JSON; 30-second timeout

### 1.3 — Rip Gumloop out of `web/server.py` and wire Gemini in

The Python web server (`web/server.py`) is the other place Gumloop lives. The web flow is: dashboard → `server.py` → Gumloop → plan JSON → POST to plan store at `localhost:4890/plans` → returns URL → dashboard shows URL → user runs `/tesseract paste <url>` in Minecraft.

- [x] Remove `GUMLOOP_WEBHOOK_URL`, `poll_gumloop_for_plan()`, and `find_plan()` from `server.py`
- [x] In `do_POST /generate`: replace the Gumloop HTTP call with a direct call to the Java mod's new embedded HTTP endpoint `POST http://localhost:4890/build` (added in Step 9), sending `{ "prompt": "...", "imageBase64": "...", "imageMimeType": "..." }` — the Java mod runs the full agent pipeline and returns `{ "url": "http://localhost:4890/plans/{id}" }`
- [x] Handle the image: the frontend already sends `images` as an array of `{ name, dataUrl }` objects where `dataUrl` is a base64 data URL; in `server.py`, extract the base64 payload by stripping the `data:image/...;base64,` prefix from `images[0].dataUrl` before forwarding
- [x] Add `GEMINI_API_KEY` to the environment variable documentation in `demoday.md`; remove `GUMLOOP_WEBHOOK_URL` from the same file
- [ ] Smoke-test: `curl -X POST http://localhost:5173/generate -d '{"prompt":"test"}'` should hit the Java mod endpoint and not crash (even if the mod isn't running yet, the error message should be "connection refused" not "GUMLOOP_WEBHOOK_URL not set")

---

## Step 2 — Define BuildState and the Event System

`BuildState` is the single shared context object. Every agent reads from it and writes to it. No agent calls another agent directly — all communication is through `BuildState`.

### 2.1 — Create `BuildState.java`

Create `src/main/java/com/rayyan/tesseract/agent/BuildState.java`:

- [x] Fields: `UUID playerId`, `String originalPrompt`, `byte[] referenceImageBytes` (nullable), `String referenceImageMimeType` (nullable), `BuildSpec spec` (set by InterpretationAgent), `List<ComponentPlan> componentPlan` (set by PlanningAgent), `int currentComponentIndex`, `List<BlockOp> completedOps` (accumulated), `List<String> failedComponentIds`, `BlockPos placementOrigin`, `Selection buildSelection`
- [x] Field `OrchestratorState state` — an enum: `IDLE, INTERPRETING, PLANNING, GENERATING, CRITIQUING, PLACING, COMPLETE, FAILED`
- [x] Field `List<BuildEvent> eventLog` — append-only log of everything that happened
- [x] All fields package-private; only the Orchestrator and agents mutate them; reads are freely accessible

### 2.2 — Create `BuildEvent.java` and `BuildSpec.java` and `ComponentPlan.java`

- [x] `BuildEvent.java`: a record with fields `long timestamp`, `String agentName`, `String message` — used for both logging and forwarding to the player UI
- [x] `BuildSpec.java`: mirrors the InterpretationAgent output schema — fields `String style`, `String type`, `int width`, `int height`, `int depth`, `List<String> materials`, `List<String> features`, `String rawJson`
- [x] `ComponentPlan.java`: mirrors the PlanningAgent output schema — fields `String id`, `String name`, `String description`, `List<String> buildAfter`, `int originX`, `int originY`, `int originZ`, `int sizeX`, `int sizeY`, `int sizeZ` (origin/size filled in by GenerationAgent based on dependency layout)

### 2.3 — Thread safety

- [x] `BuildState` is only ever mutated on the Minecraft server thread (all `whenComplete` callbacks already call `player.getServer().execute(() -> ...)` — preserve this pattern)
- [x] `eventLog` is the one field that may be read from multiple threads for logging; make it a `CopyOnWriteArrayList`

---

## Step 3 — Build the Orchestrator and State Machine

The Orchestrator owns the pipeline. It drives the state machine, invokes agents in order, and forwards events to the player.

### 3.1 — Define `OrchestratorState` and legal transitions

Create `src/main/java/com/rayyan/tesseract/agent/OrchestratorState.java`:

- [x] Enum values: `IDLE, INTERPRETING, PLANNING, GENERATING, CRITIQUING, PLACING, COMPLETE, FAILED`
- [x] Static method `assertTransition(OrchestratorState from, OrchestratorState to)` — throws `IllegalStateException` with a descriptive message if the transition is not in the allowed set: `IDLE→INTERPRETING`, `INTERPRETING→PLANNING`, `PLANNING→GENERATING`, `GENERATING→CRITIQUING`, `CRITIQUING→PLACING`, `CRITIQUING→GENERATING` (retry), `PLACING→GENERATING` (next component), `PLACING→COMPLETE`, `any→FAILED`

### 3.2 — Create `Orchestrator.java`

Create `src/main/java/com/rayyan/tesseract/agent/Orchestrator.java`:

- [x] Entry point: `void run(ServerPlayerEntity player, Selection selection, String prompt)` — creates a fresh `BuildState`, stores it keyed by `player.getUuid()` in a `ConcurrentHashMap`, then calls `transition(state, INTERPRETING)`
- [x] `void transition(BuildState state, OrchestratorState next)` — calls `OrchestratorState.assertTransition(state.state, next)`, sets `state.state = next`, emits a `BuildEvent`, then dispatches to the correct agent method
- [x] Each agent invocation is async (`CompletableFuture`), and on completion calls `transition(...)` for the next state — this keeps the chain non-blocking exactly like the existing `whenComplete` pattern
- [x] Public method `void cancelBuild(UUID playerId)` — sets state to `FAILED`, emits an event, cleans up the map entry

### 3.3 — Event forwarding

- [x] `void emit(BuildState state, String agentName, String message)` — appends a `BuildEvent` to `state.eventLog`, then if `player` is still online, calls `player.sendMessage(Text.of("[" + agentName + "] " + message), false)`
- [x] Replace `GumloopProgressManager` with `AgentProgressManager` that shows the boss bar during `INTERPRETING`, `PLANNING`, and `GENERATING` states and hides it on `COMPLETE` or `FAILED` — same triangle-wave animation, same purple bar

---

## Step 4 — Build InterpretationAgent

Converts a raw natural language prompt into a structured spec. The only job of this agent is to understand creative intent. It knows nothing about block coordinates.

### 4.1 — Define the output schema and prompt

- [x] Output schema (the exact JSON this agent must return):
  ```json
  {
    "style": "gothic",
    "type": "gate",
    "width": 8,
    "height": 14,
    "depth": 4,
    "materials": ["stone_bricks", "cobblestone", "oak_fence"],
    "features": ["twin_towers", "central_arch", "crenellations", "torches"]
  }
  ```
- [x] System prompt instructs the model: you are an architectural interpreter for a Minecraft builder; output only the JSON object above, no prose, no markdown fences; be specific about features and materials; use only Minecraft block name fragments (not full IDs) for materials
- [x] If a reference image is attached, the system prompt adds: "A reference image is attached. Use it to inform the style, materials, and features fields."

### 4.2 — Create `InterpretationAgent.java`

Create `src/main/java/com/rayyan/tesseract/agent/InterpretationAgent.java`:

- [x] Method: `void run(BuildState state, GeminiClient gemini, Runnable onComplete, Consumer<String> onError)` — calls `gemini.complete(systemPrompt, state.originalPrompt)` (or the multimodal overload if `state.referenceImageBytes != null`)
- [x] On success: parses the JSON response into `BuildSpec`, sets `state.spec`, emits a `BuildEvent` with a human-readable summary (e.g. "Interpreted: gothic gate, 8×14, features: twin_towers, arch, crenellations"), calls `onComplete`
- [x] On parse failure: calls `onError("InterpretationAgent failed to parse spec: " + rawResponse.substring(0, 120))`

### 4.3 — Connect to Orchestrator

- [x] In `Orchestrator.transition(state, INTERPRETING)`, call `InterpretationAgent.run(state, gemini, () -> transition(state, PLANNING), err -> failBuild(state, err))`

---

## Step 5 — Build PlanningAgent

Takes the spec from the InterpretationAgent and decomposes it into an ordered list of named components with spatial relationships. This agent knows nothing about block coordinates — only spatial structure and build order.

### 5.1 — Define the output schema and prompt

- [x] Output schema:
  ```json
  [
    { "id": "comp_1", "name": "foundation", "description": "flat stone base, full footprint, 1 block tall", "build_after": [] },
    { "id": "comp_2", "name": "left_tower", "description": "3×3 footprint, 12 blocks tall, stone_brick walls, crenellations on top", "build_after": ["comp_1"] },
    { "id": "comp_3", "name": "right_tower", "description": "mirror of left_tower", "build_after": ["comp_1"] },
    { "id": "comp_4", "name": "central_arch", "description": "6 blocks wide, 10 blocks tall pointed arch in cobblestone, spans between towers", "build_after": ["comp_2", "comp_3"] }
  ]
  ```
- [x] System prompt instructs the model: decompose the spec into the minimum set of named components needed; order them so dependencies are always built before dependents; write descriptions that are precise enough for a block-level generator to work from; total block count across all components should not exceed the `maxBlocks` limit (pass this in context)
- [x] Pass the full `BuildSpec` JSON as part of the user prompt, plus the bounding box dimensions from `state.buildSelection`

### 5.2 — Create `PlanningAgent.java`

Create `src/main/java/com/rayyan/tesseract/agent/PlanningAgent.java`:

- [x] Method: `void run(BuildState state, GeminiClient gemini, Runnable onComplete, Consumer<String> onError)`
- [x] Calls `gemini.complete(systemPrompt, specJson + "\n\nBounding box: " + w + "×" + h + "×" + d)`
- [x] On success: parses JSON array into `List<ComponentPlan>`, computes a simple left-to-right spatial layout to assign `originX/Y/Z` to each component (basic stacking — the GenerationAgent will use these as starting hints), sets `state.componentPlan`, emits an event listing the component names in order, calls `onComplete`
- [x] On parse failure: calls `onError`

### 5.3 — Connect to Orchestrator

- [ ] In `Orchestrator.transition(state, PLANNING)`, call `PlanningAgent.run(state, gemini, () -> { state.currentComponentIndex = 0; transition(state, GENERATING); }, err -> failBuild(state, err))`

---

## Step 6 — Build GenerationAgent

Iterates through the component list one at a time. For each component, makes one focused LLM call with a narrow, specific prompt. The entire context window is devoted to one component.

### 6.1 — Implement the iteration loop and retry logic

- [ ] The agent is invoked from the Orchestrator for the component at `state.currentComponentIndex`
- [ ] It tracks a per-component retry count (stored on `ComponentPlan` or as a local field passed through the callback chain); max 3 retries
- [ ] On CriticAgent failure: increment retry, adjust the user prompt to append the critic's failure reason (e.g. "Previous attempt failed: floating blocks detected at y=3. Ensure every block at y>0 has a solid block below it."), re-invoke the LLM call
- [ ] On 3 consecutive failures: add component id to `state.failedComponentIds`, emit a warning event, advance `state.currentComponentIndex` and continue to the next component rather than aborting the entire build

### 6.2 — Define the per-component prompt and output schema

- [ ] Output schema: a flat JSON array of block ops, coordinates relative to the component's origin:
  ```json
  [
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone_bricks" },
    { "x": 1, "y": 0, "z": 0, "block": "minecraft:stone_bricks" }
  ]
  ```
- [ ] User prompt template: `"Component: {name}\nDescription: {description}\nOrigin in build: ({originX}, {originY}, {originZ})\nAvailable materials: {materials}\nMax blocks for this component: {budget}\nOther component bounding boxes (do not place blocks inside these): [{...}]\nReturn only the JSON array of block placements. Coordinates are relative to this component's origin."`
- [ ] System prompt: you are a Minecraft block-level generator; return only a JSON array; use only block IDs from the provided materials list; every block must be at a valid coordinate relative to the component origin

### 6.3 — Create `GenerationAgent.java`

Create `src/main/java/com/rayyan/tesseract/agent/GenerationAgent.java`:

- [ ] Method: `void runComponent(BuildState state, GeminiClient gemini, int retryAttempt, String priorFailureReason, Runnable onCriticPass, BiConsumer<String, Boolean> onCriticFail)` — `onCriticFail` receives the failure reason and a boolean `shouldRetry`
- [ ] Builds prompt from `state.componentPlan.get(state.currentComponentIndex)` and `state.spec.materials`
- [ ] Parses the response into `List<BlockOp>`; on JSON parse failure, calls `onCriticFail("Malformed JSON from LLM", retryAttempt < 3)`
- [ ] On success: calls into `CriticAgent.validate(...)`, then calls `onCriticPass` or `onCriticFail` accordingly

---

## Step 7 — Build CriticAgent

Programmatic validation only — no LLM involved. Validates a single component's block array before any blocks are placed in the world.

### 7.1 — JSON schema and block ID validation

Create `src/main/java/com/rayyan/tesseract/agent/CriticAgent.java`:

- [ ] Method: `CriticResult validate(List<BlockOp> ops, ComponentPlan component, List<String> palette)` — returns a `CriticResult` with a boolean `passed` and a `String failureReason`
- [ ] Check 1 (null/empty): if `ops` is null or empty, return failure "component generated zero blocks"
- [ ] Check 2 (palette): for each op, verify `op.block` is in the allowed palette (`defaultPalette()` — the existing 17-block list); collect all violations; if any, return failure listing the offending block IDs
- [ ] Check 3 (bounds): for each op, verify `0 <= op.x < component.sizeX`, `0 <= op.y < component.sizeY`, `0 <= op.z < component.sizeZ`; if any out-of-bounds, return failure with the offending coordinate

### 7.2 — Floating block detection

- [ ] Check 4 (structural support): for any block where `op.y > 0`, verify there exists another block in the array at `(op.x, op.y - 1, op.z)` OR the op's y-coordinate sits on the ground level of the build (i.e., `state.placementOrigin.y + component.originY + op.y == worldGroundLevel`)
- [ ] A block is exempt from this check if it is `minecraft:torch`, `minecraft:lantern`, or any `_slab`, `_stairs`, `_fence`, `_trapdoor` — these are decorative and may be intentionally attached to walls
- [ ] Collect all floating violations; if count > 10% of total ops, return failure "too many floating blocks ({n}/{total})"

### 7.3 — Max block count check

- [ ] Check 5 (budget): if `ops.size()` exceeds the per-component budget (total `maxBlocks` divided by number of components), return a warning (non-fatal) in the event log but do not fail — just truncate `ops` to the budget and log which blocks were dropped

---

## Step 8 — Extract PlacementAgent

Wrap the existing block placement logic from `BuildQueueManager` into a `PlacementAgent`. This is the part of the existing system that should change the least.

### 8.1 — Create `PlacementAgent.java`

Create `src/main/java/com/rayyan/tesseract/agent/PlacementAgent.java`:

- [ ] Static method: `void placeComponent(BuildState state, ServerWorld world, List<BlockOp> ops, ComponentPlan component, Runnable onComplete, Consumer<String> onError)` — translates each op's relative coordinates to world coordinates using `state.placementOrigin + component.originX/Y/Z + op.x/y/z`, then places blocks
- [ ] Copy the `toBlockState(String blockId)` helper from `BuildQueueManager` exactly as-is
- [ ] Copy the chunk-loaded check from `BuildQueueManager` exactly as-is
- [ ] Appends placed ops to `state.completedOps` so the full build accumulates across components

### 8.2 — Throttled vs instant placement

- [ ] Keep `BuildQueueManager`'s 20-blocks-per-tick throttled placement for the normal build path — this gives the animated feel
- [ ] Keep the instant placement path (`startInstantBuild`) for the paste command — `PlanPasteClient` is unchanged
- [ ] The `PlacementAgent` queues its ops into `BuildQueueManager` the same way the old code did; the only difference is it's invoked component-by-component rather than with the entire plan at once

### 8.3 — Progress events per component

- [ ] After each component is fully placed, emit a `BuildEvent`: "Placed component '{name}' ({n} blocks)"
- [ ] After all components are placed, call `Orchestrator.transition(state, COMPLETE)` which emits "Build complete: {total} blocks across {n} components. ({failedCount} components skipped.)" and removes the `BuildJobManager` lock

---

## Step 9 — Wire Everything End-to-End

Connect the Orchestrator into the existing entry point, add the embedded HTTP server so the web dashboard can reach the Java mod, and verify the full pipeline works on both the in-game chat path and the web dashboard path.

### 9.1 — Replace `startBuild` in `TesseractMod.java`

- [ ] In `TesseractMod.startBuild()`, replace the call to `GumloopClient.sendBuildRequest(...)` with `Orchestrator.getInstance().run(player, selection, contextSelection, prompt, null, null)` (null image bytes = text-only)
- [ ] `Orchestrator` is a singleton initialized once in `TesseractMod.onInitialize()` with a shared `GeminiClient` instance
- [ ] `BuildJobManager.start(player.getUuid())` is still called before invoking the Orchestrator — the lock remains the player-facing guard

### 9.2 — Add embedded HTTP server for the web dashboard path

The web dashboard → `server.py` needs to reach the Java mod to trigger a build without an in-game player command. Add a minimal embedded HTTP server inside the mod on port 4890 (the same port the old plan store used, so `server.py` needs no URL change):

- [ ] In `TesseractMod.onInitialize()`, start a `com.sun.net.httpserver.HttpServer` on port **4891** (available in JDK 17, no extra deps). Port 4890 is already taken by `tools/plan_server.py` (the plan store) and must not be disturbed.
- [ ] Register handler `POST /build`: reads `{ "prompt", "imageBase64"?, "imageMimeType"? }` from the request body; creates a synthetic "web build" using a default 16×12×16 selection (no real player context); runs the Orchestrator pipeline; when complete, returns the raw plan JSON `{ "meta": {...}, "ops": [...] }` — `web/server.py` receives this, then posts it to `tools/plan_server.py` on port 4890 to get the shareable URL
- [ ] The web build path does not require a logged-in player; `BuildJobManager` lock is keyed by a synthetic `UUID.nameUUIDFromBytes("web".getBytes())` to prevent concurrent web builds

### 9.3 — Register Orchestrator tick and verify the visible log output

- [ ] Add `Orchestrator.tick()` to the `ServerTickEvents.END_SERVER_TICK` handler alongside the existing `BuildJobManager.tick()` and `BuildQueueManager.tick()` calls; `Orchestrator.tick()` cleans up builds that have been stuck in any state for > 5 minutes
- [ ] Run a test build in-game: `/tesseract build small wooden house` and verify in the server log and player chat that you can see each agent's events in sequence:
  - `[Interpretation] Interpreted: cottage, 8×8, style=rustic, features=[door, windows, chimney]`
  - `[Planning] Plan: foundation → walls → roof → chimney → windows → door (6 components)`
  - `[Generation] Generating component 1/6: foundation`
  - `[Critic] Component 1 passed (64 blocks)`
  - `[Placement] Placed component 'foundation' (64 blocks)` — and so on
- [ ] Run a test build via the web dashboard: open `http://localhost:5173`, type a prompt, click Generate, verify the URL appears, then run `/tesseract paste <url>` in Minecraft and verify blocks are placed

---

## Step 10 — Wire Image Reference Support End-to-End

The web frontend already has image upload built — `app.js` reads attached files as base64 data URLs and already sends `{ prompt, images: [{name, dataUrl}] }` to `/generate`. The `server.py` already passes `images` in the Gumloop payload (Gumloop just ignored them). This step connects the already-present image data to the Gemini multimodal API through the InterpretationAgent.

### 10.1 — Pass image from `server.py` to the Java mod's `/build` endpoint

- [ ] In `server.py`'s `do_POST /generate` handler (updated in Step 1.3), if `images` is non-empty, extract the first image: strip the `data:image/png;base64,` prefix from `images[0]["dataUrl"]` to get the raw base64 string, and read `images[0]["name"]` to infer the mime type (`.png` → `image/png`, `.jpg`/`.jpeg` → `image/jpeg`)
- [ ] Include `"imageBase64"` and `"imageMimeType"` in the body forwarded to `POST localhost:4890/build`; if no image is attached, omit these fields (the Java side already handles nullable)
- [ ] The Java embedded HTTP server's `/build` handler (Step 9.2) decodes the base64 string to `byte[]` using `Base64.getDecoder().decode(imageBase64)` and passes it into `Orchestrator.run()`

### 10.2 — Wire multimodal call into `InterpretationAgent`

- [ ] `BuildState.referenceImageBytes` and `BuildState.referenceImageMimeType` are already defined (Step 2.1); `Orchestrator.run()` already accepts them (Step 9.1)
- [ ] In `InterpretationAgent.run()`, check `state.referenceImageBytes != null`; if true, call `gemini.complete(systemPrompt, userPrompt, state.referenceImageBytes, state.referenceImageMimeType)` instead of the text-only overload
- [ ] `GeminiClient`'s multimodal overload constructs the request body with a `contents` array that has two `parts`: `{ "text": userPrompt }` and `{ "inlineData": { "mimeType": mimeType, "data": base64String } }`
- [ ] Emit a `BuildEvent` that notes the image was used: "[Interpretation] Interpreted with visual reference: gothic gate, 8×14..."

### 10.3 — Test and polish the web image flow

- [ ] End-to-end test: open the dashboard, attach a screenshot of a Minecraft build you like (e.g. a cathedral or castle), type "build this", click Generate, verify the interpreted spec in the Java log reflects visual features from the image (arches, towers, stone materials) rather than generic defaults
- [ ] Add a small status indicator to the dashboard's status panel that says "Reference image attached" when an image is present — one-liner change to `app.js`'s `startStatusSequence()` function

---

## New File Tree After Refactor

```
src/main/java/com/rayyan/tesseract/
  TesseractMod.java          (modified: startBuild delegates to Orchestrator)
  TesseractClient.java       (unchanged)
  agent/
    BuildState.java          (new — shared context object)
    BuildEvent.java          (new — event record)
    BuildSpec.java           (new — InterpretationAgent output model)
    ComponentPlan.java       (new — PlanningAgent output model)
    OrchestratorState.java   (new — state enum + transition guard)
    Orchestrator.java        (new — state machine + event forwarding)
    InterpretationAgent.java (new — stage 1 LLM call)
    PlanningAgent.java       (new — stage 2 LLM call)
    GenerationAgent.java     (new — stage 3 LLM call, per component)
    CriticAgent.java         (new — stage 4 programmatic validation)
    PlacementAgent.java      (new — stage 5 wrapper over existing placement)
    AgentProgressManager.java(new — boss bar, replaces GumloopProgressManager)
  api/
    GeminiClient.java        (new — direct Gemini REST client)
  jobs/
    BuildJobManager.java     (unchanged)
    BuildQueueManager.java   (mostly unchanged — used inside PlacementAgent)
  network/
    SelectionNetworking.java (unchanged)
  paste/
    PlanPasteClient.java     (unchanged)
  selection/
    Selection.java           (unchanged)
    SelectionManager.java    (unchanged)
```

---

## Environment Variables After Refactor

| Variable | Description |
|---|---|
| `GEMINI_API_KEY` | Required. Gemini API key for all LLM calls. |
| `GUMLOOP_WEBHOOK_URL` | Removed. No longer used. |

---

## Notes for Demo Day

- The `/tesseract demo cabin` and `/tesseract demo gate` commands are preserved and will now run through the full five-stage pipeline — useful for showing the event log during a live demo
- `/tesseract paste <url>` continues to work as-is (bypasses all agents, places pre-built JSON instantly) — useful as a fallback if the API is slow
- The state machine's event stream means every stage is visible in the player's chat in real time — make sure this is prominent during the interview demo
