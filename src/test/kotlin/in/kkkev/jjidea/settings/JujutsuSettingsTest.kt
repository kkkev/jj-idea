package `in`.kkkev.jjidea.settings

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuSettingsTest {
    @Test
    fun `logChangeLimit returns project default when no repo override`() {
        val settings = JujutsuSettings()
        val state = JujutsuSettingsState(logChangeLimit = 300)
        settings.loadState(state)

        val repo = mockRepo("/path/to/repo")
        settings.logChangeLimit(repo) shouldBe 300
    }

    @Test
    fun `logChangeLimit returns repo override when present`() {
        val settings = JujutsuSettings()
        val state = JujutsuSettingsState(
            logChangeLimit = 300,
            repositoryOverrides = mutableMapOf(
                "/path/to/repo" to RepositoryConfig(logChangeLimit = 100)
            )
        )
        settings.loadState(state)

        val repo = mockRepo("/path/to/repo")
        settings.logChangeLimit(repo) shouldBe 100
    }

    @Test
    fun `logChangeLimit falls back to project default when repo override is null`() {
        val settings = JujutsuSettings()
        val state = JujutsuSettingsState(
            logChangeLimit = 500,
            repositoryOverrides = mutableMapOf(
                "/path/to/repo" to RepositoryConfig(logChangeLimit = null)
            )
        )
        settings.loadState(state)

        val repo = mockRepo("/path/to/repo")
        settings.logChangeLimit(repo) shouldBe 500
    }

    @Test
    fun `logChangeLimit returns project default for unrecognized repo path`() {
        val settings = JujutsuSettings()
        val state = JujutsuSettingsState(
            logChangeLimit = 250,
            repositoryOverrides = mutableMapOf(
                "/path/to/other" to RepositoryConfig(logChangeLimit = 100)
            )
        )
        settings.loadState(state)

        val repo = mockRepo("/path/to/repo")
        settings.logChangeLimit(repo) shouldBe 250
    }

    @Test
    fun `migration v1 bumps logChangeLimit from 50 to 500`() {
        val settings = JujutsuSettings()
        val state = JujutsuSettingsState(logChangeLimit = 50, settingsVersion = 0)
        settings.loadState(state)

        settings.state.logChangeLimit shouldBe 500
        settings.state.settingsVersion shouldBe 2
    }

    @Test
    fun `migration v1 preserves custom logChangeLimit`() {
        val settings = JujutsuSettings()
        val state = JujutsuSettingsState(logChangeLimit = 200, settingsVersion = 0)
        settings.loadState(state)

        settings.state.logChangeLimit shouldBe 200
    }

    @Test
    fun `settingsVersion is set to 2 after load`() {
        val settings = JujutsuSettings()
        settings.loadState(JujutsuSettingsState(settingsVersion = 0))
        settings.state.settingsVersion shouldBe 2
    }

    private fun mockRepo(path: String): JujutsuRepository {
        val dir = mockk<VirtualFile>()
        every { dir.path } returns path
        val repo = mockk<JujutsuRepository>()
        every { repo.directory } returns dir
        return repo
    }
}
