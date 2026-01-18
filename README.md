# Tesseract

Agentic automation for Minecraft. Select a region, describe what you want, and watch AI-generated builds appear in your world.

## Quick Setup

Tesseract requires three services running: the Minecraft mod, a plan registry server, and a web UI server.

### Prerequisites

- Minecraft Java Edition 1.18.2
- Java 17 (for Minecraft)
- Python 3.8+ (for servers)
- Gradle (included via wrapper)

### Environment Setup

Before running any service, set these environment variables:

```bash
export GUMLOOP_WEBHOOK_URL="YOUR_GUMLOOP_WEBHOOK_URL"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

To make these persistent, add them to your `~/.zshrc` or `~/.bashrc`, then run `source ~/.zshrc`.

### Running the Services

You need three terminal windows:

**Terminal 1: Plan Registry Server**
```bash
cd /path/to/tesseract
python3 tools/plan_server.py
```
This runs on `http://localhost:4890` and stores build plans for the paste workflow.

**Terminal 2: Web UI Server**
```bash
cd /path/to/tesseract/web
export GUMLOOP_WEBHOOK_URL="YOUR_GUMLOOP_WEBHOOK_URL"
export GUMLOOP_SKIP_SSL_VERIFY=1
python3 server.py
```
This runs on `http://localhost:5173`. Open this URL in your browser to use the web interface.

**Terminal 3: Minecraft Mod**
```bash
cd /path/to/tesseract
export GUMLOOP_WEBHOOK_URL="YOUR_GUMLOOP_WEBHOOK_URL"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew runClient
```

For detailed demo day commands, see `Demoday.md`.

## What is Tesseract?

Tesseract brings AI-powered build generation to Minecraft. Instead of placing blocks manually, you describe what you want in natural language, and the mod generates and places the structure automatically.

The name comes from the idea of adding a fourth dimension to Minecraft's 3D canvas: the dimension of AI automation.

## Features

- **Natural Language Building**: Describe structures in plain English ("a cozy oak cabin with a peaked roof")
- **Progressive Build Execution**: Watch structures materialize block by block in real time
- **Web Interface**: Generate builds through a clean web UI, then paste them into Minecraft with a single command
- **Context-Aware Generation**: Provide reference structures to guide the AI's style
- **Bounded and Safe**: All builds are constrained to a whitelist of blocks and size limits
- **Validation Pipeline**: Multi-stage validation ensures safe, correct builds before execution

## How It Works

### In-Game Workflow

1. **Select a region**: Use a wooden axe to click two corners defining your build area
2. **Optional context**: Use a golden axe to select an existing structure for style reference
3. **Generate**: Run `/tesseract build <prompt>` or use the web interface
4. **Watch**: The structure appears progressively as blocks are placed

### Web Interface Workflow

1. **Open the web UI**: Navigate to `http://localhost:5173`
2. **Enter a prompt**: Describe the structure you want to build
3. **Optional image upload**: Attach reference images for context
4. **Generate**: Click "Generate build" and wait for the process to complete
5. **Paste into Minecraft**: Copy the paste URL, select a region in-game, and run `/tesseract paste <url>`

### Technical Pipeline

When you request a build:

1. **Request Construction**: Your prompt, region size, and optional context are sent to Gumloop (an AI orchestration platform)
2. **AI Generation**: Gumloop uses an LLM to generate a structured build plan in JSON format
3. **Validation**: The plan is validated against constraints (bounds, block whitelist, max block count)
4. **Storage**: For web-generated builds, the validated plan is stored in the local plan registry
5. **Execution**: The mod retrieves the plan (directly or via paste URL) and places blocks progressively

## Architecture

Tesseract consists of three main components:

- **Minecraft Mod (Fabric)**: Handles in-game interactions, region selection, block placement, and command processing
- **Plan Registry Server**: Stores validated build plans and serves them via HTTP (local-only)
- **Web UI Server**: Provides a web interface for build generation, proxies requests to Gumloop, and stores results in the plan registry

### Build Plan Format

All builds use a consistent JSON schema:

```json
{
  "meta": {
    "theme": "description of the build",
    "blockCount": 150,
    "warnings": []
  },
  "ops": [
    {"x": 0, "y": 0, "z": 0, "block": "minecraft:oak_planks"},
    {"x": 1, "y": 0, "z": 0, "block": "minecraft:oak_log"}
  ]
}
```

Coordinates are relative to the build origin (minimum corner of the selected region).

### Block Whitelist

Tesseract uses a curated palette of blocks for safety and reliability:

- **Structural**: oak_log, oak_planks, cobblestone, stone_bricks
- **Stairs/Slabs**: oak_stairs, cobblestone_stairs, stone_brick_stairs, oak_slab, cobblestone_slab, stone_brick_slab
- **Fences/Walls**: oak_fence, cobblestone_wall
- **Doors/Trapdoors**: oak_door, oak_trapdoor
- **Lighting**: torch, lantern
- **Windows**: glass

This whitelist ensures builds use familiar, safe blocks and prevents potentially problematic items.

## Development

### Building the Mod

```bash
./gradlew build
```

The compiled mod JAR will be in `build/libs/`.

### Running in Development

```bash
./gradlew runClient
```

This launches Minecraft with the mod loaded. Ensure `JAVA_HOME` is set to Java 17.

### Project Structure

- `src/main/java/com/rayyan/tesseract/`: Mod source code
  - `gumloop/`: Gumloop API client and payload definitions
  - `jobs/`: Build job management and progressive execution
  - `paste/`: Paste URL fetching and validation
  - `selection/`: Region selection logic
- `tools/plan_server.py`: Plan registry server
- `web/`: Web UI (HTML, CSS, JavaScript) and server
- `Demoday.md`: Quick reference commands for demo day

## Constraints and Limitations

- **Minecraft Version**: 1.18.2 only
- **Region Size**: Maximum 32x32x32 blocks
- **Block Count**: Maximum 600 blocks per build
- **Local-Only**: Plan registry and web server run locally (not suitable for remote multiplayer without port forwarding)
- **Whitelist-Only**: Only blocks in the curated palette are used

## License

See `LICENSE` for details.

## Acknowledgments

Built with Fabric modding framework and powered by Gumloop for AI orchestration.
