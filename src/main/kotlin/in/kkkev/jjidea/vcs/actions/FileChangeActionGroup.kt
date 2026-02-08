package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Builds a context menu action group for file changes.
 *
 * Includes:
 * - Show Diff (Jujutsu.ShowChangesDiff)
 * - Open File (Jujutsu.OpenChangeFile)
 * - Separator
 * - Compare with Local (Jujutsu.CompareWithLocal) - visible in historical context
 * - Compare Before with Local (Jujutsu.CompareBeforeWithLocal) - visible in historical context with parents
 * - Open Repository Version (Jujutsu.OpenRepositoryVersion) - visible in historical context
 * - Separator
 * - Restore (Jujutsu.RestoreFile) - visible in working copy context
 * - Restore to This (Jujutsu.RestoreToChange) - visible in historical context
 *
 * Actions self-filter their visibility based on the data context
 * (specifically [JujutsuDataKeys.LOG_ENTRY]).
 */
fun fileChangeActionGroup(): DefaultActionGroup {
    val actionManager = ActionManager.getInstance()
    val group = DefaultActionGroup()

    actionManager.getAction("Jujutsu.ShowChangesDiff")?.let { group.add(it) }
    actionManager.getAction("Jujutsu.OpenChangeFile")?.let { group.add(it) }

    group.addSeparator()

    // Compare actions (self-filter: historical only)
    actionManager.getAction("Jujutsu.CompareWithLocal")?.let { group.add(it) }
    actionManager.getAction("Jujutsu.CompareBeforeWithLocal")?.let { group.add(it) }
    actionManager.getAction("Jujutsu.OpenRepositoryVersion")?.let { group.add(it) }

    group.addSeparator()

    // Restore actions self-filter: RestoreFile visible for working copy, RestoreToChange for historical
    actionManager.getAction("Jujutsu.RestoreFile")?.let { group.add(it) }
    actionManager.getAction("Jujutsu.RestoreToChange")?.let { group.add(it) }

    return group
}
