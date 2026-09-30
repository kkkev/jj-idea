package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Tag
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRootPane
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * Platform-level coverage for [DropHint] (jj-idea-ymuu): the text hint next to the cursor that
 * replaces the platform's `ide.dnd.textHints`-gated (default off) drop tooltip. Uses a real
 * [JRootPane] so the hint lands in a real layered pane, like the log table and bookmarks tree do.
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class DropHintTest {
    private val project = projectFixture()
    private val rootPane = JRootPane()
    private val surface = JPanel()
    private val hints = mutableListOf<DropHint>()

    // The hint's pane holds the project, so dispose every hint made (LeakHunter would otherwise flag
    // a retained Project, as in JujutsuLogTableDnDTest).
    private fun newHint() = DropHint(project.get()).also { hints += it }

    @AfterEach
    fun disposeHints() = hints.forEach { it.dispose() }

    @BeforeEach
    fun layOut() {
        rootPane.setSize(400, 300)
        rootPane.contentPane.add(surface)
        rootPane.doLayout()
        rootPane.layeredPane.setSize(400, 300)
        surface.setSize(400, 300)
    }

    private fun hintsInPane() = rootPane.layeredPane.getComponentsInLayer(DropHint.HINT_LAYER).toList()

    @Test
    fun `show adds exactly one component carrying the text`() {
        val hint = newHint()

        hint.show(surface, Point(50, 50), DropMessage.of("Cannot drop across repositories"), rejected = true)

        hintsInPane() shouldHaveSize 1
        hint.shownText shouldBe "Cannot drop across repositories"
    }

    @Test
    fun `showing again retexts and moves the same component instead of adding another`() {
        val hint = newHint()
        hint.show(surface, Point(50, 50), DropMessage.of("first"), rejected = false)
        val component = hint.component.shouldNotBeNull()
        val firstBounds = component.bounds

        hint.show(surface, Point(120, 90), DropMessage.of("second, and longer"), rejected = false)

        hintsInPane() shouldHaveSize 1
        hint.component shouldBe component
        hint.shownText shouldBe "second, and longer"
        // Anchored 16px right of / below the cursor, and moved (not left where the first show put it).
        component.bounds.x shouldBe 136
        component.bounds.y shouldBe 106
        (component.bounds == firstBounds) shouldBe false
    }

    @Test
    fun `hide makes the hint invisible and is idempotent`() {
        val hint = newHint()
        hint.show(surface, Point(50, 50), DropMessage.of("text"), rejected = false)

        hint.hide()
        hint.hide()

        hint.shownText.shouldBeNull()
        hint.component.shouldNotBeNull().isVisible shouldBe false
    }

    @Test
    fun `hide before any show does nothing`() {
        val hint = newHint()

        hint.hide()

        hint.component.shouldBeNull()
        hintsInPane() shouldHaveSize 0
    }

    @Test
    fun `blank text hides an already visible hint`() {
        val hint = newHint()
        hint.show(surface, Point(50, 50), DropMessage.of("text"), rejected = false)

        hint.show(surface, Point(50, 50), DropMessage.of("  "), rejected = false)

        hint.shownText.shouldBeNull()
    }

    @Test
    fun `dispose removes the component from the layered pane`() {
        val hint = newHint()
        hint.show(surface, Point(50, 50), DropMessage.of("text"), rejected = false)

        hint.dispose()

        hintsInPane() shouldHaveSize 0
        hint.component.shouldBeNull()
    }

    @Test
    fun `the hint is clamped inside the layered pane at the bottom-right edge`() {
        val hint = newHint()

        hint.show(surface, Point(399, 299), DropMessage.of("a fairly long reason that would overflow"), rejected = true)

        val bounds = hint.component.shouldNotBeNull().bounds
        bounds.x shouldBeGreaterThanOrEqual 0
        bounds.y shouldBeGreaterThanOrEqual 0
        (bounds.x + bounds.width) shouldBeLessThanOrEqual 400
        (bounds.y + bounds.height) shouldBeLessThanOrEqual 300
    }

    @Test
    fun `the hint sits above RejectOverlay's layer`() {
        (DropHint.HINT_LAYER > javax.swing.JLayeredPane.DRAG_LAYER) shouldBe true
    }

    @Test
    fun `nothing is shown when the platform's own text hints are on`() {
        val disposable = Disposer.newDisposable()
        try {
            Registry.get(DropHint.PLATFORM_HINTS_KEY).setValue(true, disposable)
            val hint = newHint()

            hint.show(surface, Point(50, 50), DropMessage.of("text"), rejected = true)

            hint.shownText.shouldBeNull()
            hintsInPane() shouldHaveSize 0
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `the label carries the message as html with the id styled`() {
        val hint = newHint()

        hint.show(
            surface,
            Point(50, 50),
            DropMessage.of("Rebase ", ChangeId("kkmpztqrvwxy", "kk", null)),
            rejected = false
        )

        hint.shownHtml.shouldNotBeNull().let {
            it shouldContain "<html>"
            it shouldContain "<b>kk</b>"
        }
    }

    @Test
    fun `an unchanged message does not rebuild the label on every drag-over tick`() {
        val hint = newHint()
        val message = DropMessage.of("Edit ", ChangeId("kkmpztqrvwxy", "kk", null))
        hint.show(surface, Point(50, 50), message, rejected = false)
        val pane = hint.pane.shouldNotBeNull()
        pane.text = "sentinel"

        hint.show(surface, Point(60, 60), message, rejected = false)

        pane.text shouldContain "sentinel"
    }

    @Test
    fun `a long message wraps instead of growing wider than the maximum`() {
        val hint = newHint()
        hint.show(surface, Point(10, 10), DropMessage.of("short"), rejected = false)
        val oneLineHeight = hint.component.shouldNotBeNull().bounds.height
        val long = DropMessage.of("Rebase ${"a fairly long description of scope ".repeat(12)}onto somewhere")

        hint.show(surface, Point(10, 10), long, rejected = false)

        val bounds = hint.component.shouldNotBeNull().bounds
        bounds.width shouldBeLessThanOrEqual DropHint.scaledMaxWidth()
        bounds.height shouldBeGreaterThan oneLineHeight
    }

    @Test
    fun `hovering the hint does not make it a drop target`() {
        val hint = newHint()
        hint.show(surface, Point(50, 50), DropMessage.of("text"), rejected = false)

        val component = hint.component.shouldNotBeNull()

        component.dropTarget.shouldBeNull()
        hint.pane.shouldNotBeNull().dropTarget.shouldBeNull()
    }

    @Test
    fun `a bookmark and a tag render as icon chips like the log, not plain text`() {
        val hint = newHint()

        hint.show(
            surface,
            Point(50, 50),
            DropMessage.of("Move bookmark ", Bookmark("main"), " to ", Tag("v1")),
            rejected = false
        )

        hint.shownHtml.shouldNotBeNull().let {
            it shouldContain "main"
            it shouldContain "v1"
            it shouldContain "<img" // each chip leads with its icon element, resolved by IconAwareHtmlPane
        }
    }

    @Test
    fun `a long bookmark name gets a line of its own, in full`() {
        val hint = newHint()
        val name = "really-long-bookmark-name-that-spills-over-the-end"

        hint.show(
            surface,
            Point(10, 10),
            DropMessage.of("Move bookmark ", Bookmark(name), " to ", ChangeId("kkmpztqrvwxy", "kk", null)),
            rejected = false
        )

        hint.shownHtml.shouldNotBeNull().let {
            chipContents(it) shouldContain name // fits the bubble, so not shortened
            it.split("<br>").size shouldBe 3
        }
    }

    @Test
    fun `a bookmark name wider than the bubble is ellipsized so the chip is never clipped`() {
        val hint = newHint()
        val name = "really-long-bookmark-name-that-spills-over-the-end".repeat(4)

        hint.show(
            surface,
            Point(10, 10),
            DropMessage.of("Move bookmark ", Bookmark(name), " to ", ChangeId("kkmpztqrvwxy", "kk", null)),
            rejected = false
        )

        // Chip content is URL-encoded inside its `unbreakable:` <img src>; decode to look inside it.
        chipContents(hint.shownHtml.shouldNotBeNull()).let {
            it shouldContain "…"
            it shouldNotContain name
        }
    }

    @Test
    fun `a short bookmark name stays inline`() {
        val hint = newHint()

        hint.show(
            surface,
            Point(10, 10),
            DropMessage.of("Move bookmark ", Bookmark("main"), " to ", ChangeId("kkmpztqrvwxy", "kk", null)),
            rejected = false
        )

        hint.shownHtml.shouldNotBeNull() shouldNotContain "<br>"
    }

    @Test
    fun `a component outside any root pane is a no-op`() {
        val hint = newHint()
        val detached: JComponent = JPanel()

        hint.show(detached, Point(5, 5), DropMessage.of("text"), rejected = false)

        hint.shownText.shouldBeNull()
    }
}
