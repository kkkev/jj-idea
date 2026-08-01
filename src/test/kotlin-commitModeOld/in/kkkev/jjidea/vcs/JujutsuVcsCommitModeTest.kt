package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.JujutsuSettingsState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Build < 261 (2025.2/2025.3) variant of the `getForcedCommitMode`/`JujutsuHiddenCommitMode`
 * tests - see the `kotlin-commitModeOld`/`kotlin-commitModeNew` source-set switch in
 * build.gradle.kts (jj-idea-r5jf).
 */
class JujutsuVcsCommitModeTest {
    @Test
    fun `JujutsuHiddenCommitMode hides the Commit tool window, Local Changes tab, and default commit action`() {
        // This is the exact contract CommitModeManager / ChangesViewManager key off, per
        // platform/vcs-api's CommitMode and jj-idea-wb5l.
        JujutsuHiddenCommitMode.useCommitToolWindow() shouldBe false
        JujutsuHiddenCommitMode.hideLocalChangesTab() shouldBe true
        JujutsuHiddenCommitMode.disableDefaultCommitAction() shouldBe true
    }

    @Test
    fun `getForcedCommitMode returns JujutsuHiddenCommitMode when the setting is enabled (default)`() {
        val project = mockk<Project>()
        val settings = mockk<JujutsuSettings>()
        every { project.getService(JujutsuSettings::class.java) } returns settings
        every { settings.state } returns JujutsuSettingsState(hideStandardCommitToolWindow = true)

        val vcsWithProject = JujutsuVcs(project)

        vcsWithProject.getForcedCommitMode() shouldBe JujutsuHiddenCommitMode
    }

    @Test
    fun `getForcedCommitMode falls back to the platform default when the setting is disabled`() {
        val project = mockk<Project>()
        val settings = mockk<JujutsuSettings>()
        every { project.getService(JujutsuSettings::class.java) } returns settings
        every { settings.state } returns JujutsuSettingsState(hideStandardCommitToolWindow = false)

        val vcsWithProject = JujutsuVcs(project)

        vcsWithProject.getForcedCommitMode() shouldBe null
    }
}
