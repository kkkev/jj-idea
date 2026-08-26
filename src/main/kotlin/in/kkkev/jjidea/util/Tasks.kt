package `in`.kkkev.jjidea.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import java.awt.Component
import java.util.concurrent.CancellationException
import java.util.concurrent.Future

private val log = Logger.getInstance("in.kkkev.jjidea.util.Tasks")

/**
 * Saves all open documents before a command runs. Called as the first statement of both
 * [in.kkkev.jjidea.jj.CommandExecutor.Command.executeAsync] and
 * [in.kkkev.jjidea.jj.CommandExecutor.Command.executeWithProgress], on the calling thread - which
 * is a pooled thread for both callers.
 *
 * ## Modality (jj-idea-c4tp)
 * Posts with [ModalityState.any] rather than the default modality. `invokeAndWait` with default
 * modality only ever runs the posted runnable once no modal dialog is showing - if a modal is up
 * (e.g. the "Saving settings" dialog at shutdown, or any other plugin's modal), the pooled thread
 * calling this would otherwise park for the modal's entire lifetime, which is one of the hazards
 * behind that hang. Under [ModalityState.any], the runnable runs promptly even inside a modal, and
 * the existing [TransactionGuard.isWriteSafeModality] guard then no-ops the save - the same
 * "skip rather than save" outcome an EDT caller already gets inside a modal, just without the
 * indefinite park.
 */
fun saveAllDocuments() {
    val app = ApplicationManager.getApplication()
    if (app.isDisposed) return
    app.invokeAndWait(
        {
            if (TransactionGuard.getInstance().isWriteSafeModality(ModalityState.current())) {
                app.runWriteIntentReadAction<Unit, Nothing> {
                    FileDocumentManager.getInstance().saveAllDocuments()
                }
            } else {
                log.info("saveAllDocuments skipped: modality ${ModalityState.current()} is not write-safe")
            }
        },
        ModalityState.any()
    )
}

private val capturedModality = ThreadLocal<ModalityState>()

/**
 * Runs [action] on a pooled thread. The returned [Future] is discarded by every call site in
 * this codebase, so an uncaught throwable is logged here rather than relying on `.get()` -
 * otherwise `executeOnPooledThread` swallows it into the Future with no trace (no idea.log
 * entry, no dialog, no notification), which looks to the user like a completely dead action.
 */
fun <T> runInBackground(
    modalityState: ModalityState = ModalityState.defaultModalityState(),
    action: () -> T
): Future<T> = ApplicationManager.getApplication().executeOnPooledThread<T> {
    capturedModality.set(modalityState)
    try {
        action()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log.error("Uncaught exception in background task", e)
        throw e
    } finally {
        capturedModality.remove()
    }
}

fun runLater(action: () -> Unit) {
    val modality = capturedModality.get() ?: ModalityState.defaultModalityState()
    ApplicationManager.getApplication().invokeLater({ action() }, modality)
}

fun runLaterInModal(component: Component, action: () -> Unit) =
    ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.stateForComponent(component))
