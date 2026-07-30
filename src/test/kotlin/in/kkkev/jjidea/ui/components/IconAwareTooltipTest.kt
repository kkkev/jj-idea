package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.JPanel

/**
 * Regression tests for jj-idea-fmrj: [iconAwareTooltipComponent] must build its tip component
 * around an [IconAwareHtmlPane] (not a plain HTML pane) so chip `<img src='chip:…'>` elements
 * resolve instead of painting as broken images.
 *
 * Platform-tagged for the same reason as [TooltipComponentTest]: constructing [IconAwareHtmlPane]
 * (a [com.intellij.ui.components.JBHtmlPane]) needs IJPGP's full platform classpath.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class IconAwareTooltipTest {
    private val project = projectFixture()

    @Test
    fun `tip component wraps an IconAwareHtmlPane carrying the supplied html`() {
        val owner = JPanel()
        val result = iconAwareTooltipComponent(project.get(), "<html>hello</html>", owner)

        val pane = findIconAwareHtmlPane(result)
        pane.shouldNotBeNull()
        // Swing's HTMLEditorKit normalizes the document (adds <head>/<body>), so assert on
        // content rather than exact round-trip equality.
        pane.text shouldContain "hello"
    }

    private fun findIconAwareHtmlPane(component: java.awt.Component): IconAwareHtmlPane? = when (component) {
        is IconAwareHtmlPane -> component
        is java.awt.Container -> component.components.firstNotNullOfOrNull { findIconAwareHtmlPane(it) }
        else -> null
    }
}
