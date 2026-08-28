package `in`.kkkev.jjidea.settings

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.JBUI
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-bwdk: the settings panel's preferred width exceeded the
 * settings dialog's viewport, forcing a horizontal scrollbar. The platform puts a
 * Configurable's component straight into a `JScrollPane` (`ConfigurableCardPanel` in
 * intellij-community), so the scrollbar tracks the panel's *preferred* width, not just
 * clipping — `align(AlignX.FILL)`/`resizableColumn()` only distribute extra space, they
 * don't shrink it.
 *
 * The fixture project has no jj repositories, so `createPanel()` never builds the
 * per-repo "Repository Settings" group here — that group's width is covered by the
 * manual smoke steps in docs/manual-tests.md (MT-SETTINGS).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuConfigurablePanelTest {
    private val project = projectFixture()

    @Test
    fun `settings panel fits within the settings dialog's available width`() {
        val panel = JujutsuConfigurable(project.get()).createPanel()

        panel.preferredSize.width shouldBeLessThanOrEqual JBUI.scale(WIDTH_BUDGET)
    }

    @Test
    fun `a long validation error does not widen the panel`() {
        val configurable = JujutsuConfigurable(project.get())
        val panel = configurable.createPanel()
        val widthBefore = panel.preferredSize.width

        val longStderr = "Error: " + "a very long jj error message that keeps going ".repeat(8)
        configurable.showValidationResultForTest(longStderr)

        panel.preferredSize.width shouldBeLessThanOrEqual widthBefore
    }

    companion object {
        /**
         * A default-size Settings dialog leaves roughly 700px to the right of the category
         * tree; this leaves headroom for the page's own 16px insets and a vertical scrollbar.
         */
        private const val WIDTH_BUDGET = 640
    }
}
