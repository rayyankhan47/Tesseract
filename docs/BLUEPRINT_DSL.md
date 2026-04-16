# Tesseract Blueprint DSL — v1 Reference

## Overview

A **Blueprint** is a structured JSON document that describes a Minecraft building
using semantic geometric primitives instead of raw block coordinates.  The LLM
emits a Blueprint; a deterministic Java compiler expands it into a flat
`List<BlockOp>` that `PlacementAgent` places into the world.

**Why this matters**: LLMs are bad at spatial integer arithmetic but good at
compositional descriptions.  Blueprint primitives are composable (a roof sits
*on* its walls, which sit *on* their foundation) so the model never has to
compute absolute y-levels or alignment offsets.

---

## Root Schema

```json
{
  "name":       "string — human label for logging",
  "bounds":     { "sizeX": int, "sizeY": int, "sizeZ": int },
  "primitives": [ <Primitive>, ... ]
}
```

### Invariants

1. `primitives[0]` must have no `on` reference — it is the anchor (foundation or base).
2. All primitive `id` values must be unique across the array.
3. Every non-null `on` value must reference the `id` of an *earlier*-declared primitive.
4. `bounds.sizeX/Y/Z` must all be positive and ≤ 128.

### Coordinate system

- `(0, 0, 0)` is the **minimum corner** of the build region (world offset applied later by `PlacementAgent`).
- `x` and `z` are horizontal; `y` is up.
- All primitive coordinates are blueprint-local (relative to `(0,0,0)`).

---

## Primitive Types

Every primitive object has these common fields:

| Field  | Required | Description |
|--------|----------|-------------|
| `id`   | yes | Unique string label, e.g. `"foundation"`, `"walls"`, `"roof"` |
| `type` | yes | One of the 10 type names below |
| `on`   | no  | `id` of a parent primitive; this primitive's origin/size is inherited from the parent's top face (unless overridden by explicit `origin`/`size` params) |

All other fields live inside the primitive object at the top level (not nested under a `"params"` key).

---

### `platform`

Rectangular filled slab.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `origin` | `[x, y, z]` | yes | Min corner in blueprint space |
| `size`   | `[sx, sy, sz]` | yes | Extents in blocks (must be ≥ 1 in every axis) |
| `material` | string | yes | Full block ID, e.g. `"minecraft:stone_bricks"` |
| `edge_material` | string | no | If set, the outermost ring of blocks uses this instead of `material` |

```json
{ "id": "foundation", "type": "platform",
  "origin": [0, 0, 0], "size": [12, 1, 10],
  "material": "minecraft:stone_bricks",
  "edge_material": "minecraft:cobblestone" }
```

---

### `walls`

Hollow perimeter box rising from a parent primitive.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `on` | string | yes | Parent `id`; walls inherit parent footprint and start at parent top face |
| `height` | int | yes | Wall height in blocks (not including the parent) |
| `material` | string | yes | Wall fill block ID |
| `corner_material` | string | no | Used on the four corner columns if set |
| `openings` | `[Opening]` | no | List of openings (doors, windows) cut into walls |

**Opening schema:**
```json
{
  "face":     "north|south|east|west",
  "u_offset": int,   // blocks from left edge of that face (0-based)
  "v_offset": int,   // blocks from bottom of the wall (0 = ground, default 0)
  "width":    int,   // opening width in blocks
  "height":   int,   // opening height in blocks
  "type":     "door|window|gap"
}
```

```json
{ "id": "walls", "type": "walls", "on": "foundation",
  "height": 6, "material": "minecraft:oak_planks",
  "corner_material": "minecraft:oak_log",
  "openings": [
    { "face": "south", "u_offset": 4, "v_offset": 0, "width": 2, "height": 3, "type": "door" },
    { "face": "east",  "u_offset": 2, "v_offset": 2, "width": 2, "height": 2, "type": "window" },
    { "face": "west",  "u_offset": 2, "v_offset": 2, "width": 2, "height": 2, "type": "window" }
  ] }
```

---

### `wall_segment`

