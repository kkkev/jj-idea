package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Base class for background data loaders that manages the loading state machine:
 * - Coalesces concurrent requests via [loading] + [pendingRefresh] flags
 * - Cancels the previous indicator when a new load starts
 * - Resets state on success, failure, AND cancellation (fixing the stuck-state bug)
 */
abstract class BackgroundDataLoader(
    private val project: Project,
    private val taskTitle: String
) : DataLoader {
    protected val log: Logger = Logger.getInstance(javaClass)

    private val loading = AtomicBoolean(false)
    private val pendingRefresh = AtomicBoolean(false)
    private val currentIndicator = AtomicReference<ProgressIndicator?>(null)

    /**
     * Execute a background task with proper state management.
     *
     * @param run Work to perform on background thread. Receives [ProgressIndicator].
     * @param onSuccess Called on EDT after [run] completes successfully.
     * @param onError Optional handler for failures; defaults to logging the error.
     */
    protected fun executeInBackground(
        run: (ProgressIndicator) -> Unit,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit = { log.error("Background task failed", it) }
    ) {
        if (!loading.compareAndSet(false, true)) {
            pendingRefresh.set(true)
            return
        }

        currentIndicator.get()?.cancel()

        object : Task.Backgroundable(project, taskTitle, true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator.set(indicator)
                run(indicator)
            }

            override fun onSuccess() = done { onSuccess() }

            override fun onThrowable(throwable: Throwable) = done { onError(throwable) }

            override fun onCancel() = done { log.info("$taskTitle cancelled") }

            private fun done(callback: () -> Unit) {
                loading.set(false)
                currentIndicator.set(null)
                callback()
                if (pendingRefresh.compareAndSet(true, false)) load()
            }
        }.queue()
    }
}
