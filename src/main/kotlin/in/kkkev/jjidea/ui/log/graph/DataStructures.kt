package `in`.kkkev.jjidea.ui.log.graph

// === Input ===

data class GraphEntry<I : Any>(
    val current: I,
    /** Order matters: first parent is typically main branch */
    val parents: List<I>
)

// === Output ===

data class GraphLayout<I : Any>(
    val rows: List<RowLayout<I>>
)

/**
 * Why a parent has no lane of its own in this layout. Distinguishing the two is
 * jj-idea-2c8k/xi58's rendering-correctness backstop: a parent that's simply outside the
 * currently loaded page ([NOT_LOADED]) is not the same situation as one jj itself elided or a
 * filter hid ([HIDDEN]) — echoing jj's `~`, which means real history was deliberately skipped.
 * Before this distinction existed both rendered identically (no connector at all), which looked
 * indistinguishable from a true repository root.
 */
enum class ParentState { NOT_LOADED, HIDDEN }

data class RowLayout<I : Any>(
    val id: I,
    val lane: Int,
    /** lanes of children (rows above with this as parent) */
    val childLanes: List<Int>,
    /** lanes of parents (rows below) */
    val parentLanes: List<Int>,
    /** For each non-adjacent parent, the passthrough lane used (parent ID → lane) */
    val passthroughLanes: Map<I, Int> = emptyMap(),
    /**
     * Parent ids of this entry that aren't present in the laid-out entry set, with the state
     * ([ParentState]) explaining why - not present in `calculate`'s `allIds` at all
     * ([ParentState.NOT_LOADED]), or present there but filtered out of the entries being laid
     * out ([ParentState.HIDDEN]). A renderer uses this to show a pending/unresolved indicator
     * instead of looking like a true repository root; see
     * docs/design/jj-idea-hlu3-xi58-elided-ancestor-rendering.md.
     */
    val unresolvedParents: Map<I, ParentState> = emptyMap(),
    /**
     * Free lane reserved for this row's own unresolved-parent stub when the row's real
     * connector(s) already occupy [lane] (a mixed merge: one loaded parent, one unresolved).
     * Scoped to this row only - not a passthrough, not reserved for any other row. Null when no
     * separate lane is needed (row has no loaded parent at all, so the stub can use [lane]).
     */
    val stubLane: Int? = null
)
