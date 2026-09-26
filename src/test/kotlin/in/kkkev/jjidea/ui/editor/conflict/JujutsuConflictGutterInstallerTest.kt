package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [JujutsuConflictGutterInstaller.shouldInstall]'s gate (jj-idea-82fo, stage 3/4) - extracted
 * pure so the plugin-wide `editorFactoryListener`'s no-op-everywhere-else contract is checked
 * without a live editor. Cheapest-first ordering itself isn't asserted here (that's an
 * implementation detail, not observable behavior) - only the end result per case.
 */
class JujutsuConflictGutterInstallerTest {
    private val project = mockk<Project>()
    private val document = mockk<Document>()
    private val file = mockk<VirtualFile>()
    private val repo = mockk<JujutsuRepository>()
    private val fileDocumentManager = mockk<FileDocumentManager>()
    private val editor = mockk<Editor> {
        every { editorKind } returns EditorKind.MAIN_EDITOR
        every { this@mockk.project } returns this@JujutsuConflictGutterInstallerTest.project
        every { this@mockk.document } returns this@JujutsuConflictGutterInstallerTest.document
    }

    @BeforeEach
    fun setup() {
        mockkStatic(FileDocumentManager::class)
        every { FileDocumentManager.getInstance() } returns fileDocumentManager
        every { fileDocumentManager.getFile(document) } returns file
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        every { project.isDisposed } returns false
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `installs for a main editor on a jj-tracked file`() {
        every { project.possibleJujutsuRepositoryFor(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe true
    }

    @Test
    fun `not for a file outside any jj repo`() {
        every { project.possibleJujutsuRepositoryFor(file) } returns null

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not for a diff viewer's editor`() {
        every { editor.editorKind } returns EditorKind.DIFF
        every { project.possibleJujutsuRepositoryFor(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not for a console editor`() {
        every { editor.editorKind } returns EditorKind.CONSOLE
        every { project.possibleJujutsuRepositoryFor(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not for a preview editor`() {
        every { editor.editorKind } returns EditorKind.PREVIEW
        every { project.possibleJujutsuRepositoryFor(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not when the editor has no project`() {
        every { editor.project } returns null

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not when the document has no backing virtual file`() {
        every { fileDocumentManager.getFile(document) } returns null

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not when the project is disposed`() {
        every { project.isDisposed } returns true
        every { project.possibleJujutsuRepositoryFor(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }
}
