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
        settings.state.settingsVersion shouldBe 4
    }

    @Test
    fun `migration v1 preserves custom logChangeLimit`() {
        val settings = JujutsuSettings()
        val state = JujutsuSettingsState(logChangeLimit = 200, settingsVersion = 0)
        settings.loadState(state)

        settings.state.logChangeLimit shouldBe 200
    }

    @Test
    fun `settingsVersion is set to 4 after load`() {
        val settings = JujutsuSettings()
        settings.loadState(JujutsuSettingsState(settingsVersion = 0))
        settings.state.settingsVersion shouldBe 4
    }

    // ── RepositoryConfig.isEmpty ────────────────────────────────────────────────

    @Test
    fun `RepositoryConfig isEmpty returns true when all fields are null`() {
        RepositoryConfig().isEmpty() shouldBe true
    }

    @Test
    fun `RepositoryConfig isEmpty returns false when logChangeLimit is set`() {
        RepositoryConfig(logChangeLimit = 100).isEmpty() shouldBe false
    }

    @Test
    fun `RepositoryConfig isEmpty returns false when logRevset is set`() {
        RepositoryConfig(logRevset = "all()").isEmpty() shouldBe false
    }

    @Test
    fun `RepositoryConfig isEmpty returns false when disableIgnoredFileScanning is set`() {
        RepositoryConfig(disableIgnoredFileScanning = true).isEmpty() shouldBe false
    }

    // ── disableIgnoredFileScanning resolver ────────────────────────────────────

    @Test
    fun `disableIgnoredFileScanning returns false when no override`() {
        val settings = JujutsuSettings()
        settings.loadState(JujutsuSettingsState())
        settings.disableIgnoredFileScanning(mockRepo("/repo")) shouldBe false
    }

    @Test
    fun `disableIgnoredFileScanning returns true when override is true`() {
        val settings = JujutsuSettings()
        settings.loadState(
            JujutsuSettingsState(
                repositoryOverrides = mutableMapOf("/repo" to RepositoryConfig(disableIgnoredFileScanning = true))
            )
        )
        settings.disableIgnoredFileScanning(mockRepo("/repo")) shouldBe true
    }

    @Test
    fun `disableIgnoredFileScanning returns false when override is null`() {
        val settings = JujutsuSettings()
        settings.loadState(
            JujutsuSettingsState(
                repositoryOverrides = mutableMapOf("/repo" to RepositoryConfig(disableIgnoredFileScanning = null))
            )
        )
        settings.disableIgnoredFileScanning(mockRepo("/repo")) shouldBe false
    }

    // ── setDisableIgnoredFileScanning ─────────────────────────────────────────

    @Test
    fun `setDisableIgnoredFileScanning creates override when set to true`() {
        val settings = JujutsuSettings()
        settings.loadState(JujutsuSettingsState())
        val repo = mockRepo("/repo")
        settings.setDisableIgnoredFileScanning(repo, true)
        settings.state.repositoryOverrides["/repo"]?.disableIgnoredFileScanning shouldBe true
    }

    @Test
    fun `setDisableIgnoredFileScanning removes override when set to false and no other fields`() {
        val settings = JujutsuSettings()
        settings.loadState(
            JujutsuSettingsState(
                repositoryOverrides = mutableMapOf("/repo" to RepositoryConfig(disableIgnoredFileScanning = true))
            )
        )
        val repo = mockRepo("/repo")
        settings.setDisableIgnoredFileScanning(repo, false)
        settings.state.repositoryOverrides["/repo"] shouldBe null
    }

    @Test
    fun `setDisableIgnoredFileScanning preserves other override fields when clearing`() {
        val settings = JujutsuSettings()
        settings.loadState(
            JujutsuSettingsState(
                repositoryOverrides = mutableMapOf(
                    "/repo" to RepositoryConfig(logChangeLimit = 200, disableIgnoredFileScanning = true)
                )
            )
        )
        val repo = mockRepo("/repo")
        settings.setDisableIgnoredFileScanning(repo, false)
        settings.state.repositoryOverrides["/repo"]?.logChangeLimit shouldBe 200
        settings.state.repositoryOverrides["/repo"]?.disableIgnoredFileScanning shouldBe null
    }

    private fun mockRepo(path: String): JujutsuRepository {
        val dir = mockk<VirtualFile>()
        every { dir.path } returns path
        val repo = mockk<JujutsuRepository>()
        every { repo.directory } returns dir
        return repo
    }
}
