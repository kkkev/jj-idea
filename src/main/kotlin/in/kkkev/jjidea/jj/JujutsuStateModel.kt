package `in`.kkkev.jjidea.jj

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import `in`.kkkev.jjidea.vcs.jujutsuVcs

/**
 * Central state model for Jujutsu VCS data.
 * Implements MVC pattern: this is the Model that views observe.
 *
 * Holds current VCS state and notifies observers via MessageBus when state changes.
 */
@Service(Service.Level.PROJECT)
class JujutsuStateModel(private val project: Project) : Disposable {

    private val log = Logger.getInstance(javaClass)

    // Current state (accessed from EDT)
    @Volatile
    private var _workingCopy: LogEntry? = null
    val workingCopy: LogEntry? get() = _workingCopy

    @Volatile
    private var _fileChanges: List<Change> = emptyList()
    val fileChanges: List<Change> get() = _fileChanges

    @Volatile
    private var _selectWorkingCopy: Boolean = false
    val shouldSelectWorkingCopy: Boolean get() = _selectWorkingCopy

    /**
     * Refresh the model from the VCS.
     * Loads fresh data and notifies all listeners.
     */
    private fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val vcs = project.jujutsuVcs

                // Load working copy entry
                val logResult = vcs.logService.getLog(WorkingCopy)
                val newWorkingCopy = logResult.getOrNull()?.firstOrNull()

                // Load file changes from ChangeListManager
                val changeListManager = ChangeListManager.getInstance(project)
                val newFileChanges = changeListManager.allChanges.toList()

                ApplicationManager.getApplication().invokeLater {
                    val workingCopyChanged = _workingCopy != newWorkingCopy

                    _workingCopy = newWorkingCopy
                    _fileChanges = newFileChanges

                    if (workingCopyChanged) {
                        notifyWorkingCopyChanged()
                    }
                    notifyLogUpdated()
                }
            } catch (e: Exception) {
                log.error("Failed to refresh Jujutsu state", e)
            }
        }
    }

    /**
     * Invalidate the model and trigger a refresh.
     * Call this after VCS operations that change state.
     *
     * @param selectWorkingCopy If true, views should select the working copy after refresh
     */
    fun invalidate(selectWorkingCopy: Boolean = false) {
        log.debug("State invalidated (selectWorkingCopy=$selectWorkingCopy), refreshing...")
        _selectWorkingCopy = selectWorkingCopy
        refresh()
    }

    private fun notifyWorkingCopyChanged() {
        log.debug("Publishing workingCopyChanged event")
        project.messageBus.syncPublisher(JujutsuStateListener.TOPIC)
            .workingCopyChanged()
    }

    private fun notifyLogUpdated() {
        log.debug("Publishing logUpdated event")
        project.messageBus.syncPublisher(JujutsuStateListener.TOPIC)
            .logUpdated()
    }

    override fun dispose() {
        _workingCopy = null
        _fileChanges = emptyList()
    }

    companion object {
        fun getInstance(project: Project): JujutsuStateModel = project.service()
    }
}
