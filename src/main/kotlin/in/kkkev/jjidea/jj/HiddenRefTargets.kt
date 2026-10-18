package `in`.kkkev.jjidea.jj

/** Upper bound on hidden targets resolved in the one batched `jj log`, keeping the revset argument bounded. */
internal const val HIDDEN_TARGET_LIMIT = 200

/**
 * Commit ids of the targets jj reports as hidden, e.g. a remote bookmark left behind when its change was amended
 * locally (jj-idea-wiz0, GitHub #130). Distinct, in first-seen order.
 */
internal fun List<RefItem>.hiddenTargetIds(): List<CommitId> = asSequence()
    .flatMap { item -> item.commitIds.zip(item.hiddens).asSequence().filter { it.second }.map { it.first } }
    .distinct()
    .toList()

/**
 * Loads the [LogEntry] behind every hidden bookmark/tag target, keyed by commit id.
 *
 * Hidden commits are not in the log table, so this is the only source of a bookmark panel row's entry when its target
 * is hidden. One `jj log` call regardless of how many targets are hidden (capped at [HIDDEN_TARGET_LIMIT] rows), and
 * none at all when no target is hidden - the common case. A failed call yields an empty map: those rows simply offer
 * no change actions, rather than acting on the visible commit that shares the change id.
 */
fun LogService.loadHiddenTargets(bookmarks: List<BookmarkItem>, tags: List<TagItem>): Map<CommitId, LogEntry> {
    val ids = (bookmarks + tags).hiddenTargetIds().take(HIDDEN_TARGET_LIMIT)
    if (ids.isEmpty()) return emptyMap()
    val revset = Expression(ids.joinToString(" | ") { it.full })
    return getLogBasic(revset).getOrNull().orEmpty().associateBy { it.commitId }
}

/**
 * The [LogEntry] this ref's target actually is, or `null` when it cannot be established.
 *
 * A hidden target is looked up by commit id in [hiddenTargets], never by change id (which would find the visible
 * commit sharing it). A visible target uses [lookup], but only if the entry found is that very commit: a log that
 * lags behind the references by a rewrite yields `null` rather than a different commit. A ref without commit ids
 * (not reported by its source) falls back to the change id.
 */
fun RefItem.resolveEntry(
    repo: JujutsuRepository,
    lookup: (ChangeKey) -> LogEntry?,
    hiddenTargets: Map<CommitId, LogEntry>
): LogEntry? {
    val commitId = commitId
    if (hidden) return commitId?.let(hiddenTargets::get)
    val entry = id?.let { lookup(ChangeKey(repo, it)) } ?: return null
    return entry.takeIf { commitId == null || it.commitId == commitId }
}
