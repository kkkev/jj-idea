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

fun saveAllDocuments() {
    ApplicationManager.getApplication().invokeAndWait {
        if (TransactionGuard.getInstance().isWriteSafeModality(ModalityState.current())) {
            ApplicationManager.getApplication().runWriteIntentReadAction<Unit, Nothing> {
                FileDocumentManager.getInstance().saveAllDocuments()
            }
        }
    }
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
