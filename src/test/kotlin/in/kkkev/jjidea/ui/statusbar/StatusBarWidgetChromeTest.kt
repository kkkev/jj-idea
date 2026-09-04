package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.JBUI
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Graphics
import javax.swing.JComponent

/**
 * Regression tests for jj-idea-z5uu (GitHub #95): [JujutsuStatusBarWidget] and
 * [JujutsuBookmarkStatusBarWidget] used to hand-roll their hover background
 * (`UIUtil.getPanelBackground().darker()`, wrong-direction in dark themes) and a fixed
 * `empty(0, 4)` border, diverging from every stock status-bar widget. The fix relies on the
 * platform's own hover/pressed painting (`WidgetEffectRenderer`, invoked from
 * `IdeStatusBarImpl.paintChildren` for any direct, non-`JLabel` child of the status bar's right
 * panel) rather than painting anything itself - so this asserts the widget panel no longer
 * overrides `paintComponent`, stays non-opaque (opaque would paint over the platform's fill),
 * and uses [JBUI.CurrentTheme.StatusBar.Widget]'s border/foreground rather than hand-picked
 * values.
 *
 * `@TestApplication` is required: `JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND` and
 * `.border()` resolve to real (`JBColor`/scaled) values only once loaded in a running platform,
 * not from a bare unit test's UIManager, so the equality assertions below would pass for the
 * wrong reason without it.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class StatusBarWidgetChromeTest {
    private val project = projectFixture()

    @Test
    fun `working-copy widget panel matches platform status-bar widget chrome`() {
        val panel = JujutsuStatusBarWidget(project.get()).component
        panel.assertMatchesPlatformChrome()
    }

    @Test
    fun `bookmark fallback widget panel matches platform status-bar widget chrome`() {
        val panel = JujutsuBookmarkStatusBarWidget(project.get()).component
        panel.assertMatchesPlatformChrome()
    }

    @Test
    fun `no descendant of the working-copy widget panel is opaque`() {
        val panel = JujutsuStatusBarWidget(project.get()).component
        panel.assertNoOpaqueDescendant()
    }

    @Test
    fun `no descendant of the bookmark fallback widget panel is opaque`() {
        val panel = JujutsuBookmarkStatusBarWidget(project.get()).component
        panel.assertNoOpaqueDescendant()
    }

    /**
     * Regression guard for jj-idea-z5uu: `WidgetEffectRenderer.applyEffect` sets this outer
     * panel's own `background` field to `Widget.HOVER_BACKGROUND` on hover start but never
     * resets it on hover end. An opaque descendant panel (e.g. a bare `TextCanvasPanel`, which
     * defaults to `isOpaque = true`) inheriting that dangling background dynamically would paint
     * it as a permanent box behind the text after the very first hover, even at rest.
     */
    private fun java.awt.Container.assertNoOpaqueDescendant() {
        for (child in components) {
            if (child is JComponent) child.isOpaque shouldBe false
            if (child is java.awt.Container) child.assertNoOpaqueDescendant()
        }
    }

    private fun JComponent.assertMatchesPlatformChrome() {
        foreground shouldBe JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
        insets shouldBe JBUI.CurrentTheme.StatusBar.Widget.border().getBorderInsets(this)
        isOpaque shouldBe false

        // No self-painted hover/pressed background: overriding paintComponent would repaint
        // over the platform's own fill, which is drawn earlier in IdeStatusBarImpl.paintChildren.
        // Only walk up as far as JComponent itself - it declares paintComponent(Graphics) too,
        // which isn't the override under test.
        var declaringClass: Class<*> = javaClass
        var found = false
        while (declaringClass != JComponent::class.java) {
            if (
                declaringClass.declaredMethods.any {
                    it.name == "paintComponent" && it.parameterTypes.contentEquals(arrayOf(Graphics::class.java))
                }
            ) {
                found = true
                break
            }
            declaringClass = declaringClass.superclass
        }
        found shouldBe false
    }
}
