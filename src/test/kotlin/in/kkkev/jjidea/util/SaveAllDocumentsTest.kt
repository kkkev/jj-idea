package `in`.kkkev.jjidea.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerBase
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
class SaveAllDocumentsTest {
    private fun assertNoErrorLogged(block: () -> Unit) {
        var loggedAnything = false
        LoggedErrorProcessor.executeWith<RuntimeException>(
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<String>,
                    t: Throwable?
                ): Set<Action> {
                    loggedAnything = true
                    return Action.NONE
                }
            },
            block
        )
        loggedAnything shouldBe false
    }

    // runReadActionBlocking is 2026.x-only and runReadAction/ReadAction.compute are deprecated
    // there; Application.runReadAction(Computable) is non-deprecated on the whole supported
    // range (sinceBuild=251 .. 2026.2). See contributing.md § Platform API compatibility.
    private fun isUnsaved(document: Document): Boolean =
        ApplicationManager.getApplication()
            .runReadAction(Computable { FileDocumentManager.getInstance().isDocumentUnsaved(document) })

    @Test
    fun `saveAllDocuments from a pooled thread does not log a write-unsafe error`() {
        assertNoErrorLogged { runInBackground { saveAllDocuments() }.get() }
    }

    @Test
    fun `saveAllDocuments from the EDT does not log a write-unsafe error`() {
        assertNoErrorLogged { ApplicationManager.getApplication().invokeAndWait { saveAllDocuments() } }
    }

    @Test
    fun `saveAllDocuments actually saves unsaved document content`() {
        val file = LightVirtualFile("test.txt", "original")
        file.putUserData(FileDocumentManagerBase.TRACK_NON_PHYSICAL, true)
        lateinit var document: Document
        ApplicationManager.getApplication().invokeAndWait {
            document = FileDocumentManager.getInstance().getDocument(file)!!
            runWriteAction { document.setText("edited") }
        }

        isUnsaved(document) shouldBe true

        runInBackground { saveAllDocuments() }.get()

        isUnsaved(document) shouldBe false
    }
}
