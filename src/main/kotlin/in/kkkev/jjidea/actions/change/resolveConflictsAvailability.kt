package `in`.kkkev.jjidea.actions.change

/**
 * Availability state for the "Resolve Conflicts" actions, shared by the log-commit context menu
 * action ([resolveConflictsAction]) and the file-level details-pane action
 * (`ResolveSelectedConflictsAction`).
 *
 * jj-idea-sm1s: a conflict can be *inherited* by a descendant commit from an ancestor merge.
 * `LogEntry.hasConflict` is already accurate for this case (it reflects jj's `conflict` template
 * keyword, which is true for any commit with an active conflict in its tree). But resolution goes
 * through IntelliJ's merge dialog, which reads/writes files on disk - i.e. the working copy - so a
 * non-working-copy commit can't be resolved in place without first `jj edit`-ing it. Rather than
 * doing that edit implicitly, we surface [NEEDS_EDIT]: visible but disabled, with a hint telling
 * the user to edit the change first.
 */
internal enum class ResolveAvailability(val visible: Boolean, val enabled: Boolean, val needsEditHint: Boolean) {
    HIDDEN(visible = false, enabled = false, needsEditHint = false),
    ENABLED(visible = true, enabled = true, needsEditHint = false),

    // Working copy, but no conflicted files (preserves pre-existing behaviour).
    DISABLED(visible = true, enabled = false, needsEditHint = false),

    // Non-working-copy commit carrying a conflict (including one inherited from an ancestor).
    NEEDS_EDIT(visible = true, enabled = false, needsEditHint = true)
}

/** Availability for the log-commit "Resolve Conflicts" action ([resolveConflictsAction]). */
internal fun resolveAvailability(
    isWorkingCopy: Boolean,
    hasConflict: Boolean,
    workingCopyConflictCount: Int
): ResolveAvailability = when {
    isWorkingCopy -> if (workingCopyConflictCount > 0) ResolveAvailability.ENABLED else ResolveAvailability.DISABLED
    hasConflict -> ResolveAvailability.NEEDS_EDIT
    else -> ResolveAvailability.HIDDEN
}

/** Availability for the file-level "Resolve Selected Conflicts" action. */
internal fun resolveSelectedAvailability(
    hasContextConflicts: Boolean,
    isWorkingCopyContext: Boolean
): ResolveAvailability = when {
    !hasContextConflicts -> ResolveAvailability.HIDDEN
    isWorkingCopyContext -> ResolveAvailability.ENABLED
    else -> ResolveAvailability.NEEDS_EDIT
}
