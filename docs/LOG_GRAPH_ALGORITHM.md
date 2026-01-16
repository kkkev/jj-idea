# Log Graph Drawing Algorithm

This document describes the algorithm for drawing commit graphs in the custom log window.

## Concepts

### Rows and Entries

- Each row contains one entry
- Each entry has a change ID and zero or more parent change IDs
- Rows are processed top-to-bottom (newest commits at top)
- Parents are always in rows below their children

### Lanes

- Each entry is assigned a lane (0 is leftmost)
- The lane determines the horizontal position of the entry's circle
- Each lane has a designated color

### Passthroughs

- A passthrough represents a parent-child connection that spans intermediate rows
- When a child is above and its parent is more than one row below, the rows in between have a passthrough
- A passthrough occupies a lane and draws a vertical line through that row
- A passthrough uses the child's lane (the lane is established when the child is processed and maintained until the passthrough terminates at the parent)

### Passthrough Data Structure

Each passthrough tracks:

| Field | Description |
|-------|-------------|
| `lane` | The lane it occupies (same as the source child's lane) |
| `targetParentId` | The change ID of the parent it connects to |
| `sourceLane` | The lane of the child (for drawing diagonal lines when terminating) |

## Drawing

### Circle Drawing

- Each entry's circle is drawn at the vertical center of its row
- Horizontal position is determined by the entry's lane

### Line Drawing

#### Upward lines (to children)

- Lines are drawn from the circle center upward toward each child's lane
- If the child is in the same lane: draw a vertical line
- If the child is in a different lane: draw a diagonal line toward that lane
- **Color**: the child's lane color

#### Downward lines (to parents)

- Lines are drawn from the circle center downward toward each parent's lane
- If the parent is in the same lane: draw a vertical line
- If the parent is in a different lane: draw a diagonal line toward that lane
- **Color**: the parent's lane color

#### Passthrough lines

- For each passthrough in a row, draw a vertical line through the row at the passthrough's lane
- **Color**: the passthrough's lane color

## Lane Allocation Algorithm

### Initialization

- Start with an empty set of active passthroughs
- Maintain a map: `childrenByParent[parentId] → list of {childId, childLane}`

### Processing Each Row

For each row, processed top to bottom:

#### Step 1: Determine this entry's lane

- Look up children of this entry in `childrenByParent`
- **If no children exist**: pick the lowest available lane (leftmost lane not occupied by a passthrough)
- **If children exist**: pick the lane of the child with the lowest lane number, unless that lane is occupied by a passthrough, in which case pick the lowest available lane

#### Step 2: Terminate passthroughs that end at this entry

- For each active passthrough where `targetParentId` equals this entry's change ID:
  - Record that a diagonal line is needed from `sourceLane` (above) to this entry's lane
  - Remove the passthrough from active passthroughs

#### Step 3: Register this entry as a child of its parents

- For each parent of this entry:
  - Add `{childId: this entry's ID, childLane: this entry's lane}` to `childrenByParent[parentId]`

#### Step 4: Create passthroughs for parents not in the next row

- For each parent of this entry:
  - If the parent is not in the immediately next row:
    - Create a passthrough with:
      - `lane`: this entry's lane
      - `targetParentId`: the parent's change ID
      - `sourceLane`: this entry's lane

#### Step 5: Pass active passthroughs to the next row

- The set of active passthroughs (minus those terminated in step 2, plus those created in step 4) carries forward to the next row

## Handling Multiple Parents

When an entry has multiple parents (a merge commit):

- The entry's lane selection considers all children (as described in step 1)
- Each parent gets its own downward line from the circle
- Each parent that is not in the immediately next row creates its own passthrough
- When allocating lanes for multiple parents, prefer adjacent lanes to minimize line crossings

## Examples

### Example 1: Simple Fork

Consider this commit history where two commits (A and B) share a common parent (C):

```
Row 0: A (parents: C)
Row 1: B (parents: C)
Row 2: C (parents: D)
Row 3: D (no parents)
```

**Processing:**

1. **Row 0 (A)**: No children, assign lane 0. Parent C is not in next row, create passthrough `{lane: 0, target: C, source: 0}`
2. **Row 1 (B)**: No children in `childrenByParent`, but lane 0 is occupied by passthrough. Assign lane 1. Parent C is in next row, no passthrough needed.
3. **Row 2 (C)**: Children are A (lane 0) and B (lane 1). Lowest child lane is 0, occupied by passthrough for A→C. That passthrough terminates here (target is C). Assign lane 0. Parent D is in next row, no passthrough needed.
4. **Row 3 (D)**: Child is C (lane 0). Assign lane 0.

**Result:**

```
Lane:  0   1
       ●      ← A
       │   ●  ← B
       ●───┘  ← C (B's line merges from lane 1)
       ●      ← D
```

### Example 2: Fork and Merge (Diamond Pattern)

A more complex history with both a fork point and a merge:

```
Row 0: A (parents: B, E)   ← MERGE: combines two branches
Row 1: B (parents: C)
Row 2: C (parents: D)
Row 3: D (parents: G)
Row 4: E (parents: F)
Row 5: F (parents: G)
Row 6: G (no parents)      ← FORK POINT: common ancestor
```

**Processing:**

| Row | Entry | Children | Lane Selection | Passthroughs Created | Active Passthroughs |
|-----|-------|----------|----------------|---------------------|---------------------|
| 0 | A | none | 0 (lowest available) | E not next row → `{lane:0, target:E}` | `[{0,E}]` |
| 1 | B | A@0 | 0 occupied by passthrough → 1 | C is next row → none | `[{0,E}]` |
| 2 | C | B@1 | 1 (follow child) | D is next row → none | `[{0,E}]` |
| 3 | D | C@1 | 1 (follow child) | G not next row → `{lane:1, target:G}` | `[{0,E}, {1,G}]` |
| 4 | E | A@0 | 0 (passthrough terminates here) | F is next row → none | `[{1,G}]` |
| 5 | F | E@0 | 0 (follow child) | G is next row → none | `[{1,G}]` |
| 6 | G | D@1, F@0 | 0 (lowest child lane); passthrough terminates | none | `[]` |

**Result:**

```
Lane:  0   1

       ●───┐   ← A (merge commit: parents B and E)
       │   │
       │   ●   ← B
       │   │
       │   ●   ← C
       │   │
       │   ●   ← D
       │   │
       ●   │   ← E
       │   │
       ●   │   ← F
       │   │
       ●───┘   ← G (fork point: ancestor of both branches)
```

**Line details:**

| From | To | Type | Color |
|------|-----|------|-------|
| A | B | Diagonal down-right (lane 0 → 1) | Lane 1 (B's lane) |
| A | E | Vertical down via passthrough | Lane 0 (E's lane) |
| B | C | Vertical | Lane 1 |
| C | D | Vertical | Lane 1 |
| D | G | Vertical via passthrough, diagonal at G | Lane 0 (G's lane) |
| E | F | Vertical | Lane 0 |
| F | G | Vertical | Lane 0 |

**Passthrough visualization:**

- **Rows 1-3**: Lane 0 has a passthrough connecting A to E (vertical line)
- **Rows 4-5**: Lane 1 has a passthrough connecting D to G (vertical line)
- **Row 6**: Passthrough from lane 1 terminates with diagonal to lane 0 (where G is)
