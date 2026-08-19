package `in`.kkkev.jjidea.settings

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
class JujutsuSettingsPlatformTest {
    private val project = projectFixture()

    @Test
    fun `settings service is available and has default state`() {
        val settings = JujutsuSettings.getInstance(project.get())
        settings.state.logChangeLimit shouldBe 500
    }

    @Test
    fun `application settings service is available and has default state`() {
        val appSettings = JujutsuApplicationSettings.getInstance()
        appSettings.state.jjExecutablePath shouldBe "jj"
    }

    // ── disableIgnoredFileScanning global-default fallback (jj-idea-ixju) ───────

    @AfterEach
    fun resetAppSettings() {
        JujutsuApplicationSettings.getInstance().state.disableIgnoredFileScanning = false
    }

    @Test
    fun `disableIgnoredFileScanning returns false when no override and no global default`() {
        val settings = JujutsuSettings.getInstance(project.get())
        settings.disableIgnoredFileScanning(mockRepo("/repo")) shouldBe false
    }

    @Test
    fun `disableIgnoredFileScanning falls back to global default when no repo override exists`() {
        JujutsuApplicationSettings.getInstance().state.disableIgnoredFileScanning = true
        val settings = JujutsuSettings.getInstance(project.get())
        settings.disableIgnoredFileScanning(mockRepo("/repo")) shouldBe true
    }

    @Test
    fun `disableIgnoredFileScanning per-repo false override wins over global default true`() {
        JujutsuApplicationSettings.getInstance().state.disableIgnoredFileScanning = true
        val settings = JujutsuSettings.getInstance(project.get())
        val repo = mockRepo("/repo")
        settings.state.repositoryOverrides["/repo"] = RepositoryConfig(disableIgnoredFileScanning = false)
        settings.disableIgnoredFileScanning(repo) shouldBe false
    }

    private fun mockRepo(path: String): JujutsuRepository {
        val dir = mockk<VirtualFile>()
        every { dir.path } returns path
        val repo = mockk<JujutsuRepository>()
        every { repo.directory } returns dir
        return repo
    }
}
