package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.DefaultActionGroup
import `in`.kkkev.jjidea.actions.ManagedActions
import `in`.kkkev.jjidea.actions.add

/**
 * Builds a context menu action group for file changes.
 *
 * Includes:
 * - Show Diff (Jujutsu.ShowChangesDiff)
 * - Open File (Jujutsu.OpenChangeFile)
 * - Separator
 * - Open Local File (Jujutsu.OpenLocalFile) - visible in historical context
 * - Compare with Local (Jujutsu.CompareWithLocal) - visible in historical context
 * - Compare Before with Local (Jujutsu.CompareBeforeWithLocal) - visible in historical context with parents
 * - Compare with Another Commit (Jujutsu.CompareWithBranch) - visible in historical context
 * - Compare Before with Another Commit (Jujutsu.CompareBeforeWithBranch) - visible in historical context with parents
 * - Open Repository Version (Jujutsu.OpenRepositoryVersion) - visible in historical context
 * - Annotate (standard "Annotate") - jj-idea-0t5o, matches Jujutsu.EditorGroup's placement
 * - Show History (Jujutsu.ShowFileHistory)
 * - Separator
 * - Restore (Jujutsu.RestoreFile) - visible in working copy context
 * - Restore to This (Jujutsu.RestoreToChange) - visible in historical context
 * - Resolve Conflicts (Jujutsu.ResolveSelectedConflicts) - visible when selection contains conflicted files
 * - Accept Side #1 / Accept Side #2 (Jujutsu.AcceptConflictCurrentSide/LastSide) - visible for an
 *   explicit multi-selection of conflicted files in the working copy; labelled with jj's own
 *   commit for a single-file selection (GitHub #66, and the #112 "yours/theirs is meaningless"
 *   feedback)
 * - Tracked toggle (Jujutsu.TrackedToggle) - visible in working copy context on predicted-ignored files
 *
 * Actions self-filter their visibility based on the data context
 * (specifically [in.kkkev.jjidea.actions.JujutsuDataKeys.LOG_ENTRY]).
 */
fun fileChangeActionGroup(): DefaultActionGroup {
    val group = DefaultActionGroup()

    group.add(ManagedActions["Jujutsu.ShowChangesDiff"])
    group.add(ManagedActions["Jujutsu.ShowDiffInNewTab"])
    group.add(ManagedActions["Jujutsu.OpenChangeFile"])

    group.addSeparator()

    // Compare/navigate actions (self-filter: historical only)
    group.add(ManagedActions["Jujutsu.OpenLocalFile"])
    group.add(ManagedActions["Jujutsu.CompareWithLocal"])
    group.add(ManagedActions["Jujutsu.CompareBeforeWithLocal"])
    group.add(ManagedActions["Jujutsu.CompareWithBranch"])
    group.add(ManagedActions["Jujutsu.CompareBeforeWithBranch"])
    group.add(ManagedActions["Jujutsu.OpenFileInRemote"])

    // jj-idea-0t5o: same slot Jujutsu.EditorGroup gives the standard Annotate action (right
    // before Show History, no separator between them). It resolves the file via VIRTUAL_FILE,
    // which JujutsuChangesTree only supplies for a working-copy selection
    // (JujutsuChangesTree.showsLocalFiles) - self-filters (hidden) otherwise.
    group.add(ManagedActions["Annotate"])
    group.add(ManagedActions["Jujutsu.ShowFileHistory"])

    group.addSeparator()

    // Restore actions self-filter: RestoreFile visible for working copy, RestoreToChange for historical
    group.add(ManagedActions["Jujutsu.RestoreFile"])
    group.add(ManagedActions["Jujutsu.RestoreToChange"])
    group.add(ManagedActions["Jujutsu.ResolveSelectedConflicts"])
    group.add(ManagedActions["Jujutsu.AcceptConflictCurrentSide"])
    group.add(ManagedActions["Jujutsu.AcceptConflictLastSide"])

    group.addSeparator()

    // Squash/split actions — self-filter based on entry mutability
    group.add(ManagedActions["Jujutsu.SquashFiles"])
    group.add(ManagedActions["Jujutsu.SquashIntoFiles"])
    group.add(ManagedActions["Jujutsu.SplitFiles"])
    group.add(ManagedActions["Jujutsu.SplitIntoNewParentFiles"])

    group.addSeparator()

    // Tracked toggle — self-filters (hidden outside working-copy context / when not predicted-ignored)
    group.add(ManagedActions["Jujutsu.TrackedToggle"])

    return group
}