Single flat wall between two points.  Use when `walls` is too constrained
(e.g. non-rectangular perimeter or interior dividers).

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `from` | `[x, y, z]` | yes | Start corner |
| `to`   | `[x, y, z]` | yes | End corner (must share exactly two axes with `from`) |
| `height` | int | yes | Height in blocks |
| `material` | string | yes | Block ID |

```json
{ "id": "arch_frame", "type": "wall_segment",
  "from": [3, 1, 0], "to": [3, 1, 8],
  "height": 5, "material": "minecraft:stone_bricks" }
```

---

### `gable_roof`

Triangular peaked roof with stair-step slopes.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `on` | string | yes | Parent `id` (inherits footprint) |
| `ridge_axis` | `"x"` or `"z"` | yes | Axis along which the ridge runs |
| `overhang` | int | yes | How many blocks the roof extends past the parent footprint on the non-ridge sides (0 = flush) |
| `stairs_material` | string | yes | Stair block for the slopes (e.g. `"minecraft:oak_stairs"`) |
| `slab_material` | string | yes | Slab for the step tops |
| `ridge_material` | string | no | Material for the topmost ridge blocks (defaults to `stairs_material` base block) |

```json
{ "id": "roof", "type": "gable_roof", "on": "walls",
  "ridge_axis": "z", "overhang": 1,
  "stairs_material": "minecraft:oak_stairs",
  "slab_material": "minecraft:oak_slab",
  "ridge_material": "minecraft:oak_log" }
```

---

### `hip_roof`

Pyramid-style roof where all four sides slope to a single central ridge or apex.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `on` | string | yes | Parent `id` |
| `stairs_material` | string | yes | Stair block for the four sloped sides |
| `slab_material` | string | yes | Slab used at the apex or near-apex row |
| `apex_material` | string | no | Single apex block (defaults to `slab_material`) |

```json
{ "id": "tower_roof", "type": "hip_roof", "on": "tower_walls",
  "stairs_material": "minecraft:stone_brick_stairs",
  "slab_material": "minecraft:stone_brick_slab" }
```

---

### `flat_roof`

Flat single-layer roof with optional crenellations (battlements).

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `on` | string | yes | Parent `id` |
| `material` | string | yes | Roof fill block |
| `battlements` | boolean | no | If `true`, every other perimeter block is raised one block |
| `battlement_material` | string | no | Material for the raised battlement blocks (defaults to `material`) |

```json
{ "id": "parapet", "type": "flat_roof", "on": "tower_walls",
  "material": "minecraft:stone_bricks",
  "battlements": true,
  "battlement_material": "minecraft:stone_brick_wall" }
```

---

### `column`

Vertical pillar.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `origin` | `[x, y, z]` | yes | Base position |
| `height` | int | yes | Height in blocks |
| `material` | string | yes | Shaft block ID |
| `cap_material` | string | no | Top block (if different from shaft) |
| `base_material` | string | no | Bottom block (if different from shaft) |

```json
{ "id": "left_pillar", "type": "column",
  "origin": [0, 0, 0], "height": 8,
  "material": "minecraft:stone_brick_wall",
  "cap_material": "minecraft:stone_bricks" }
```

---

### `arch`

Semicircle archway between two points (same y level).

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `from` | `[x, y, z]` | yes | One pillar base |
| `to`   | `[x, y, z]` | yes | Other pillar base (must share y with `from`) |
| `height` | int | yes | Maximum arch height above base y |
| `material` | string | yes | Arch block ID |

```json
{ "id": "gate_arch", "type": "arch",
  "from": [2, 1, 4], "to": [8, 1, 4],
  "height": 5, "material": "minecraft:stone_bricks" }
```

---

### `staircase`

Steps connecting two elevations.

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `from` | `[x, y, z]` | yes | Bottom step position |
| `to`   | `[x, y, z]` | yes | Top step position |
| `width` | int | yes | Staircase width (extrudes perpendicular to step direction) |
| `material` | string | yes | Stair block ID (e.g. `"minecraft:oak_stairs"`) |

```json
{ "id": "entrance_stairs", "type": "staircase",
  "from": [5, 0, 10], "to": [5, 2, 8],
  "width": 3, "material": "minecraft:stone_stairs" }
```

---

### `frame`

