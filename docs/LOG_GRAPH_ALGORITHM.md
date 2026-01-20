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

### Connection Classification

Each connection from a child to a non-adjacent parent is classified as one of:

| Type | Condition | Passthrough Lane | Visual Behavior |
|------|-----------|------------------|-----------------|
| **Simple/Fork** | Child has 1 parent | Child's lane | Vertical in child's lane, diagonal at parent's row |
| **Fork+Merge** | Child has multiple parents AND parent has multiple children | Child's lane | Vertical in child's lane, diagonal at parent's row |
| **Pure Merge** | Child has multiple parents AND parent has only this child | NEW lane (reserved for parent) | Diagonal at child's row, vertical in parent's lane |

The key insight is that fork+merge connections are treated like forks (visual line stays in child's lane), while pure merges get a dedicated lane for the merge line.

### Passthroughs

- A passthrough represents a parent-child connection that spans intermediate rows
- When a child is above and its parent is more than one row below, the rows in between have a passthrough
- A passthrough occupies a lane and draws a vertical line through that row
- For simple/fork connections: passthrough uses child's lane
- For pure merge connections: passthrough uses a NEW lane (which the parent will inherit)

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

- **If a lane was reserved for this entry** (from a pure merge passthrough): use that lane
- Otherwise, look up children of this entry in `childrenByParent`
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

- For each parent of this entry that is not in the immediately next row:
  - Classify the connection:
    - **If this entry has only 1 parent**: Simple/Fork → passthrough uses child's lane
    - **If this entry has multiple parents**:
      - Check if the parent already has other children (from earlier rows)
      - **If parent has other children**: Fork+Merge → passthrough uses child's lane
      - **If parent has no other children**: Pure Merge → passthrough uses a NEW lane (reserve it for the parent)
  - Create a passthrough with:
    - `lane`: determined by classification above
    - `targetParentId`: the parent's change ID

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

**Processing with Fork+Merge Classification:**

| Row | Entry | Children | Lane Selection | Classification | Passthroughs |
|-----|-------|----------|----------------|----------------|--------------|
| 0 | A | none | 0 (lowest available) | E: pure merge (E has no other children) | `{lane:1, target:E}` (new lane, reserved) |
| 1 | B | A@0 | 0 (not blocked, passthrough is at lane 1) | C is adjacent | none |
| 2 | C | B@0 | 0 (follow child) | D is adjacent | none |
| 3 | D | C@0 | 0 (follow child) | G: simple (D has 1 parent) | `{lane:0, target:G}` |
| 4 | E | A@0 | 1 (reserved lane) | F is adjacent | none |
| 5 | F | E@1 | 1 (follow child) | G is adjacent | none |
| 6 | G | D@0, F@1 | 0 (lowest child lane) | | |

**Result:**

```
Lane:  0   1

       ●───┐   ← A (merge commit: parents B and E)
       │   │
       ●   │   ← B (stays in lane 0, not pushed)
       │   │
       ●   │   ← C
       │   │
       ●   │   ← D
       │   │
       │   ●   ← E (reserved lane 1 from pure merge)
       │   │
       │   ●   ← F
       │   │
       ●───┘   ← G (fork point: ancestor of both branches)
```

**Line details:**

| From | To | Classification | Passthrough Lane | Color |
|------|-----|---------------|------------------|-------|
| A | B | Adjacent (no passthrough) | - | Lane 0 |
| A | E | Pure merge | Lane 1 (reserved for E) | Lane 1 |
| B | C | Adjacent | - | Lane 0 |
| C | D | Adjacent | - | Lane 0 |
| D | G | Simple (D has 1 parent) | Lane 0 (child's lane) | Lane 0 |
| E | F | Adjacent | - | Lane 1 |
| F | G | Adjacent | - | Lane 1 |

**Key observation:** With the fork+merge classification, the first branch (B, C, D) stays in lane 0 because the passthrough to E uses a new lane (1) instead of blocking lane 0. This results in a more compact layout where the main branch stays in lane 0
