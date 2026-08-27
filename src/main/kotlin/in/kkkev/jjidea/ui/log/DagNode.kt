package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId

/**
 * A node in a DAG identified purely by id + parent ids, with no notion of rendering, repo
 * scoping, or anything jj-specific beyond [ChangeId]. Lets algorithms that only shuffle graph
 * topology - currently [in.kkkev.jjidea.ui.rebase.RebaseSimulator]'s reparenting - depend on this
 * minimal shape instead of the full [in.kkkev.jjidea.jj.LogEntry], the same way
 * [in.kkkev.jjidea.ui.log.graph.LayoutCalculatorImpl] is generic and
 * [in.kkkev.jjidea.ui.log.CommitGraphBuilder] is the jj-domain wrapper around it.
 *
 * [GraphableEntry] is a similar but repo-scoped shape used for commit graph layout (it needs
 * `repo` to disambiguate two repositories' root commits, which otherwise share the same
 * synthetic change id - see `CommitGraphBuilderRepoScopingTest`). [RebaseSimulator] never reads
 * `repo`, so this interface omits it; reconciling the two - e.g. by making parent links
 * repo-scoped at the source instead of patching identity on after the fact - is tracked
 * separately (jj-idea-t0nu) rather than solved here.
 */
interface DagNode<T : DagNode<T>> {
    val id: ChangeId
    val parentIds: List<ChangeId>

    /** A copy of this node with [parentIds] replaced - the only mutation the simulator needs. */
    fun withParents(parentIds: List<ChangeId>): T
}
