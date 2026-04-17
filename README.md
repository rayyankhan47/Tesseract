# Tesseract

A Fabric mod for Minecraft 1.18.2. Describe a structure in plain English and it gets built block by block in the world.

```
/tesseract build "gothic stone gate with twin towers"
```

## Architecture

A player prompt goes through a multi-agent pipeline backed by Gemini. Each agent has one job. All share a `BuildState` object. No agent calls another directly. An `Orchestrator` singleton drives a strict state machine and handles all sequencing and error routing.

```
InterpretationAgent    prompt -> BuildSpec (style, dimensions, materials, features)
BlueprintPlanningAgent BuildSpec -> Blueprint DSL (semantic primitives: walls, roof, columns...)
BlueprintCompiler      Blueprint -> List<BlockOp>  [deterministic, no LLM]
IsoRenderer            List<BlockOp> -> PNG         [deterministic, no LLM]
VisualCriticAgent      PNG + Blueprint -> Critique + patch list
BlueprintPatcher       applies patches -> revised Blueprint -> loop back to Compiler (up to 3x)
DetailAgent            adds torches, trim, decoration ops
PlacementAgent         places blocks at 20/tick via BuildQueueManager
```

The key design decision is that the LLM never emits block coordinates directly. It emits a **Blueprint DSL** — structured JSON describing a building as semantic primitives (`platform`, `walls`, `gable_roof`, `column`, `arch`, etc.). A deterministic Java compiler expands those into exact block ops. Spatial coherence is enforced by the compiler, not the model.

The **vision critic loop** renders the compiled build as an isometric PNG before any blocks are placed in the world, sends it to Gemini Vision, and iterates on the blueprint based on the critique. Nothing touches the world until the loop converges or hits the 3-iteration cap.

State machine:

```
IDLE -> INTERPRETING -> BLUEPRINTING -> COMPILING -> RENDERING -> CRITIQUING_VISUAL
                                             ^                            |
                                             |______ PATCHING <__________|
                                                     (if !satisfied)
                                                         |
                                                    DETAILING -> PLACING -> COMPLETE
```

All LLM calls are async (`CompletableFuture`). Callbacks re-enter the Minecraft server thread via `server.execute()` before touching world state.

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

To dump isometric renders to disk for debugging:

```bash
./gradlew runClient -Dtesseract.debug.renders=true
# renders saved to run/tesseract_debug/
```

## Usage

**In-game build:**
1. Punch the ground with a wooden axe to set the build anchor.
2. Run `/tesseract build <prompt>`.
3. Agent progress is logged to in-game chat in real time.

**Web dashboard:**
1. Open `http://localhost:5173`, enter a prompt, optionally attach a reference image.
2. Click Generate. Copy the returned URL.
3. Run `/tesseract paste <url>` in-game with a region selected.

**Shortcuts:** `/tesseract demo cabin` and `/tesseract demo gate` run preset prompts.

## Project structure

```
src/main/java/com/rayyan/tesseract/
  agent/       Orchestrator, state machine, all agents, BuildState
  blueprint/   Blueprint DSL data classes, parser, compiler, patcher, palette utils
  render/      IsoRenderer, BlockColorPalette
  api/         GeminiClient (text + multimodal)
  jobs/        BuildJobManager, BuildQueueManager (throttled placement)
  paste/       PlanPasteClient
  selection/   Region selection (wooden axe / golden axe)
  TesseractMod.java
tools/
  plan_server.py
web/
  server.py / index.html / app.js / styles.css
```

## Ports

| Port | Service |
|------|---------|
| 4890 | plan store |
| 4891 | mod HTTP endpoint (`POST /build`) |
| 5173 | web dashboard |

## Constraints

- Minecraft 1.18.2 only
- One concurrent build per player, one concurrent web build
- Vision critic loop: max 3 iterations, 90-second wall-clock budget
