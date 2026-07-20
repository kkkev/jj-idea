package `in`.kkkev.jjidea.ui.duplicate

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode

/**
 * Predicts which `jj duplicate` destination/placement combinations would fail because they
 * require rewriting an immutable commit, so the dialog can prevent constructing them
 * instead of surfacing a raw CLI error.
 *
 * `jj duplicate` never rewrites the change(s) being duplicated — the copy is always a
 * brand-new commit. Only the *destination* can be rewritten, and only for the insert
 * modes (per `jj duplicate --help`: "the new children indicated by the arguments will be
 * rebased onto the heads of the specified commits"):
 * - **ONTO** (`-o`/`-d`): the destination isn't touched at all — the copy simply points at
 *   it as a parent. Never excludes anything.
 * - **INSERT_AFTER** (`-A X`): X's children are rebased onto the copy; X itself is *not*
 *   rewritten. Excluded iff X has an immutable child.
 * - **INSERT_BEFORE** (`-B X`): X itself gets a new parent (the copy) — X is rewritten
 *   directly. Excluded iff X is immutable.
 *
 * Both checks reduce to a single pass over the loaded entries with no graph traversal,
 * because jj's immutable set (`::immutable_heads()`) is ancestor-closed: every ancestor of
 * an immutable commit is itself immutable, so a *mutable* commit can never have an
 * immutable descendant. That means "X has an immutable descendant" is exactly "X has an
 * immutable child" — no need to walk further than one hop.
 *
 * Caveat: this predicts from whatever entries are loaded (typically `repo.logCache.all`).
 * For INSERT_AFTER, excluding a destination requires its immutable child to also be
 * loaded; an unloaded immutable child would let that destination slip through the guard.
 * Immutable commits are normally on the loaded trunk, so this is rare in practice — and jj
 * itself remains the final authority (the action's existing `onFailure` path is the
 * backstop), matching the loaded-log assumption `RebaseDialog`/`SquashIntoDialog` already
 * make for their own destination pickers.
 */
fun invalidDestinationIds(entries: List<LogEntry>, mode: RebaseDestinationMode): Set<ChangeId> =
    when (mode) {
        RebaseDestinationMode.ONTO -> emptySet()
        RebaseDestinationMode.INSERT_BEFORE -> entries.filter { it.immutable }.map { it.id }.toSet()
        RebaseDestinationMode.INSERT_AFTER -> entries.filter { it.immutable }.flatMap { it.parentIds }.toSet()
    }

/**
 * Placement modes that remain valid once [selected] destination(s) are chosen. A mode is
 * valid iff none of the selected IDs would be excluded by [invalidDestinationIds] for that
 * mode. An empty selection permits every mode (nothing has been ruled out yet).
 */
fun validPlacementModes(entries: List<LogEntry>, selected: Set<ChangeId>): Set<RebaseDestinationMode> =
    RebaseDestinationMode.entries.filterTo(mutableSetOf()) { mode ->
        selected.none { it in invalidDestinationIds(entries, mode) }
    }
