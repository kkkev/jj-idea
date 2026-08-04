package `in`.kkkev.jjidea.ui.components

import com.intellij.ide.IdeTooltip
import com.intellij.ide.TooltipEvent
import com.intellij.openapi.project.Project
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport

/**
 * Behaviour tests for jj-idea-wp12 (GitHub #51 point 3): the log hover tooltip must stay open
 * while the pointer moves towards/into the balloon - so its hyperlinks and selectable text are
 * reachable - and must dismiss when the owner's enclosing viewport scrolls, instead of blocking
 * the view and going stale.
 *
 * Uses a fake [TooltipHost] instead of the real [com.intellij.ide.IdeTooltipManager] so these run
 * as plain unit tests, no platform classpath needed: the suppressed-after-scroll path in
 * `beforeShow()` returns before touching any platform pane, and [TooltipEvent] has a public
 * constructor. [installIconAwareTooltip]'s [Project] parameter is only dereferenced on the
 * non-suppressed show path, which these tests never exercise, so a bare mock suffices.
 */
class IconAwareTooltipBehaviourTest {
    private class FakeHost : TooltipHost {
        var installed: IdeTooltip? = null
        val moveHideRequests = mutableListOf<MouseEvent>()
        var acceptHideOnMouseMove = true
        var hideNowCalls = 0

        override fun install(owner: JComponent, tooltip: IdeTooltip) {
            installed = tooltip
        }

        override fun hideOnMouseMove(e: MouseEvent): Boolean {
            moveHideRequests += e
            return acceptHideOnMouseMove
        }

        override fun hideNow(owner: JComponent) {
            hideNowCalls++
        }
    }

    private val project = mockk<Project>()

    private fun install(
        owner: JComponent,
        host: FakeHost,
        cellKeyAt: (Point) -> Any? = { p -> p.x / 10 }
    ): IdeTooltip {
        installIconAwareTooltip(owner, project, cellKeyAt, htmlAt = { "<html>tip</html>" }, host = host)
        return host.installed!!
    }

    private fun moveEvent(owner: JComponent, x: Int) =
        MouseEvent(owner, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, 0, 0, false)

    @Test
    fun `tooltip is installed as a hint`() {
        val owner = JPanel()
        val tooltip = install(owner, FakeHost())

        tooltip.isHint shouldBe true
    }

    @Test
    fun `autohide policy spares events inside the balloon`() {
        tooltipShouldAutohide(TooltipEvent(null, true, null, null)) shouldBe false
        tooltipShouldAutohide(TooltipEvent(null, false, null, null)) shouldBe true
    }

    @Test
    fun `moving within the same cell requests no hide`() {
        val owner = JPanel()
        val host = FakeHost()
        install(owner, host)

        val listener = owner.mouseMotionListeners.single()
        listener.mouseMoved(moveEvent(owner, x = 1))
        listener.mouseMoved(moveEvent(owner, x = 2)) // same cellKeyAt (x / 10) bucket

        host.moveHideRequests.size shouldBe 1
    }

    @Test
    fun `moving to a different cell asks the host to hide, never force-hides`() {
        val owner = JPanel()
        val host = FakeHost()
        install(owner, host)

        val listener = owner.mouseMotionListeners.single()
        listener.mouseMoved(moveEvent(owner, x = 1))
        listener.mouseMoved(moveEvent(owner, x = 15)) // different bucket

        host.moveHideRequests.size shouldBe 2
        host.hideNowCalls shouldBe 0
    }

    @Test
    fun `a declined hide is retried on the next move over the same new cell`() {
        val owner = JPanel()
        val host = FakeHost()
        install(owner, host)

        val listener = owner.mouseMotionListeners.single()
        listener.mouseMoved(moveEvent(owner, x = 1))
        host.acceptHideOnMouseMove = false
        listener.mouseMoved(moveEvent(owner, x = 15)) // declined - lastCellKey not advanced
        listener.mouseMoved(moveEvent(owner, x = 16)) // same new bucket, still not advanced - asks again

        host.moveHideRequests.size shouldBe 3
    }

    @Test
    fun `scrolling the enclosing viewport force-hides and suppresses the next show`() {
        val owner = JPanel()
        val host = FakeHost()
        val viewport = JViewport().apply { view = owner }
        val tooltip = install(owner, host) as SuppressibleTooltip

        viewport.viewPosition = Point(0, 10)

        host.hideNowCalls shouldBe 1
        tooltip.isSuppressedUntilMouseMove shouldBe true
    }

    @Test
    fun `suppression clears on the next mouse move`() {
        val owner = JPanel()
        val host = FakeHost()
        val viewport = JViewport().apply { view = owner }
        val tooltip = install(owner, host) as SuppressibleTooltip

        viewport.viewPosition = Point(0, 10)
        owner.mouseMotionListeners.single().mouseMoved(moveEvent(owner, x = 1))

        tooltip.isSuppressedUntilMouseMove shouldBe false
    }

    @Test
    fun `viewport bound after install is still tracked`() {
        val owner = JPanel()
        val host = FakeHost()
        val tooltip = install(owner, host) as SuppressibleTooltip

        val viewport = JViewport()
        viewport.view = owner // parented after installIconAwareTooltip already ran

        viewport.viewPosition = Point(0, 10)

        host.hideNowCalls shouldBe 1
        tooltip.isSuppressedUntilMouseMove shouldBe true
    }
}
