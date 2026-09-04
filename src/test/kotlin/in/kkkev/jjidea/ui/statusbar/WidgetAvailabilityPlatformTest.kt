package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.ui.toolbar.JujutsuBookmarkToolbarWidget
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-0hw4 (GitHub #100): [JujutsuStatusBarWidgetFactory],
 * [JujutsuBookmarkStatusBarWidgetFactory], and [JujutsuBookmarkToolbarWidget] used to gate on
 * [in.kkkev.jjidea.vcs.possibleJujutsuVcs], which only tests whether the Jujutsu VCS *extension*
 * is registered — true in every project once the plugin is installed, regardless of whether the
 * project actually has a `.jj` root. They must instead gate on
 * [in.kkkev.jjidea.vcs.isJujutsu], which reflects the real repository state.
 *
 * `@TestApplication`'s [projectFixture] has no `.jj` directory, so it stands in for "a non-jj
 * project with the plugin installed" — exactly the case this bug showed up in.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class WidgetAvailabilityPlatformTest {
    private val project = projectFixture()

    @Test
    fun `working-copy status-bar widget is unavailable in a non-jj project`() {
        JujutsuStatusBarWidgetFactory().isAvailable(project.get()) shouldBe false
    }

    @Test
    fun `bookmark status-bar widget is unavailable in a non-jj project`() {
        JujutsuBookmarkStatusBarWidgetFactory().isAvailable(project.get()) shouldBe false
    }

    @Test
    fun `bookmark toolbar widget is invisible in a non-jj project`() {
        val widget = JujutsuBookmarkToolbarWidget()
        val context = DataContext { dataId -> if (dataId == CommonDataKeys.PROJECT.name) project.get() else null }
        val event: AnActionEvent = TestActionEvent.createTestEvent(context)

        widget.update(event)

        event.presentation.isEnabledAndVisible shouldBe false
    }
}
