package `in`.kkkev.jjidea.ui.dnd

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JRootPane
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * Regression cover for [RejectOverlay]'s layering (jj-idea-ymuu). In Kotlin, `layeredPane.add(c,
 * JLayeredPane.DRAG_LAYER)` binds to `Container.add(Component, int index)` rather than Java's
 * layer-constraint overload, which silently left the overlay in the default layer.
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class RejectOverlayTest {
    @Test
    fun `the overlay lives in the DRAG_LAYER and is reused across shows`() {
        val rootPane = JRootPane()
        val surface = JPanel()
        rootPane.contentPane.add(surface)
        rootPane.setSize(400, 300)
        rootPane.doLayout()
        rootPane.layeredPane.setSize(400, 300)
        val overlay = RejectOverlay()

        overlay.show(surface, Rectangle(0, 0, 100, 20))
        overlay.show(surface, Rectangle(0, 20, 100, 20))

        rootPane.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER) shouldHaveSize 1
        rootPane.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER).single().bounds shouldBe
            Rectangle(0, 20, 100, 20)
    }

    @Test
    fun `dispose removes the overlay`() {
        val rootPane = JRootPane()
        val surface = JPanel()
        rootPane.contentPane.add(surface)
        rootPane.setSize(400, 300)
        rootPane.doLayout()
        rootPane.layeredPane.setSize(400, 300)
        val overlay = RejectOverlay()
        overlay.show(surface, Rectangle(0, 0, 100, 20))

        overlay.dispose()

        rootPane.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER) shouldHaveSize 0
    }
}
