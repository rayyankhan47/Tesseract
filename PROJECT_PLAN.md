# Tesseract — MVP Build Plan (Step-by-step)

This is the **execution plan** we will follow for the hackathon MVP.

Rules for using this plan:

- We will implement **one subsubstep at a time** (e.g. 2.1.3), then mark it done here.
- If a step changes scope, we update both this file and `PROJECT_DETAILS.md`.
- “Done” means working in-game (or at minimum compiled + logically complete if blocked by environment).

---

## 0. Scope Lock (Do not build beyond this until stable)

### 0.1 MVP constraints (must implement)

#### 0.1.1 Region size cap
- [ ] Default cap: 32×32×32
- [ ] Reject larger region with clear error message

#### 0.1.2 Build operation cap
- [ ] Default cap: 8000 block ops
- [ ] Reject plans exceeding cap

#### 0.1.3 Whitelist-only block placement
- [ ] Define a small block whitelist (IDs include `minecraft:` prefix)
- [ ] Reject any block not on whitelist

### 0.2 MVP “wow” requirements

#### 0.2.1 Selection overlay
- [ ] After selecting two corners, show a **filled translucent ground tint** over the selected area

#### 0.2.2 Progressive building
- [ ] Place blocks in batches over time (configurable rate)
- [ ] Show progress feedback in chat

#### 0.2.5 Context-aware generation
- [ ] Select a “context” region (separate wand)
- [ ] Send context snapshot to Gumloop so the AI can imitate style
- [ ] If context exists, attach a **screenshot** of the player’s current view (optional, best-effort)

---

## 1. Repository Scaffolding (Fabric 1.18.2)

### 1.1 Create base Fabric project

#### 1.1.1 Add Fabric example template
- [x] Download/unpack Fabric example mod for the 1.18 branch into repo root

#### 1.1.2 Rename identifiers to “tesseract”
- [x] Mod id
- [x] Main class name
- [x] Package name
- [x] Display name

#### 1.1.3 Confirm Gradle wrapper works
- [x] `./gradlew build` succeeds locally

### 1.2 Mod entrypoints and baseline command

#### 1.2.1 Register `/tesseract` command
- [x] Command prints “Tesseract loaded” to player chat

#### 1.2.2 Add `/tesseract help`
- [x] Prints available commands and basic usage

---

## 2. Region Selection (Two corners per player)

### 2.1 Data model

#### 2.1.1 Define a `Selection` object
- [x] Stores cornerA (optional) and cornerB (optional)
- [x] Computes min/max corners
- [x] Computes origin + size `{w,h,l}`

#### 2.1.2 Store selections per player
- [x] Map keyed by player UUID → build `Selection`
- [x] Map keyed by player UUID → context `Selection`
- [x] Clears on `/tesseract clear` (build selection)
- [x] Clears on `/tesseract context clear` (context selection)

### 2.2 Wand behavior (corner setting)

#### 2.2.1 Choose wand UX
- [x] Decide which item triggers build selection (e.g. wooden axe)
- [x] Decide which item triggers context selection (a different item)
- [x] First click sets Corner 1, second click sets Corner 2 (either mouse button)

#### 2.2.2 Hook interaction events
- [x] On click, record block position as corner (depending on which wand)
- [x] Send chat confirmation “Corner A set: x y z” / “Corner B set: x y z”

#### 2.2.3 Normalize corners
- [x] After both corners set, compute min/max and store normalized selection

### 2.3 Selection overlay rendering (filled ground tint)

#### 2.3.1 Decide overlay style parameters
- [x] Color (e.g. red)
- [x] Alpha (fully visible)
- [ ] Render only within a max distance from player (optional perf guard)

#### 2.3.2 Implement client-side rendering hook
- [x] Each frame/tick, if selection complete, render a translucent overlay on ground blocks in x–z rectangle

#### 2.3.3 Performance guardrails
- [x] If selected area too large, render simplified overlay or refuse selection

### 2.4 “Diff highlight” overlay (after build finishes)
#### 2.4.1 (De-scoped)
- [ ] Keep/Undo + diff highlight are out of scope for MVP.

---

## 3. Commands UX (Build, Clear)

### 3.1 `/tesseract build <prompt...>`

#### 3.1.1 Parse prompt string
- [x] Allow multi-word prompt (rest-of-line)

#### 3.1.2 Validate selection exists
- [x] If not selected: error message

#### 3.1.3 Validate region size cap
- [x] If too large: error message

#### 3.1.4 Trigger build pipeline
- [x] Start “drafting…” message
- [x] Disable starting a second build concurrently (while an active build job is running)

#### 3.1.5 Support context-aware prompting (optional)
- [x] If a context selection exists, include it in the Gumloop request
- [ ] If a context selection exists, capture/send a screenshot (best-effort; do not fail build if screenshot fails)

### 3.2 `/tesseract clear`

#### 3.3.1 Clear selection
- [x] Remove stored corners

