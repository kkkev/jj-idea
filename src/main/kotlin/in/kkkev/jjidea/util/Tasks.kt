package `in`.kkkev.jjidea.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import java.awt.Component
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private val log = Logger.getInstance("in.kkkev.jjidea.util.Tasks")

private val saveAllDocumentsTimeoutMillis = TimeUnit.SECONDS.toMillis(10)

/**
 * Saves all open documents before a command runs. Called as the first statement of both
 * [in.kkkev.jjidea.jj.CommandExecutor.Command.executeAsync] and
 * [in.kkkev.jjidea.jj.CommandExecutor.Command.executeWithProgress], on the calling thread - which
 * is a pooled thread for both callers.
 *
 * ## Modality (jj-idea-c4tp, corrected by jj-idea-r5tx)
 * c4tp posted this with [ModalityState.any] to stop a modal dialog (e.g. the "Saving settings"
 * dialog at shutdown) from parking the calling pooled thread for the modal's entire lifetime.
 * That broke saving in two ways (GH #106): [ModalityState.any] is never registered write-safe by
 * [com.intellij.openapi.application.impl.TransactionGuardImpl.wrapLaterInvocation], so
 * [TransactionGuard.isWritingAllowed] is `false` inside the posted runnable even with no modal
 * showing, which both fails the old `isWriteSafeModality(current())` guard's premise and trips
 * the platform's own write-safety assertion in `FileDocumentManagerImpl.saveAllDocuments`; and
 * separately, [ModalityState.any] is documented as forbidden for VFS/PSI/project-model work, which
 * a document save is - so the save could never legally run under it regardless.
 *
 * Instead, post with [ModalityState.nonModal] - which *is* write-safe - and bound the wait with a
 * timeout rather than blocking indefinitely, preserving c4tp's "don't park behind a modal forever"
 * property without borrowing `any()`. If already on the EDT (some callers reach this via
 * [runLater]), save inline instead of re-queuing.
 */
fun saveAllDocuments() {
    val app = ApplicationManager.getApplication()
    if (app.isDisposed) return

    if (app.isDispatchThread) {
        saveIfWritingAllowed()
        return
    }

    val latch = CountDownLatch(1)
    app.invokeLater(
        {
            try {
                saveIfWritingAllowed()
            } finally {
                latch.countDown()
            }
        },
        ModalityState.nonModal(),
        app.disposed
    )
    if (!latch.await(saveAllDocumentsTimeoutMillis, TimeUnit.MILLISECONDS)) {
        log.warn(
            "saveAllDocuments timed out after ${saveAllDocumentsTimeoutMillis}ms waiting for the EDT; " +
                "proceeding without saving"
        )
    }
}

/**
 * On platform 2025.1, [TransactionGuard.isWritingAllowed] itself asserts the write-intent lock
 * is held (`ApplicationImpl.assertWriteIntentLockAcquired`) - calling it from a raw EDT dispatch
 * that hasn't acquired that lock throws, even though the call is only a check, not a write. Later
 * platform versions don't assert this. Acquiring the lock via [runWriteIntentReadAction] before
 * calling [TransactionGuard.isWritingAllowed] (rather than after, as before) satisfies 2025.1's
 * assertion on every platform version, since real EDT dispatch already holds this lock in
 * practice and nested acquisition is a no-op.
 */
private fun saveIfWritingAllowed() {
    val app = ApplicationManager.getApplication()
    app.runWriteIntentReadAction<Unit, Nothing> {
        if (TransactionGuard.getInstance().isWritingAllowed()) {
            FileDocumentManager.getInstance().saveAllDocuments()
        } else {
            log.info("saveAllDocuments skipped: writing is not allowed in modality ${ModalityState.current()}")
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
