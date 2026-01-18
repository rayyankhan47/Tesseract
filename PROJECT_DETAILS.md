# Tesseract — Project Details (A→Z Spec)

This document is the **single source of truth** for the hackathon MVP of **Tesseract: Cursor for Minecraft**.

If anything in conversation becomes ambiguous later, **treat this file as canonical** and update it when decisions change.

---

## ⚠️ SETUP REQUIRED: Environment Variables

**BEFORE running the mod, you MUST set these environment variables:**

```bash
export GUMLOOP_WEBHOOK_URL="https://api.gumloop.com/api/v1/start_pipeline?api_key=50d96e74be0849609c62088c5102da28&user_id=TM6pLxGuKRd6jcUX2QlrXKlIx9K2&saved_item_id=1SMP5SVWn2pp7WKAekNqHH"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

**To make it persistent**, add the above lines to your `~/.zshrc` file, then run `source ~/.zshrc`.

**Then run:** `./gradlew runClient`

---

## 0. Elevator Pitch

**Tesseract is Cursor for Minecraft**: you select a build region in-world, type what you want in chat, and an AI generates a safe, bounded build plan that the mod executes progressively so the structure “grows” in real time.

Key idea: Minecraft is a 3D canvas; we add an “AI dimension” (hence **Tesseract**).

---

## 1. Hackathon Constraints & Stack

- **Minecraft**: Java Edition **1.18.2**
- **Modding**: **Fabric**
- **Build mode**: **Creative mode**, flat world/plane (assume mostly flat terrain)
- **AI orchestration**: **Gumloop** (bundled agentic AI available; credits + sponsor prize)
- **Execution model**: AI builds **directly in the world** (no schematic paste requirement for MVP)

---

## 2. MVP Goals (What “Done” Means)

The MVP is successful if it reliably demos this loop **2–3 times in a row**:

1. Player selects a region (two corners).
2. Player sees a **filled translucent ground tint** overlay showing the selected area.
3. Player runs `/tesseract build <prompt>`.
4. Mod sends `{prompt, region, whitelist, maxBlocks}` to Gumloop.
5. Gumloop returns a **validated JSON** build plan.
6. Mod re-validates and **progressively places blocks** to visualize the build “growing”.

Reliability > complexity. The wow factor comes from the **tight UX** and **visual execution**.

---

## 3. Non-Goals (Explicitly Out of Scope for MVP)

- **Complex redstone** logic (repeaters, comparators, pistons, timing circuits).
  - If we allow any redstone blocks at all, it is **decorative-only** (no promises it functions).
- Large builds that risk lag or long generation time.
- Perfect architectural correctness; we only need a compelling demo structure.
- Build modification/editing of existing structures (we will focus on **generation** + **context-aware generation**).

---

## 4. Region Selection UX

### 4.1 Selection mechanism

- Use two selection tools:
  - **Build wand**: selects the region where we will generate/modify.
  - **Context wand**: selects an optional “style context” region (an existing build to imitate).
- Each wand sets **Corner A** and **Corner B** (two opposite corners of a cuboid).
- Store selections **per player** (so multiple players would not conflict, even though MVP demo is single-user).

### 4.2 Visual overlay requirements

After both corners are set (and before building starts):

- Show a **filled translucent ground tint** over the selected ground area.
- Also optionally show an outline of the cuboid (nice-to-have), but the ground tint is the MVP requirement.

### 4.3 Edge cases

- If the player has not selected both corners, running `/tesseract build ...` must return:
  - `Error: you haven't selected a region yet. Select two corners first.`
- If the region exceeds max size, error with a clear message (see constraints below).

---

## 5. Safety Constraints (Hard Limits)

These constraints prevent griefing, lag spikes, and LLM “runaway plans”.

### 5.1 Max region size

- Recommended cap for demo: **32×32×32**
- Absolute cap is also acceptable: **48×48×48** if performance is fine, but default is **32³**.

### 5.2 Max blocks placed

- Default cap: **8,000** operations/blocks (tune down to 4,000 if laggy).

### 5.3 Whitelist-only palette

The mod must only allow blocks from a curated list (see next section).

Even if Gumloop validates, the mod must still enforce this whitelist.

---

## 6. Block Whitelist (Palette)

MVP “house/building” palette. Keep it small and familiar.

### 6.1 Recommended MVP whitelist

Use the `minecraft:` namespace explicitly.

- Structural:
  - `minecraft:oak_log`
  - `minecraft:oak_planks`
  - `minecraft:cobblestone`
  - `minecraft:stone_bricks` (enables “gothic-ish” looks with minimal complexity)
- Stairs/slabs:
  - `minecraft:oak_stairs`
  - `minecraft:cobblestone_stairs`
  - `minecraft:stone_brick_stairs` (optional, but consistent with stone bricks)
  - `minecraft:oak_slab` (optional)
  - `minecraft:cobblestone_slab` (optional)
  - `minecraft:stone_brick_slab` (optional)
- Fences/doors:
  - `minecraft:oak_fence`
  - `minecraft:cobblestone_wall` (better than “cobblestone fence”; stone walls read more “gothic”)
  - `minecraft:oak_door` (optional)
  - `minecraft:oak_trapdoor` (optional)
- Lighting:
  - `minecraft:torch`
  - `minecraft:lantern` (optional)
