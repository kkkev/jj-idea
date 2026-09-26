package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.conflict.ConflictMarkerFixtures
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryByAncestry
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * End-to-end sanity check for [JujutsuConflictGutterInstaller] against a *real* platform
 * [com.intellij.openapi.editor.Editor] and [com.intellij.openapi.editor.markup.MarkupModel]
 * (jj-idea-82fo follow-up) - `JujutsuConflictGutterInstallerTest`'s mocked-`Editor` tests can
 * only prove [JujutsuConflictGutterInstaller.shouldInstall]'s own gate logic; they can't catch a
 * mistake in how [com.intellij.diff.util.DiffGutterOperation.Simple] actually attaches to a real
 * editor's real markup model, which is exactly the kind of gap a "banner shows, gutter icon
 * doesn't" report needs a real editor to rule in or out.
 *
 * Deliberately does **not** call [JujutsuConflictGutterInstaller.editorCreated] directly -
 * [EditorFactory.createEditor] under `@TestApplication` already dispatches to every registered
 * `com.intellij.editorFactoryListener`, this class's real, `plugin.xml`-registered instance
 * included, exactly as it would in a running IDE. Calling it a second time here would double up
 * (and did, the first time this test was written - two gutter icons instead of one, which is
 * exactly the kind of double-install bug a real dispatch path catches that a mocked `Editor`
 * never could).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuConflictGutterInstallerRenderingTest {
    private val project = projectFixture()

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a conflicted file's editor gets a real gutter icon renderer on its opening marker line`() {
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        val repo = mockk<JujutsuRepository>()
        every { any<Project>().jujutsuRepositoryByAncestry(any<VirtualFile>()) } returns repo

        WriteIntentReadAction.run {
            val file = LightVirtualFile("file.txt", ConflictMarkerFixtures.gitWithBase)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(document, project.get(), file, false, EditorKind.MAIN_EDITOR)
            try {
                val gutterIcons = editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer }
                gutterIcons.size shouldBe 1
                gutterIcons.single().icon shouldBe AllIcons.Vcs.Merge
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }

    @Test
    fun `a clean file's editor gets no gutter icon`() {
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        val repo = mockk<JujutsuRepository>()
        every { any<Project>().jujutsuRepositoryByAncestry(any<VirtualFile>()) } returns repo

        WriteIntentReadAction.run {
            val file = LightVirtualFile("clean.txt", "no conflicts here\n")
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(document, project.get(), file, false, EditorKind.MAIN_EDITOR)
            try {
                editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer } shouldBe emptyList()
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }

    @Test
    fun `no icon for a file outside any jj repo, even though its text has conflict markers`() {
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        every { any<Project>().jujutsuRepositoryByAncestry(any<VirtualFile>()) } returns null

        WriteIntentReadAction.run {
            val file = LightVirtualFile("file.txt", ConflictMarkerFixtures.gitWithBase)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(document, project.get(), file, false, EditorKind.MAIN_EDITOR)
            try {
                editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer } shouldBe emptyList()
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }
}
