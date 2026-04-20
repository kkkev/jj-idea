package `in`.kkkev.jjidea.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.awt.Component
import java.util.concurrent.Future

fun saveAllDocuments() {
    if (TransactionGuard.getInstance().isWriteSafeModality(ModalityState.current())) {
        ApplicationManager.getApplication().runWriteIntentReadAction<Unit, Nothing> {
            FileDocumentManager.getInstance().saveAllDocuments()
        }
    }
}

private val capturedModality = ThreadLocal<ModalityState>()

fun <T> runInBackground(action: () -> T): Future<T> {
    val modality = ModalityState.defaultModalityState()
    return ApplicationManager.getApplication().executeOnPooledThread<T> {
        capturedModality.set(modality)
        try {
            action()
        } finally {
            capturedModality.remove()
        }
    }
}

fun runLater(action: () -> Unit) {
    val modality = capturedModality.get() ?: ModalityState.defaultModalityState()
    ApplicationManager.getApplication().invokeLater({ action() }, modality)
}

fun runLaterInModal(component: Component, action: () -> Unit) =
    ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.stateForComponent(component))
