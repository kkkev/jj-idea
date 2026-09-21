package `in`.kkkev.jjidea.jj

/** Bounded so a repo with many conflicted bookmarks can't turn exact-count derivation into an
 * unbounded fan-out of `jj log` calls (jj-idea-ks5k). */
private const val DIVERGENCE_BOOKMARKS_LIMIT = 10

/** Bounded so one exact-count query can't become an O(repo-size) traversal. */
private const val DIVERGENCE_COUNT_LIMIT = 1000

/**
 * Derives every local bookmark's own ahead/behind divergence, and corrects the matching
 * remote-tracking rows to agree with it, so the bookmarks panel and the log table's bookmark
 * chips always show the same numbers for the same bookmark (jj-idea-ks5k, GitHub #110) — today
 * they disagree because the panel derives divergence itself
 * ([in.kkkev.jjidea.ui.log.bookmarks.BookmarkTreeModel.buildRepoNodes], via
 * [withDivergenceFrom]) while the log table renders whatever (usually 0/0, thanks to jj erroring
 * "Not a tracked remote ref" on a local ref) the per-commit template emitted.
 *
 * Call once per repo, right after loading raw items from [LogService.getBookmarks] — see
 * [in.kkkev.jjidea.jj.JujutsuStateModel.references] — so every consumer downstream of
 * [RepositoryReferences] sees corrected numbers.
 *
 * Three corrections, applied in order:
 * 1. **Absent remotes are meaningless** (jj-idea-lc43 for a deleted local, jj-idea-j58e for a
 *    remote ref that never existed, e.g. via `auto-track-created-bookmarks`): a remote row with
 *    no target ([BookmarkItem.targets] empty, so [Bookmark.deleted]) carries an ahead/behind
 *    measured against nothing — [zeroedIfLocalDeleted] already zeroed the deleted-local case;
 *    [zeroedIfRemoteAbsent] does the mirror image here, and both are applied before deriving so
 *    neither garbage number can propagate onto the local bookmark or a collapsed rollup. Such a
 *    row is also dropped from the local's remote list entirely, per the agreed UX: no arrow, no
 *    number.
 * 2. **A non-conflicted local bookmark** derives via [withDivergenceFrom] exactly as before: the
 *    tracked remote rows' counts, swapped, max across remotes.
 * 3. **A conflicted (divergent) local bookmark** ignores jj's own `tracking_ahead_count`/
 *    `tracking_behind_count` for its remote rows: those fields report a `SizeHint` computed
 *    between the remote's single target and *one* of the local ref's several conflicting targets
 *    (confirmed empirically — for two sibling branches each 2 changes past their common
 *    ancestor, jj reports one direction only, e.g. `ahead=0/behind=2`, never the true
 *    bidirectional `ahead=2/behind=2`). [exactDivergence] recomputes both directions directly
 *    from the bookmark's own target set via two bounded revset queries per remote, giving the
 *    "counts a bookmark at its actual local position would show" the reporter asked for. The
 *    exact pair is also written back (swapped) onto the matching remote row, so its leaf/chip
 *    agrees with the local one instead of keeping jj's raw one-directional hint.
 *
 * **Scale.** Zero extra `jj log` calls in the overwhelmingly common case of no conflicted local
 * bookmark. Otherwise bounded to at most `2 × [DIVERGENCE_BOOKMARKS_LIMIT] ×` (tracked remotes
 * per bookmark) calls, each itself capped at [DIVERGENCE_COUNT_LIMIT] — never a per-file or
 * per-commit loop, independent of total repo size. See `BookmarkDivergenceScaleTest`.
 */
