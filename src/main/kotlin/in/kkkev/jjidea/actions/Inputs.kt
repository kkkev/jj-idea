package `in`.kkkev.jjidea.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsConfiguration
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.ui.describe.DescribeDialog

/**
 * Opens [DescribeDialog] - a real commit-message editor (spellcheck, inspections, history) - and
 * returns the edited [Description], or null if the user cancelled. See GitHub #46 / jj-idea-n3w1.
 */
fun Project.requestDescription(
    resourceKeyPrefix: String,
    initial: Description = Description.EMPTY,
    vararg messageParams: Any
): Description? {
    val dialog = DescribeDialog(
        this,
        title = JujutsuBundle.message("$resourceKeyPrefix.title"),
        label = JujutsuBundle.message("$resourceKeyPrefix.message", *messageParams),
        initial = initial
    )
    return if (dialog.showAndGet()) dialog.result else null
}

/**
 * Adds [description] to the IDE's shared recent-commit-messages list (also used by Git's commit
 * UI), populating the history popup the description editors' toolbars show. Call only after a
 * `jj` operation actually succeeds - never on cancel or failure - so a discarded edit doesn't
 * pollute the history.
 */
fun Project.saveDescriptionToHistory(description: Description) {
    if (description.empty) return
    VcsConfiguration.getInstance(this).saveCommitMessage(description.actual)
}