Hollow rectangular box — only the outer shell is filled (interior is air).

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `origin` | `[x, y, z]` | yes | Min corner |
| `size`   | `[sx, sy, sz]` | yes | Extents |
| `material` | string | yes | Frame block ID |

```json
{ "id": "window_frame", "type": "frame",
  "origin": [2, 3, 0], "size": [3, 3, 1],
  "material": "minecraft:oak_log" }
```

---

## Reference semantics (`on`)

When a primitive declares `"on": "<parentId>"`:
- Its **y-origin** becomes the top face of the parent: `parent.originY + parent.sizeY`.
- Its **x/z footprint** is inherited from the parent's footprint unless overridden by explicit `origin`/`size` in the primitive itself.

Override rules (in order of priority):
1. Explicit `origin`/`size` param in the primitive → used directly.
2. Inherited from parent via `on`.
3. Neither available → compile error.

---

## Worked Example 1 — `cozy_oak_cabin`

A modest two-room cabin: stone foundation, oak plank walls with a door and two
windows, gable roof running along the Z-axis.

```json
{
  "name": "cozy_oak_cabin",
  "bounds": { "sizeX": 12, "sizeY": 12, "sizeZ": 10 },
  "primitives": [
    {
      "id": "foundation",
      "type": "platform",
      "origin": [0, 0, 0],
      "size": [12, 1, 10],
      "material": "minecraft:stone_bricks",
      "edge_material": "minecraft:cobblestone"
    },
    {
      "id": "walls",
      "type": "walls",
      "on": "foundation",
      "height": 6,
      "material": "minecraft:oak_planks",
      "corner_material": "minecraft:oak_log",
      "openings": [
        { "face": "south", "u_offset": 4, "v_offset": 0, "width": 2, "height": 3, "type": "door" },
        { "face": "east",  "u_offset": 2, "v_offset": 2, "width": 2, "height": 2, "type": "window" },
        { "face": "west",  "u_offset": 2, "v_offset": 2, "width": 2, "height": 2, "type": "window" }
      ]
    },
    {
      "id": "roof",
      "type": "gable_roof",
      "on": "walls",
      "ridge_axis": "z",
      "overhang": 1,
      "stairs_material": "minecraft:oak_stairs",
      "slab_material": "minecraft:oak_slab",
      "ridge_material": "minecraft:oak_log"
    }
  ]
}
```

---

## Worked Example 2 — `stone_watchtower`

A cylindrical-ish square tower: stone foundation, stone brick walls, flat roof
with battlements, corner columns for visual interest.

```json
{
  "name": "stone_watchtower",
  "bounds": { "sizeX": 8, "sizeY": 16, "sizeZ": 8 },
  "primitives": [
    {
      "id": "foundation",
      "type": "platform",
      "origin": [0, 0, 0],
      "size": [8, 1, 8],
      "material": "minecraft:cobblestone",
      "edge_material": "minecraft:stone_brick_wall"
    },
    {
      "id": "walls",
      "type": "walls",
      "on": "foundation",
      "height": 12,
      "material": "minecraft:stone_bricks",
      "corner_material": "minecraft:stone_brick_wall",
      "openings": [
        { "face": "south", "u_offset": 2, "v_offset": 0, "width": 2, "height": 3, "type": "door" },
        { "face": "north", "u_offset": 3, "v_offset": 5, "width": 2, "height": 2, "type": "window" }
      ]
    },
    {
      "id": "col_sw", "type": "column",
      "origin": [0, 1, 0], "height": 12,
      "material": "minecraft:stone_brick_wall"
    },
    {
      "id": "col_se", "type": "column",
      "origin": [7, 1, 0], "height": 12,
      "material": "minecraft:stone_brick_wall"
    },
    {
      "id": "col_nw", "type": "column",
      "origin": [0, 1, 7], "height": 12,
      "material": "minecraft:stone_brick_wall"
    },
    {
      "id": "col_ne", "type": "column",
      "origin": [7, 1, 7], "height": 12,
      "material": "minecraft:stone_brick_wall"
    },
    {
      "id": "parapet",
      "type": "flat_roof",
      "on": "walls",
      "material": "minecraft:stone_bricks",
      "battlements": true,
      "battlement_material": "minecraft:stone_brick_wall"
    }
  ]
}
```
