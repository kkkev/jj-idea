package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.text.Element
import org.junit.jupiter.api.Tag as JupiterTag

private const val PREVIEW_PROPERTY = "jjidea.preview.dragAndDrop"

/**
 * Coverage for [IconAwareHtmlPane.refDragPayload]/[installRefDragSource] (jj-idea-4ji7, batch 4)
 * and the caret drag-select suppression in [IconAwareHtmlPane.processMouseMotionEvent] that makes
 * a chip draggable in the first place - the pane has no `TransferHandler` to fight (see
 * [installRefDragSource]'s doc), only the inherited `JTextComponent` caret's own drag-select.
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class IconAwareHtmlPaneDnDTest {
    private val project = projectFixture()

    // refUri()'s host segment is the repo's directory path (URL-encoded) - an unstubbed relaxed
    // mock returns "", collapsing the URI's authority and making it unparseable. Same stub
    // LogClickTargetTest/JujutsuLogTableDnDChipTest already use.
    private val repo = mockk<JujutsuRepository>(relaxed = true).also { every { it.directory.path } returns "/repo" }

    @AfterEach
    fun cleanUp() {
        System.clearProperty(PREVIEW_PROPERTY)
    }

    private fun entry(id: String, bookmarks: List<Bookmark> = emptyList(), tags: List<Tag> = emptyList()) =
        LogEntry(
            repo = repo,
            id = ChangeId(id, id, null),
            commitId = CommitId("commit-$id"),
            underlyingDescription = "desc $id",
            bookmarks = bookmarks,
            tags = tags
        )

    private fun collectImgElements(root: Element): List<Element> {
        val result = mutableListOf<Element>()
        fun collect(e: Element) {
            if (e.name == "img") result.add(e)
            for (i in 0 until e.elementCount) collect(e.getElement(i))
        }
        collect(root)
        return result
    }

    /** Builds a pane whose sole content is [entry]'s [kind]/[name] ref rendered as a chip. */
    private fun paneWithChip(entry: LogEntry, kind: String, name: String): IconAwareHtmlPane {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                linked(refUri(entry, kind, name)) {
                    appendUnbreakable {
                        append(icon(JujutsuIcons::Bookmark))
                        append(name)
                    }
                }
            }
        }
        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()
        return pane
    }

    private fun chipPoint(pane: IconAwareHtmlPane): Point {
        val chip = collectImgElements(pane.document.defaultRootElement).single()
        val bounds = pane.modelToView2D(chip.startOffset).bounds
        return Point(bounds.x + 1, bounds.centerY.toInt())
    }

    // region refDragPayload

    @Test
    fun `refDragPayload resolves a BookmarkRef for a point over a bookmark chip`() {
        val bookmark = Bookmark("main")
        val a = entry("aaaaaaaa", bookmarks = listOf(bookmark))
        val pane = paneWithChip(a, "bookmark", "main")

        val payload = pane.refDragPayload(chipPoint(pane), project.get(), listOf(a))

        payload.shouldNotBeNull()
        payload as DragPayload.BookmarkRef
        payload.repo shouldBe a.repo
        payload.id shouldBe a.id
        payload.bookmark shouldBe bookmark
    }

    @Test
    fun `refDragPayload resolves a TagRef for a point over a tag chip`() {
        val tag = Tag("v1")
        val a = entry("aaaaaaaa", tags = listOf(tag))
        val pane = paneWithChip(a, "tag", "v1")

        val payload = pane.refDragPayload(chipPoint(pane), project.get(), listOf(a))

        payload.shouldNotBeNull()
        payload as DragPayload.TagRef
        payload.repo shouldBe a.repo
        payload.id shouldBe a.id
        payload.tag shouldBe tag
    }

    @Test
    fun `refDragPayload is null for a point not over any chip`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("plain text, no chip")
            }
        }
        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        pane.refDragPayload(Point(5, 5), project.get(), emptyList()).shouldBeNull()
    }

    @Test
    fun `refDragPayload is null when the chip's change is no longer in entries - a stale mid-drag call`() {
        val bookmark = Bookmark("main")
        val a = entry("aaaaaaaa", bookmarks = listOf(bookmark))
        val pane = paneWithChip(a, "bookmark", "main")

        pane.refDragPayload(chipPoint(pane), project.get(), emptyList()).shouldBeNull()
    }

    // endregion

    // region caret drag-select suppression

    private fun press(pane: IconAwareHtmlPane, point: Point) {
        pane.dispatchEvent(
            MouseEvent(pane, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, point.x, point.y, 1, false)
        )
    }

    private fun drag(pane: IconAwareHtmlPane, point: Point): MouseEvent {
        val event =
            MouseEvent(pane, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), 0, point.x, point.y, 1, false)
        pane.dispatchEvent(event)
        return event
    }

    @Test
    fun `a drag that started on a chip is consumed - the caret must not extend a selection over it`() {
        val bookmark = Bookmark("main")
        val a = entry("aaaaaaaa", bookmarks = listOf(bookmark))
        val pane = paneWithChip(a, "bookmark", "main")
        val point = chipPoint(pane)

        press(pane, point)
        val dragged = drag(pane, Point(point.x + 20, point.y))

        dragged.isConsumed shouldBe true
    }

    @Test
    fun `a drag that started on plain text is left alone - normal text selection still works`() {
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("plain text, no chip, long enough to drag across")
            }
        }
        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()

        press(pane, Point(5, 5))
        val dragged = drag(pane, Point(60, 5))

        dragged.isConsumed shouldBe false
    }

    @Test
    fun `a later drag after releasing over a chip is not suppressed - the flag resets on release`() {
        val bookmark = Bookmark("main")
        val a = entry("aaaaaaaa", bookmarks = listOf(bookmark))
        val html = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                linked(refUri(a, "bookmark", "main")) {
                    appendUnbreakable {
                        append(icon(JujutsuIcons::Bookmark))
                        append("main")
                    }
                }
                append(" plenty of plain text follows the chip on this same line to click on")
            }
        }
        val pane = IconAwareHtmlPane(project.get())
        pane.text = html
        pane.setSize(2000, 1000)
        pane.doLayout()
        val chip = chipPoint(pane)
        // Well to the right of the chip's own end, still on the same (unwrapped, 2000px-wide) line -
        // lands in the plain-text run that follows it, never back on the chip itself.
        val plainTextPoint = Point(chip.x + 300, chip.y)

        press(pane, chip)
        pane.dispatchEvent(
            MouseEvent(pane, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, chip.x, chip.y, 1, false)
        )
        press(pane, plainTextPoint)
        val dragged = drag(pane, Point(plainTextPoint.x + 20, plainTextPoint.y))

        dragged.isConsumed shouldBe false
    }

    // endregion

    // region gating

    @Test
    fun `installRefDragSource does not throw with the preview feature off`() {
        val a = entry("aaaaaaaa")
        val pane = paneWithChip(a, "bookmark", "main")

        pane.installRefDragSource(project.get(), project.get()) { listOf(a) }
    }

    @Test
    fun `installRefDragSource does not throw with the preview feature on`() {
        System.setProperty(PREVIEW_PROPERTY, "true")
        val a = entry("aaaaaaaa")
        val pane = paneWithChip(a, "bookmark", "main")

        // DnDManager is a no-op in tests (HeadlessDnDManager), same as the other install-site
        // tests - this only asserts the gated call itself is safe; refDragPayload above covers
        // the actual behaviour the builder wires up.
        pane.installRefDragSource(project.get(), project.get()) { listOf(a) }
    }

    // endregion
}
