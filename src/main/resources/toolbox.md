# Tesseract geometric toolbox

Fourteen built-in functions the L4 REPL uses to compose block geometry.
Every function is pure and returns a `Set<BlockOp>` — an unordered
collection of block placements keyed by `(x, y, z)`. Two placements at
the same position dedup by position (later writer wins).

All coordinates are **inclusive integers** in the caller's coordinate
space (the element's parent zone is the natural space for L4). Doubles
are accepted for centres on round fills so you can centre on a
half-voxel.

## Fills

### `box(x1, y1, z1, x2, y2, z2, material)`
Solid axis-aligned cuboid. Inclusive on both corners.
```python
foundation = box(0, 0, 0, 15, 2, 15, "stone_bricks")
```

### `cylinder(cx, cz, y1, y2, radius, material)`
Solid vertical cylinder. `cx, cz` are the axis coordinates, `y1..y2` the
vertical extent. Radius is in blocks; half-step widening makes round
cylinders read as round and not jaggy.
```python
drum = cylinder(8.0, 8.0, 0, 7, 5.0, "sandstone")
```

### `pyramid(cx, cz, y1, height, baseRadius, material)`
Stepped square pyramid with apex up. Radius shrinks by one block per Y
step (`height=4, baseRadius=3` → layer radii `3, 2, 1, 0`).
```python
roof = pyramid(8, 8, 12, 4, 3, "stone_brick_stairs")
```

### `sphere(cx, cy, cz, radius, material)`
Solid sphere. Useful for domes when intersected with a half-space.
```python
dome = sphere(8.0, 12.0, 8.0, 4.0, "quartz_block")
```

## Outlines

### `walls(x1, y1, z1, x2, y2, z2, material)`
Four vertical walls of an axis-aligned box. No floor, no ceiling, no
interior.
```python
shell = walls(0, 0, 0, 10, 5, 10, "planks")
```

### `frame(x1, y1, z1, x2, y2, z2, material)`
Twelve edges of an axis-aligned box — a wire-frame skeleton.
```python
ribs = frame(0, 0, 0, 10, 10, 10, "dark_oak_log")
```

### `line(x1, y1, z1, x2, y2, z2, material)`
3D Bresenham line; both endpoints included.
```python
strut = line(0, 0, 0, 10, 5, 2, "iron_bars")
```

## Curves

### `arc(cx, cy, cz, radius, startDeg, endDeg, axis, material)`
2D arc in the plane perpendicular to `axis` (`'X'`, `'Y'`, or `'Z'`).
Angles are degrees, counter-clockwise looking down the axis.
```python
archway = arc(5, 4, 0, 3.0, 0, 180, 'Z', "stone")
```

## Composition

### `repeat(ops, dx, dy, dz, count)`
Translates `ops` by `(dx, dy, dz)` `count` times and unions all copies
(including the original). `count=3` produces 4 copies.
```python
bay   = walls(0, 0, 0, 3, 4, 3, "stone")
wings = repeat(bay, 4, 0, 0, 5)
```

### `mirror(ops, axis, pivot)`
Reflects `ops` across `axis={'X','Y','Z'}` at `pivot` and unions.
```python
left_half = walls(0, 0, 0, 4, 5, 0, "stone")
both      = mirror(left_half, 'X', 5)
```

### `subtract(a, b)`
Every op in `a` whose position is not also in `b`. Position-only
matching; materials ignored.
```python
solid  = box(0, 0, 0, 10, 5, 10, "stone")
window = box(3, 2, 0, 5, 4, 0, "stone")
wall   = subtract(solid, window)
```

### `intersect(a, b)`
Every op in `a` whose position is also in `b`. Preserves `a`'s
material.
```python
disc  = cylinder(8.0, 8.0, 0, 0, 5.0, "stone")
slice = intersect(disc, box(5, 0, 0, 11, 0, 8, "stone"))
```

## Decoration

### `crenellate(wallTop, period, offset, material)`
Turns the top-Y layer of `wallTop` into a crenellated parapet. Merlons
are kept and raised one block; crenels (gaps) are dropped. `period=1`
gives the classic castle tooth pattern; `period=2` gives pairs of
merlons separated by single gaps; `offset` shifts the pattern.
```python
top    = walls(0, 0, 0, 10, 0, 10, "stone")
crown  = crenellate(top, 1, 0, "stone_brick")
```

### `scatter(x1, y1, z1, x2, y2, z2, density, seed, material)`
Deterministic random scatter inside a bounding box. Each voxel is
filled with probability `density` (0.0–1.0); `seed` makes reruns
identical.
```python
rubble = scatter(0, 0, 0, 15, 0, 15, 0.15, 42, "cobblestone")
```

## Idioms

- **Union of multiple sets:** merge by passing the result into the next
  call's set argument; every function returns a fresh set, so
  `union = subtract(a, subtract(a, b))` is the common identity.
- **Avoid over-large sets:** the sandbox has a hard voxel ceiling. Prefer
  `walls` over `box` when you only need a shell, and use `subtract` to
  carve rather than enumerating every block.
- **Determinism:** every function is deterministic for a given input.
  Only `scatter` takes a seed; all other randomness must come from your
  own `random.Random(seed)` at the script level (not yet supported —
  use `scatter` or a manual loop).