fun LogService.withDerivedDivergence(items: List<BookmarkItem>): List<BookmarkItem> {
    val deletedLocals = items.map { it.bookmark }.deletedLocalNames()
    val correctedRemotes = items
        .filter { it.bookmark.isRemote }
        .associate { item ->
            item.bookmark.name to item.copy(
                bookmark = item.bookmark.zeroedIfLocalDeleted(deletedLocals).zeroedIfRemoteAbsent()
            )
        }
    // Real (present, tracked, non-git) remote rows only — an absent or untracked remote row
    // contributes neither a divergence number nor a comparison target.
    val remotesByLocalName = correctedRemotes.values
        .filter {
            it.bookmark.isRemote &&
                it.bookmark.remote != GIT_PSEUDO_REMOTE &&
                it.bookmark.tracked &&
                !it.bookmark.deleted
        }
        .groupBy { it.bookmark.localName }

    val exactByName = mutableMapOf<BookmarkName, Bookmark>()
    var conflictedBudget = DIVERGENCE_BOOKMARKS_LIMIT
    for (item in items) {
        if (item.bookmark.isRemote || !item.bookmark.conflict || conflictedBudget <= 0) continue
        val remotes = remotesByLocalName[item.bookmark.localName] ?: continue
        conflictedBudget--
        for (remoteItem in remotes) {
            val exact = exactDivergence(item.targets, remoteItem.targets.firstOrNull() ?: continue) ?: continue
            // Local's own perspective: ahead/behind of its own position. The matching remote row
            // gets the swap, mirroring withDivergenceFrom, so both leaves read consistently.
            exactByName.merge(
                item.bookmark.name,
                Bookmark(item.bookmark.name, aheadCount = exact.ahead, behindCount = exact.behind),
                ::maxCounts
            )
            exactByName.merge(
                remoteItem.bookmark.name,
                Bookmark(remoteItem.bookmark.name, aheadCount = exact.behind, behindCount = exact.ahead),
                ::maxCounts
            )
        }
    }

    return items.map { item ->
        val corrected = correctedRemotes[item.bookmark.name]?.bookmark ?: item.bookmark
        val exact = exactByName[item.bookmark.name]
        val bookmark = when {
            exact != null -> corrected.copy(aheadCount = exact.aheadCount, behindCount = exact.behindCount)
            !corrected.isRemote -> corrected.withDivergenceFrom(
                remotesByLocalName[corrected.localName]?.map {
                    it.bookmark
                }.orEmpty()
            )
            else -> corrected
        }
        if (bookmark === item.bookmark) item else item.copy(bookmark = bookmark)
    }
}

private fun maxCounts(a: Bookmark, b: Bookmark) =
    a.copy(aheadCount = maxOf(a.aheadCount, b.aheadCount), behindCount = maxOf(a.behindCount, b.behindCount))

private data class ExactDivergence(val ahead: Int, val behind: Int)

/**
 * Exact bidirectional ahead/behind between [localTargets] (a conflicted local bookmark's own
 * `added_targets`) and one tracked remote's [remoteTarget], from the local's point of view:
 * `ahead` is how many changes [localTargets] has that [remoteTarget] lacks, `behind` the reverse.
 * [remoteTarget] is excluded from [localTargets] first — it's normally one of the local's own
 * conflicting targets, having come from this same remote's prior push — so the comparison is
 * against the bookmark's *other* target(s), independent of this remote; falls back to the full
 * set if that would leave nothing to compare.
 *
 * Two bounded `jj log` calls (capped at [DIVERGENCE_COUNT_LIMIT]), not a per-commit or per-file
 * loop. Returns `null` if [localTargets] is empty or either query fails.
 */
private fun LogService.exactDivergence(localTargets: List<ChangeId>, remoteTarget: ChangeId): ExactDivergence? {
    val others = localTargets.filterNot { it == remoteTarget }.ifEmpty { localTargets }
    if (others.isEmpty()) return null
    val local = others.joinToString("|") { "::$it" }
    val remote = "::$remoteTarget"
    val ahead = getLogBasic(revset = Expression("($local) ~ ($remote)"), limit = DIVERGENCE_COUNT_LIMIT)
        .getOrNull() ?: return null
    val behind = getLogBasic(revset = Expression("($remote) ~ ($local)"), limit = DIVERGENCE_COUNT_LIMIT)
        .getOrNull() ?: return null
    return ExactDivergence(ahead.size, behind.size)
}

/**
 * Zeroes the tracking counts jj reports for a remote-tracking row that itself has no target
 * (jj-idea-j58e, GitHub #110): `foo@origin` can be tracked but absent on the remote (e.g. created
 * locally via `auto-track-created-bookmarks` and never pushed), in which case jj's
 * `tracking_ahead_count`/`tracking_behind_count` measure from the *absent* remote to the repo
 * root — as meaningless as [zeroedIfLocalDeleted]'s deleted-local case, just the mirror side.
 * Agreed UX (GitHub #110): no arrow, no number, for a bookmark tracking a remote ref that doesn't
 * exist. Returns this bookmark unchanged unless it is itself a remote row with no target.
 */
fun Bookmark.zeroedIfRemoteAbsent(): Bookmark = if (isRemote && deleted) copy(aheadCount = 0, behindCount = 0) else this
