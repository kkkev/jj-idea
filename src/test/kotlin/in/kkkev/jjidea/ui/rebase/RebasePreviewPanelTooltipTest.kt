package `in`.kkkev.jjidea.ui.rebase

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.ui.components.iconAwareTooltip
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Container

/**
 * Regression test for jj-idea-2md7: [RebasePreviewPanel]'s log-style preview table must render
 * row tooltips through [in.kkkev.jjidea.ui.components.IconAwareHtmlPane], not a plain Swing
 * tooltip which paints bookmark/tag chip `<img>` markup as a broken image.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class RebasePreviewPanelTooltipTest {
    private val project = projectFixture()

    @Test
    fun `preview table has the icon-aware tooltip installed`() {
        val panel = RebasePreviewPanel(project.get())

        findTable(panel)!!.iconAwareTooltip().shouldNotBeNull()
    }

    private fun findTable(container: Container): javax.swing.JTable? = container.components.firstNotNullOfOrNull {
        when (it) {
            is javax.swing.JTable -> it
            is Container -> findTable(it)
            else -> null
        }
    }
}
