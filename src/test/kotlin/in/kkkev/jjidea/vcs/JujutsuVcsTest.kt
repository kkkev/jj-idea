package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsType
import com.intellij.vcs.commit.CommitMode
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.JujutsuSettingsState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuVcsTest {
    private val vcs = JujutsuVcs(mockk<Project>())

    @Test
    fun `VCS type is distributed so Commit tool window shows in mixed-VCS projects`() {
        vcs.type shouldBe VcsType.distributed
    }

    @Test
    fun `JujutsuHiddenCommitMode hides the Commit tool window, Local Changes tab, and default commit action`() {
        // This is the exact contract CommitModeManager / ChangesViewManager key off, per
        // platform/vcs-api's CommitMode and jj-idea-wb5l.
        JujutsuHiddenCommitMode.isCommitTwEnabled shouldBe false
        JujutsuHiddenCommitMode.isLocalChangesTabHidden shouldBe true
        JujutsuHiddenCommitMode.isDefaultCommitActionDisabled shouldBe true
    }

    @Test
    fun `getForcedCommitMode returns JujutsuHiddenCommitMode when the setting is enabled (default)`() {
        val project = mockk<Project>()
        val settings = mockk<JujutsuSettings>()
        every { project.getService(JujutsuSettings::class.java) } returns settings
        every { settings.state } returns JujutsuSettingsState(hideStandardCommitToolWindow = true)

        val vcsWithProject = JujutsuVcs(project)

        vcsWithProject.getForcedCommitMode(mockk<CommitMode>()) shouldBe JujutsuHiddenCommitMode
    }

    @Test
    fun `getForcedCommitMode falls back to the platform default when the setting is disabled`() {
        val project = mockk<Project>()
        val settings = mockk<JujutsuSettings>()
        every { project.getService(JujutsuSettings::class.java) } returns settings
        every { settings.state } returns JujutsuSettingsState(hideStandardCommitToolWindow = false)

        val vcsWithProject = JujutsuVcs(project)

        vcsWithProject.getForcedCommitMode(mockk<CommitMode>()) shouldBe null
    }
}
