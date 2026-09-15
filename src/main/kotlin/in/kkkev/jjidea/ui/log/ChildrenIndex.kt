package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeKey

/**
 * Repo-scoped parent -> children index over [entries], via [GraphableEntry.key]/[GraphableEntry.parentKeys] -
 * the same repo-scoping [CommitGraphBuilder] uses (jj-idea-1ra9: two repos' root commits can
 * share a synthetic change id, so identity must include `repo`). Doesn't discriminate loaded-
 * vs-filtered - an entry that's currently hidden by a filter is still a real DAG node, matching
 * [JujutsuLogTableModel.entryFor]'s existing "still a valid action target" convention.
 *
 * Backs Move Up/Down's "swap with the single child" resolution (jj-idea-owje, GitHub #93) -
 * [buildChildrenMap][`in`.kkkev.jjidea.ui.rebase.RebaseSimulator]'s equivalent is `private` and
 * keyed on bare `ChangeId`, so it isn't repo-safe and isn't reused here; that's fine for its own
 * (provably single-repo, per jj-idea-t0nu) callers but wrong for an action that must work
 * correctly across a multi-root log.
 */
fun <T : GraphableEntry> buildChildrenIndex(entries: List<T>): Map<ChangeKey, List<T>> {
    val result = mutableMapOf<ChangeKey, MutableList<T>>()
    for (entry in entries) {
        for (parentKey in entry.parentKeys) {
            result.getOrPut(parentKey) { mutableListOf() }.add(entry)
        }
    }
    return result
}
