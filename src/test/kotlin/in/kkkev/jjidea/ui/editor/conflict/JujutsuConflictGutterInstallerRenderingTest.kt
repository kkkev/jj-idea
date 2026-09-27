package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.markup.RangeHighlighter
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
 * (jj-idea-82fo follow-up: per-side icons + background highlighting) -
 * `JujutsuConflictGutterInstallerTest`'s mocked-`Editor` tests can only prove
 * [JujutsuConflictGutterInstaller.shouldInstall]'s own gate logic; they can't catch a mistake in
 * how [com.intellij.diff.util.DiffGutterOperation.Simple]/`addRangeHighlighter` actually attach
 * to a real editor's real markup model, which is exactly the kind of gap a "banner shows,
 * gutter icon doesn't" report needs a real editor to rule in or out.
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

    private fun stubJjRepo(repo: JujutsuRepository?) {
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        every { any<Project>().jujutsuRepositoryByAncestry(any<VirtualFile>()) } returns repo
    }

    @Test
    fun `a conflicted file's editor gets one gutter icon per side, on that side's own first line`() {
        stubJjRepo(mockk<JujutsuRepository>())

        WriteIntentReadAction.run {
            val text = ConflictMarkerFixtures.gitWithBase
            val file = LightVirtualFile("file.txt", text)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(document, project.get(), file, false, EditorKind.MAIN_EDITOR)
            try {
                val highlighters = editor.markupModel.allHighlighters
                val gutterIcons = highlighters.mapNotNull { it.gutterIconRenderer }

                // side1, base, side2 - one icon each, all the same icon glyph.
                gutterIcons.size shouldBe 3
                gutterIcons.forEach { it.icon shouldBe AllIcons.Vcs.Merge }

                // Each icon sits at the offset of its own side's first (only) content line.
                val iconOffsets = highlighters.filter { it.gutterIconRenderer != null }.map { it.startOffset }
                iconOffsets.map { text.substring(it, minOf(it + 3, text.length)) } shouldContainExactlyInAnyOrder
                    listOf("our", "bas", "the")
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }

    @Test
    fun `each side gets its own background highlighter spanning exactly its own raw text`() {
        stubJjRepo(mockk<JujutsuRepository>())

        WriteIntentReadAction.run {
            val text = ConflictMarkerFixtures.gitWithBase
            val file = LightVirtualFile("file.txt", text)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(document, project.get(), file, false, EditorKind.MAIN_EDITOR)
            try {
                val backgroundHighlighters = colorHighlighters(editor.markupModel.allHighlighters)
                val spans = backgroundHighlighters.map { text.substring(it.startOffset, it.endOffset) }

                spans.shouldContainExactlyInAnyOrder("ours content\n", "base content\n", "theirs content\n")
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }

    @Test
    fun `a diff-style block's derived base gets no background highlighter, only side1 and side2 do`() {
        stubJjRepo(mockk<JujutsuRepository>())

        WriteIntentReadAction.run {
            val text = ConflictMarkerFixtures.diffDestinationFirst
            val file = LightVirtualFile("file.txt", text)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(document, project.get(), file, false, EditorKind.MAIN_EDITOR)
            try {
                val backgroundHighlighters = colorHighlighters(editor.markupModel.allHighlighters)

                backgroundHighlighters.size shouldBe 2 // side1 + side2 only, never a DIFF-derived base
                // And no base gutter icon either (choicesFor excludes BASE for DIFF style).
                editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer }.size shouldBe 2
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }

    @Test
    fun `a clean file's editor gets no gutter icon and no side highlighter`() {
        stubJjRepo(mockk<JujutsuRepository>())

        WriteIntentReadAction.run {
            val file = LightVirtualFile("clean.txt", "no conflicts here\n")
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(document, project.get(), file, false, EditorKind.MAIN_EDITOR)
            try {
                editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer } shouldBe emptyList()
                colorHighlighters(editor.markupModel.allHighlighters) shouldBe emptyList()
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }

    @Test
    fun `no icon or highlighter for a file outside any jj repo, even though its text has conflict markers`() {
        stubJjRepo(null)

        WriteIntentReadAction.run {
            val file = LightVirtualFile("file.txt", ConflictMarkerFixtures.gitWithBase)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(document, project.get(), file, false, EditorKind.MAIN_EDITOR)
            try {
                editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer } shouldBe emptyList()
                colorHighlighters(editor.markupModel.allHighlighters) shouldBe emptyList()
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }

    /** The side-background highlighters this feature adds - keyed by one of our own [DiffColors], never a gutter icon. */
    private fun colorHighlighters(highlighters: Array<RangeHighlighter>): List<RangeHighlighter> {
        val ourKeys = setOf(DiffColors.DIFF_DELETED, DiffColors.DIFF_INSERTED, DiffColors.DIFF_MODIFIED)
        return highlighters.filter { it.gutterIconRenderer == null && it.textAttributesKey in ourKeys }
    }
}
