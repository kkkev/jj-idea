package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import `in`.kkkev.jjidea.jj.JujutsuStateModel

/**
 * Actions against change objects.
 */
object ChangeAction {

}

/**
 * Helper function to refresh all UI components after VCS state changes.
 * Invalidates the state model, which will notify all observers.
 *
 * @param selectWorkingCopy If true, log views should select the working copy after refresh
 */
fun Project.refreshAfterVcsOperation(selectWorkingCopy: Boolean = true) {
    // Invalidate the model - it will notify all subscribers
    JujutsuStateModel.getInstance(this).invalidate(selectWorkingCopy)

    // Still need to mark VCS dirty for change list detection
    VcsDirtyScopeManager.getInstance(this).markEverythingDirty()
}
