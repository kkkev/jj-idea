package `in`.kkkev.jjidea.ui.log.graph

/**
 * Calculates the layout for a commit graph.
 *
 * @see <a href="../../../../../../../docs/LOG_GRAPH_ALGORITHM.md">Log Graph Algorithm</a>
 */
interface LayoutCalculator<I : Any> {
    /**
     * @param allIds ids known to be loaded even if not laid out in [entries] (e.g. the full page
     *   before a filter is applied). Defaults to just [entries]'s own ids, so a caller with no
     *   filter concept gets every unresolved parent classified [ParentState.NOT_LOADED] as
     *   before. A parent missing from [entries] but present in [allIds] is [ParentState.HIDDEN]
     *   instead - loaded, but filtered out of what's being laid out.
     */
    fun calculate(
        entries: List<GraphEntry<I>>,
        allIds: Set<I> = entries.mapTo(HashSet()) {
            it.current
        }
    ): GraphLayout<I>
}

/**
 * A from-scratch [LayoutCalculator.calculate] over a fresh [IncrementalLayout] engine - see
 * that class for the algorithm itself and jj-idea-jnqi's incremental (append-only) entry
 * point, which [in.kkkev.jjidea.ui.log.JujutsuCommitGraph.CommitGraphBuilder] uses directly
 * for the merged-log fast path instead of going through this wrapper.
 */
class LayoutCalculatorImpl<I : Any> : LayoutCalculator<I> {
    private val engine = IncrementalLayout<I>()

    /**
     * Work-count of per-row passthrough/lane bookkeeping in the last [calculate] call.
     * Exposed for `report.count("operations", …)` and for scale tests asserting this stays
     * linear (not quadratic) in entry count - see GraphLayoutScaleTest.
     */
    val operationCount: Long get() = engine.operationCount

    override fun calculate(entries: List<GraphEntry<I>>, allIds: Set<I>): GraphLayout<I> {
        engine.reset()
        return engine.append(entries, allIds)
    }
}
