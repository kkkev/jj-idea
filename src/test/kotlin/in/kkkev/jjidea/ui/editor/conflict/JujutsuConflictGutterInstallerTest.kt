package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuStateModel
import `in`.kkkev.jjidea.util.NotifiableState
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryByAncestry
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [JujutsuConflictGutterInstaller.shouldInstall]'s gate (jj-idea-82fo, stage 3/4) - extracted
 * pure so the plugin-wide `editorFactoryListener`'s no-op-everywhere-else contract is checked
 * without a live editor. Cheapest-first ordering itself isn't asserted here (that's an
 * implementation detail, not observable behavior) - only the end result per case.
 *
 * `retries once the repository state finishes loading` (jj-idea-82fo follow-up) covers a real
 * bug this reproduces: [editorCreated][JujutsuConflictGutterInstaller.editorCreated] fired
 * before [JujutsuStateModel.initialisedRepositories] had loaded, so the gutter icon never
 * appeared. (A second, unrelated bug found in the same investigation -
 * `possibleJujutsuRepositoryFor`'s `VcsUtil.getVcsRootFor` path returning null even once
 * initialised - is why [shouldInstall][JujutsuConflictGutterInstaller.shouldInstall] resolves
 * the repository via [in.kkkev.jjidea.vcs.jujutsuRepositoryByAncestry] instead; see that
 * function's own KDoc.)
 */
class JujutsuConflictGutterInstallerTest {
    private val project = mockk<Project>(relaxed = true)
    private val document = mockk<Document>(relaxed = true)
    private val file = mockk<VirtualFile>(relaxed = true)
    private val repo = mockk<JujutsuRepository>()
    private val fileDocumentManager = mockk<FileDocumentManager>()
    private val editor = mockk<Editor>(relaxed = true) {
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
        every { project.jujutsuRepositoryByAncestry(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe true
    }

    @Test
    fun `not for a file outside any jj repo`() {
        every { project.jujutsuRepositoryByAncestry(file) } returns null

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not for a diff viewer's editor`() {
        every { editor.editorKind } returns EditorKind.DIFF
        every { project.jujutsuRepositoryByAncestry(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not for a console editor`() {
        every { editor.editorKind } returns EditorKind.CONSOLE
        every { project.jujutsuRepositoryByAncestry(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `not for a preview editor`() {
        every { editor.editorKind } returns EditorKind.PREVIEW
        every { project.jujutsuRepositoryByAncestry(file) } returns repo

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
        every { project.jujutsuRepositoryByAncestry(file) } returns repo

        JujutsuConflictGutterInstaller.shouldInstall(editor) shouldBe false
    }

    @Test
    fun `retries once the repository state finishes loading, for an editor open before it did`() {
        val stateModel = mockk<JujutsuStateModel>()
        val initialisedRepositories = mockk<NotifiableState<Map<VirtualFile, JujutsuRepository>>>()
        every { project.getService(JujutsuStateModel::class.java) } returns stateModel
        every { stateModel.initialisedRepositories } returns initialisedRepositories
        val handler = slot<NotifiableState.Listener<Map<VirtualFile, JujutsuRepository>>>()
        every { initialisedRepositories.connectAndFireSync(any(), capture(handler)) } just Runs

        // Not loaded yet at editorCreated time - shouldInstall's own check fails.
        every { project.jujutsuRepositoryByAncestry(file) } returns null
        val event = mockk<EditorFactoryEvent> {
            every { this@mockk.editor } returns this@JujutsuConflictGutterInstallerTest.editor
        }
        JujutsuConflictGutterInstaller().editorCreated(event)
        handler.captured.changed(emptyMap()) // connectAndFireSync's own immediate, cold replay

        verify(exactly = 0) { document.addDocumentListener(any(), any()) } // not installed yet

        // The background load completes and publishes - now the repo is there.
        every { project.jujutsuRepositoryByAncestry(file) } returns repo
        handler.captured.changed(mapOf(mockk<VirtualFile>() to repo))

        verify(exactly = 1) { document.addDocumentListener(any(), any()) } // installed exactly once
    }
}