#### 3.3.2 Clear overlay state
- [x] Overlay disappears immediately

### 3.3 Keep/Undo commands
#### 3.4.1 (De-scoped)
- [ ] Keep/Undo is out of scope for MVP.

---

## 4. Gumloop Integration (Webhook “Build Compiler”)

### 4.1 Workflow contract design

#### 4.1.1 Define request JSON
- [x] `prompt`
- [x] `origin` (world coords)
- [x] `size` (w,h,l)
- [x] `palette`
- [x] `maxBlocks`
- [x] `context` (optional): `{ origin, size, blocks[] }`
- [x] `context.screenshot` (optional): image bytes or base64 (depending on webhook expectations)

#### 4.1.2 Define response JSON
- [x] `meta` (theme, blockCount, warnings)
- [x] `ops` list `{x,y,z,block}`

### 4.2 Gumloop workflow implementation (inside Gumloop UI)

#### 4.2.1 Webhook trigger node
- [ ] Accept request payload

#### 4.2.2 LLM generation node
- [ ] Prompt includes strict JSON-only output rules + bounds + palette

#### 4.2.3 Verifier node (hard validation)
- [ ] Reject out-of-bounds ops
- [ ] Reject disallowed blocks
- [ ] Reject ops length > maxBlocks

#### 4.2.4 Retry strategy
- [ ] If invalid, feed error back and retry up to N times (e.g. 2)
- [ ] If still invalid, return an error payload (consistent shape)

### 4.3 Mod HTTP client + timeout behavior

#### 4.3.1 Add configurable Gumloop endpoint
- [x] Store webhook URL in config/env

#### 4.3.2 Perform request asynchronously
- [x] Do not freeze server thread

#### 4.3.3 Timeouts and error messages
- [x] On timeout/failure: message to player + do not start build

---

## 5. Plan Parsing & Validation (in the mod)

### 5.1 JSON parsing

#### 5.1.1 Add JSON library (or use built-in approach)
- [ ] Parse response JSON into Java objects

#### 5.1.2 Handle invalid JSON
- [ ] Show “AI returned invalid JSON” error

### 5.2 Validation rules (must be enforced even if Gumloop validates)

#### 5.2.1 Bounds validation
- [ ] Every op within `[0..w-1],[0..h-1],[0..l-1]`

#### 5.2.2 Whitelist validation
- [ ] Every op block is in palette whitelist

#### 5.2.3 Max blocks validation
- [ ] `ops.length <= maxBlocks`

#### 5.2.4 Safe execution guards
- [ ] Do not place blocks in unloaded chunks (either require loaded area or handle carefully)

#### 5.2.5 Modification safety rule (MVP)
- [ ] Default rule: for “generation”, allow replacements inside the region but record prior state for undo/preview
  - Note: “record prior state for undo/preview” is de-scoped if we drop Keep/Undo; we may still record minimal state for potential cancel-safety, but no user-facing undo.

---

## 6. Progressive Builder (Queue + Rate Limit)

### 6.1 Queue design

#### 6.1.1 Represent a build job
- [ ] Player who requested it
- [ ] Origin, size
- [ ] Ops list
- [ ] Current index/progress

#### 6.1.2 Prevent concurrent build conflicts
- [ ] One active build per player (MVP)

### 6.2 Scheduling placements

#### 6.2.1 Place blocks server-side
- [ ] Ensure world mutation happens on correct thread/tick

#### 6.2.2 Batch size / speed config
- [ ] Example: 50–200 blocks/sec (tune)

#### 6.2.3 Progress messages
- [ ] Update every N blocks or every second

### 6.3 End-of-job behavior

#### 6.3.1 Completion message
- [ ] After job finishes, announce completion and basic stats (blocks placed, duration)

---

## 7. Demo Hardening (Reliability > Features)

### 7.1 Pre-tested prompts

#### 7.1.1 Create 2 “demo prompts”
- [ ] Cabin prompt
- [ ] Gothic gate prompt

#### 7.1.2 Add `/tesseract demo cabin` and `/tesseract demo gate` (optional)
- [ ] Runs build with known-good prompts to reduce risk

### 7.2 Observability

#### 7.2.1 Log Gumloop request/response metadata
- [ ] Request id, timing, retries (if returned)

#### 7.2.2 Friendly error surfacing
- [ ] Fail loudly and clearly, never silently

---

## 8. Stretch Goals (Only after MVP is stable)

### 8.1 Undo / diff preview (future)

#### 8.1.1 Transactional Keep/Undo
- [ ] Add Keep/Undo and diff highlighting after MVP is stable.

### 8.2 “Fill ops” compression

#### 8.2.1 Add optional op type
- [ ] Support fill cuboids to reduce op count

### 8.3 Optional decorative redstone

#### 8.3.1 Expand whitelist cautiously
- [ ] Add `redstone_torch`, `redstone_wire`, `lever` only

