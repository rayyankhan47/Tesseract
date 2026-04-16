# Tesseract

A Fabric mod for Minecraft 1.18.2. Select a region, describe what you want in plain English, and the structure appears block by block.

## How it works

A player types `/tesseract build <prompt>`. The mod runs a five-stage agent pipeline backed by Gemini 2.0 Flash:

1. **InterpretationAgent** — reads the prompt (and optional reference image) and outputs a structured spec: style, type, dimensions, materials, features.
2. **PlanningAgent** — decomposes the spec into an ordered list of named components with dependency relationships.
3. **GenerationAgent** — calls Gemini once per component with a focused prompt, producing a flat array of block coordinates relative to that component's origin. Retries up to 3 times if the critic rejects the output.
4. **CriticAgent** — validates each component: checks for empty output, palette violations, out-of-bounds coordinates, floating blocks (>10% threshold), and budget overrun. No LLM involved.
5. **PlacementAgent** — translates component-relative coordinates to world coordinates and places blocks at 20 per tick using the existing throttled queue.

All five agents share a `BuildState` object. No agent calls another directly. An `Orchestrator` singleton owns the state machine (`IDLE -> INTERPRETING -> PLANNING -> GENERATING -> CRITIQUING -> PLACING -> COMPLETE`) and all transitions. All LLM calls are async (`CompletableFuture`) so the server thread is never blocked.

There is also a web path: an embedded HTTP server on port 4891 accepts `POST /build` from the web dashboard, runs the same pipeline without a live player, and returns the plan JSON for storage in the plan registry.

## Setup

**Prerequisites:** Minecraft Java Edition 1.18.2, Java 17, Python 3.8+.

**1. Add your Gemini API key.**

Create a `.env` file in the project root (same folder as `build.gradle`):

```
GEMINI_API_KEY=your_key_here
```

**2. Start three services** (three terminals):

```bash
# Terminal 1: plan store (port 4890)
python3 tools/plan_server.py

# Terminal 2: web dashboard (port 5173)
cd web && python3 server.py

# Terminal 3: Minecraft mod
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE_USER_HOME=/path/to/.gradle-jdk17 ./gradlew runClient
```

## In-game usage

**Build path:**
1. Right-click two corners with a wooden axe to define the build region.
2. Run `/tesseract build <prompt>`.
3. Watch the agents log each stage to your chat in real time.

**Paste path (web dashboard):**
1. Open `http://localhost:5173`, enter a prompt, optionally attach a reference image.
2. Click Generate. Copy the URL it returns.
3. Select a region in-game, run `/tesseract paste <url>`.

**Demo shortcuts:** `/tesseract demo cabin` and `/tesseract demo gate` run preset prompts through the full pipeline.

## Ports

| Port | Service |
|------|---------|
| 4890 | `tools/plan_server.py` — plan store |
| 4891 | Java mod — agent pipeline (`POST /build`) |
| 5173 | `web/server.py` — dashboard UI |

## Project structure

```
src/main/java/com/rayyan/tesseract/
  agent/          Orchestrator, state machine, all five agents, shared data model
  api/            GeminiClient (direct Gemini REST, text + multimodal)
  jobs/           BuildJobManager, BuildQueueManager (throttled placement)
  paste/          PlanPasteClient (paste command)
  selection/      Region selection (wooden axe / golden axe)
  TesseractMod.java
tools/
  plan_server.py  Stores plans, returns shareable URLs
web/
  server.py       Dashboard backend, bridges browser to Java mod
  index.html / app.js / styles.css
```

## Constraints

- Minecraft 1.18.2 only
- Max region size: 32x32x32
- Block palette: 17 curated block types (stone, oak, bricks, glass, torches, etc.)
- One concurrent web build at a time
