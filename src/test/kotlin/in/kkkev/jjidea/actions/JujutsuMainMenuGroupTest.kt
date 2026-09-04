package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.JujutsuStateModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JujutsuMainMenuGroup] — the "Jujutsu" submenu gathering the plugin's
 * product-specific `VcsGlobalGroup` entries (jj-idea-g1io follow-up), placed the same way
 * `Git.Menu` is. `Jujutsu.Init` lives elsewhere (`Vcs.Import`, consistent with `Git.Init`), so
 * unlike a first cut of this group, nothing here needs to be reachable before a jj repo exists —
 * the guard is simply "the project is already a jj repo". A plain mocked [AnActionEvent] is
 * enough — no platform fixture needed.
 */
class JujutsuMainMenuGroupTest {
    private fun eventFor(project: Project?): AnActionEvent {
        val presentation = Presentation()
        return mockk {
            every { this@mockk.project } returns project
            every { this@mockk.presentation } returns presentation
        }
    }

    private fun jujutsuProject(isJujutsu: Boolean): Project {
        val stateModel = mockk<JujutsuStateModel> { every { this@mockk.isJujutsu } returns isJujutsu }
        return mockk { every { getService(JujutsuStateModel::class.java) } returns stateModel }
    }

    @Test
    fun `visible for a Jujutsu project`() {
        val e = eventFor(jujutsuProject(isJujutsu = true))
        JujutsuMainMenuGroup().update(e)
        e.presentation.isEnabledAndVisible shouldBe true
    }

    @Test
    fun `hidden for a non-Jujutsu project`() {
        val e = eventFor(jujutsuProject(isJujutsu = false))
        JujutsuMainMenuGroup().update(e)
        e.presentation.isEnabledAndVisible shouldBe false
    }

    @Test
    fun `hidden when there is no project`() {
        val e = eventFor(null)
        JujutsuMainMenuGroup().update(e)
        e.presentation.isEnabledAndVisible shouldBe false
    }
}
