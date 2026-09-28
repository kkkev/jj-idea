package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.conflict.ConflictMarkerFixtures
import `in`.kkkev.jjidea.jj.conflict.ConflictRegionScanner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.awt.event.MouseEvent

/**
 * [ConflictSideHover] (jj-idea-sr42) against a real platform [Editor]/`MarkupModel` - a mocked
 * `Editor` can't prove `addRangeHighlighter` actually attaches, or that
 * [Editor.getColorsScheme]'s [com.intellij.openapi.diff.DiffColors] attributes resolve to a real
 * background - see [JujutsuConflictGutterInstallerRenderingTest]'s own doc for why this codebase
 * always backs this kind of markup-model claim with a real editor.
 *
 * Builds [ConflictSideHover] directly over a plain [ConflictRegionScanner] rather than through
 * [JujutsuConflictGutterInstaller] - that installer's own repository gate is exercised by
 * [JujutsuConflictGutterInstallerRenderingTest] already; this class only needs a real editor to
 * hang a hover highlighter off, and feeds [EditorMouseEvent]s directly rather than driving real
 * Swing dispatch.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class ConflictSideHoverTest {
    private val project = projectFixture()

    @Test
    fun `hovering a side's text shows exactly one hover highlighter spanning that side's own text`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            val offset = fx.text.indexOf("theirs content")
            fx.hover.mouseMoved(fx.mouseEvent(offset))

            val highlighters = fx.textHoverHighlighters()
            highlighters.size shouldBe 1
            fx.text.substring(highlighters[0].startOffset, highlighters[0].endOffset) shouldBe "theirs content\n"
        }
    }

    @Test
    fun `hovering a side's text also adds one icon-background highlighter, anchored at the icon's own offset`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            val offset = fx.text.indexOf("theirs content")
            fx.hover.mouseMoved(fx.mouseEvent(offset))

            val icons = fx.iconHoverHighlighters()
            icons.size shouldBe 1
            icons[0].startOffset shouldBe offset
            icons[0].endOffset shouldBe offset // zero-length - anchored at one offset, not a span
            icons[0].lineMarkerRenderer shouldBe ConflictIconHoverBackground
        }
    }

    @Test
    fun `further moves within the same side reuse the same highlighter instances - no churn`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            val start = fx.text.indexOf("theirs content")
            fx.hover.mouseMoved(fx.mouseEvent(start))
            val firstText = fx.textHoverHighlighters().single()
            val firstIcon = fx.iconHoverHighlighters().single()

            fx.hover.mouseMoved(fx.mouseEvent(start + 1))
            fx.hover.mouseMoved(fx.mouseEvent(start + "theirs content".length - 1))

            fx.textHoverHighlighters().single() shouldBe firstText
            fx.iconHoverHighlighters().single() shouldBe firstIcon
        }
    }

    @Test
    fun `moving onto a marker line clears both the text and icon hover`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            fx.hover.mouseMoved(fx.mouseEvent(fx.text.indexOf("theirs content")))
            fx.textHoverHighlighters().size shouldBe 1
            fx.iconHoverHighlighters().size shouldBe 1

            fx.hover.mouseMoved(fx.mouseEvent(fx.text.indexOf(">>>>>>>")))
            fx.textHoverHighlighters() shouldBe emptyList()
            fx.iconHoverHighlighters() shouldBe emptyList()
        }
    }

    @Test
    fun `moving from one side to another swaps both highlighters`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            fx.hover.mouseMoved(fx.mouseEvent(fx.text.indexOf("ours content")))
            val oursText = fx.textHoverHighlighters().single()
            val oursIcon = fx.iconHoverHighlighters().single()

            fx.hover.mouseMoved(fx.mouseEvent(fx.text.indexOf("base content")))
            val baseText = fx.textHoverHighlighters().single()
            val baseIcon = fx.iconHoverHighlighters().single()

            baseText shouldNotBe oursText
            baseIcon shouldNotBe oursIcon
            fx.text.substring(baseText.startOffset, baseText.endOffset) shouldBe "base content\n"
            baseIcon.startOffset shouldBe fx.text.indexOf("base content")
        }
    }

    @Test
    fun `a gutter-row event at a side's own first line hovers that side`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            val offset = fx.text.indexOf("ours content")
            fx.hover.mouseMoved(fx.mouseEvent(offset, EditorMouseEventArea.LINE_MARKERS_AREA))

            val highlighters = fx.textHoverHighlighters()
            highlighters.size shouldBe 1
            fx.text.substring(highlighters[0].startOffset, highlighters[0].endOffset) shouldBe "ours content\n"
            fx.iconHoverHighlighters().size shouldBe 1
        }
    }

    @Test
    fun `mouseExited clears the hover`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            fx.hover.mouseMoved(fx.mouseEvent(fx.text.indexOf("ours content")))
            fx.textHoverHighlighters().size shouldBe 1
            fx.iconHoverHighlighters().size shouldBe 1

            fx.hover.mouseExited(fx.mouseEvent(fx.text.indexOf("ours content")))
            fx.textHoverHighlighters() shouldBe emptyList()
            fx.iconHoverHighlighters() shouldBe emptyList()
        }
    }

    @Test
    fun `scrolling the editor clears the hover - the one case with no mouseMoved event at all`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            fx.hover.mouseMoved(fx.mouseEvent(fx.text.indexOf("ours content")))
            fx.textHoverHighlighters().size shouldBe 1
            fx.iconHoverHighlighters().size shouldBe 1

            fx.hover.visibleAreaChanged(
                VisibleAreaEvent(fx.editor, Rectangle(0, 0, 100, 100), Rectangle(0, 20, 100, 100))
            )

            fx.textHoverHighlighters() shouldBe emptyList()
            fx.iconHoverHighlighters() shouldBe emptyList()
        }
    }

    @Test
    fun `scrolling with nothing hovered is a harmless no-op`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            fx.hover.visibleAreaChanged(
                VisibleAreaEvent(fx.editor, Rectangle(0, 0, 100, 100), Rectangle(0, 20, 100, 100))
            )

            fx.textHoverHighlighters() shouldBe emptyList()
            fx.iconHoverHighlighters() shouldBe emptyList()
        }
    }

    @Test
    fun `a document edit clears a stale hover`() {
        withEditor(ConflictMarkerFixtures.gitWithBase).use { fx ->
            fx.hover.mouseMoved(fx.mouseEvent(fx.text.indexOf("ours content")))
            fx.textHoverHighlighters().size shouldBe 1
            fx.iconHoverHighlighters().size shouldBe 1

            WriteCommandAction.runWriteCommandAction(fx.editor.project) { fx.editor.document.insertString(0, "x") }

            fx.textHoverHighlighters() shouldBe emptyList()
            fx.iconHoverHighlighters() shouldBe emptyList()
        }
    }

    @Test
    fun `a diff-style block's raw diff section resolves to a real side - its derived base is never itself hoverable`() {
        withEditor(ConflictMarkerFixtures.diffDestinationFirst).use { fx ->
            // "-base content" is part of the %%%%%%% diff section's raw span, which belongs to
            // whichever real side that section derives (see ConflictSide.contentStartOffset's own
            // doc) - never to the separately-derived, offsetless ConflictBlock.base itself. Matches
            // ConflictHitTestTest's "sideAt never resolves BASE for it" pin at the pure level.
            val offset = fx.text.indexOf("-base content")
            fx.hover.mouseMoved(fx.mouseEvent(offset))

            val highlighters = fx.textHoverHighlighters()
            highlighters.size shouldBe 1
            fx.text.substring(highlighters[0].startOffset, highlighters[0].endOffset) shouldBe
                "-base content\n+destination content\n"
            fx.iconHoverHighlighters().size shouldBe 1
        }
    }

    private fun withEditor(text: String): Fixture = WriteIntentReadAction.compute<Fixture> {
        val file = LightVirtualFile("file.txt", text)
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        val editor = EditorFactory.getInstance().createEditor(
            document,
            project.get(),
            file,
            false,
            EditorKind.MAIN_EDITOR
        )
        val scanner = ConflictRegionScanner()
        scanner.fullScan(text)
        val hover = ConflictSideHover(editor) { scanner.blocks }
        hover.install()
        Fixture(editor, text, hover)
    }

    /** One test's editor + the text it holds + the [ConflictSideHover] under test - disposed together by [close]. */
    private class Fixture(val editor: Editor, val text: String, val hover: ConflictSideHover) : AutoCloseable {
        fun mouseEvent(offset: Int, area: EditorMouseEventArea = EditorMouseEventArea.EDITING_AREA): EditorMouseEvent {
            val point = editor.offsetToXY(offset)
            val swingEvent =
                MouseEvent(
                    editor.contentComponent,
                    MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(),
                    0,
                    point.x,
                    point.y,
                    0,
                    false
                )
            return EditorMouseEvent(
                editor,
                swingEvent,
                area,
                offset,
                editor.offsetToLogicalPosition(offset),
                editor.offsetToVisualPosition(offset),
                true,
                null,
                null,
                null
            )
        }

        /** [ConflictSideHover]'s own text-tint highlighter - non-zero-length and keyless (built from a raw [com.intellij.openapi.editor.markup.TextAttributes], not a [com.intellij.openapi.editor.colors.TextAttributesKey]). */
        fun textHoverHighlighters(): List<RangeHighlighter> =
            editor.markupModel.allHighlighters.filter { it.textAttributesKey == null && it.startOffset != it.endOffset }

        /** [ConflictSideHover]'s own icon-background highlighter - zero-length, keyless, carrying a [ConflictIconHoverBackground]. */
        fun iconHoverHighlighters(): List<RangeHighlighter> =
            editor.markupModel.allHighlighters.filter { it.textAttributesKey == null && it.startOffset == it.endOffset }

        override fun close() {
            WriteIntentReadAction.run {
                Disposer.dispose(hover)
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }
}