- Windows:
  - `minecraft:glass`

### 6.2 Redstone stance

- MVP default: **no redstone components**.
- Optional “decorative only” (if we add): `minecraft:redstone_torch`, `minecraft:redstone_wire`, `minecraft:lever`.
- Avoid for MVP: pistons/repeaters/comparators.

---

## 7. Gumloop’s Role (Option B: “Build Compiler”)

We will use Gumloop because:

- We can win a Gumloop usage prize.
- Credits are bundled and available.
- Gumloop makes the system more reliable by providing guardrails + retries + validation.

### 7.1 High-level pipeline

**Minecraft mod → Gumloop webhook → Minecraft mod**

Gumloop is responsible for:

- calling an LLM to generate a build plan
- ensuring it matches a strict JSON schema
- verifying safety constraints (bounds, whitelist, maxBlocks)
- retrying if invalid (up to a small retry count)

The Minecraft mod is responsible for:

- final re-validation (never trust network output)
- block placement (server-side)
- progressive build animation and cancellation
- selection overlay rendering

---

## 8. API Contract (Mod ↔ Gumloop)

### 8.1 Request payload (Mod → Gumloop)

The mod sends:

- `prompt`: string (the user’s desired build)
- `origin`: world coordinates of the build origin (min corner of region)
  - `{ "x": int, "y": int, "z": int }`
- `size`: `{ "w": int, "h": int, "l": int }`
- `palette`: string array of allowed block IDs (whitelist)
- `maxBlocks`: int (e.g. 8000)

Optional (context-aware generation):

- `context` (optional):
  - `origin`: `{ "x": int, "y": int, "z": int }`
  - `size`: `{ "w": int, "h": int, "l": int }`
  - `blocks`: a bounded list of non-air blocks in the context region (relative coords), capped to a safe maximum
  - `screenshot` (optional): an image of the player’s current view to “pad” context
    - taken when the player has a context selection and triggers a build
    - demo assumption: the player positions their camera so the context build is clearly visible

### 8.2 Response payload (Gumloop → Mod)

Gumloop returns:

- `meta`: `{ "theme": string, "blockCount": int, "warnings": string[] }`
- `ops`: array of operations of the form:
  - `{ "x": int, "y": int, "z": int, "block": string }`
  - Coordinates are **relative** to `origin`, and must satisfy:
    - `0 ≤ x < w`
    - `0 ≤ y < h`
    - `0 ≤ z < l`

### 8.3 Important guarantee boundaries

- Gumloop tries to ensure correctness, but the mod must still enforce:
  - bounds
  - whitelist
  - maxBlocks
  - JSON parse validity

---

## 9. JSON Schema (Build Plan)

Canonical schema we will enforce (conceptually):

```json
{
  "meta": {
    "theme": "string",
    "blockCount": 123,
    "warnings": ["string"]
  },
  "ops": [
    { "x": 0, "y": 0, "z": 0, "block": "minecraft:oak_planks" }
  ]
}
```

Notes:

- `ops.length` must equal `meta.blockCount`.
- We may later add optional rotation/facing fields; MVP avoids that to keep it simple.

---

## 10. Prompting Rules (Inside Gumloop)

### 10.1 Strictness

We will instruct the model:

- Output **ONLY valid JSON** (no markdown code fences).
- Use **ONLY** blocks from the palette.
- Do not exceed `maxBlocks`.
- All coordinates must be within bounds.

### 10.2 Style guidance (demo-friendly)

We will keep prompts reasonable during demo (we control prompts).

We’ll also maintain 1–2 pre-tested prompts for reliability, e.g.:

- “Small cozy oak cabin with a peaked roof and a stone foundation”
- “Gothic stone gate entrance with torches and a central arch”

---

## 11. Progressive Build (The “Wow Factor”)

### 11.1 Behavior

- Execute blocks in batches at a fixed rate (e.g. 50–200 blocks/sec).
- Show chat feedback:
  - “Tesseract drafting…”
  - “Building… 35% (2800/8000)”

### 11.2 Cancellation
De-scoped for MVP.

---

## 12. Error Handling (Required Messages)

Examples of user-facing errors:

- No region selected:
  - `Error: you haven't selected a region yet. Select two corners first.`
- Region too large:
  - `Error: selected region is too large (max 32x32x32).`
- Gumloop request failed:
  - `Error: failed to contact Gumloop. Try again.`
- Invalid JSON / validation failure:
  - `Error: AI returned an invalid plan (out of bounds / disallowed blocks / too many blocks).`

---

## 13. Demo Script (Recommended)

1. Fly to a flat area.
2. Select region corners; let judges see overlay.
3. (Optional) Select a **context** region containing a build you made earlier.
4. Run `/tesseract build <tested prompt>` or “build in the same style as the context”.
5. Pause and narrate: “Gumloop compiles a safe build plan.”
6. Watch progressive build.
7. Optionally rerun with another prompt or context to show a second flow.

---

## 14. What We’ll Keep Updating During the Hackathon

- Block whitelist (if we find a block is essential or problematic)
- MaxBlocks and build rate (to avoid lag)
- Gumloop prompt and validator rules (to reduce retries)
- Visual overlay polish (tint/alpha/color)
- Context serialization strategy (how much context to send vs summarize)

