package `in`.kkkev.jjidea.settings

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class LogWindowConfigTest {
    // ── selectedRepos ──────────────────────────────────────────────────────────

    @Test
    fun `selectedRepos returns all repos when selectedRepoPaths is empty`() {
        val config = LogWindowConfig(selectedRepoPaths = mutableListOf())
        val repos = listOf(mockRepo("/a"), mockRepo("/b"), mockRepo("/c"))
        config.selectedRepos(repos) shouldContainExactly repos
    }

    @Test
    fun `selectedRepos filters to specified subset`() {
        val repoA = mockRepo("/a")
        val repoB = mockRepo("/b")
        val repoC = mockRepo("/c")
        val config = LogWindowConfig(selectedRepoPaths = mutableListOf("/a", "/c"))
        config.selectedRepos(listOf(repoA, repoB, repoC)) shouldContainExactlyInAnyOrder listOf(repoA, repoC)
    }

    @Test
    fun `selectedRepos ignores stale paths not in allRepos`() {
        val repoA = mockRepo("/a")
        val config = LogWindowConfig(selectedRepoPaths = mutableListOf("/a", "/stale"))
        config.selectedRepos(listOf(repoA)) shouldContainExactly listOf(repoA)
    }

    @Test
    fun `selectedRepos returns empty list when all selected paths are stale`() {
        val config = LogWindowConfig(selectedRepoPaths = mutableListOf("/gone"))
        config.selectedRepos(listOf(mockRepo("/a"))) shouldBe emptyList()
    }

    // ── v4 migration ───────────────────────────────────────────────────────────

    @Test
    fun `v4 migration creates default window when logWindows is empty`() {
        val settings = JujutsuSettings()
        settings.loadState(JujutsuSettingsState(settingsVersion = 3, logWindows = mutableListOf()))

        settings.logWindows().size shouldBe 1
        settings.logWindows().first().id shouldBe JujutsuSettings.DEFAULT_LOG_WINDOW_ID
    }

    @Test
    fun `v4 migration folds global columnWidths into default window`() {
        val globalWidths = mutableMapOf("author" to 120, "date" to 90)
        val settings = JujutsuSettings()
        settings.loadState(
            JujutsuSettingsState(
                settingsVersion = 3,
                columnWidths = globalWidths,
                logWindows = mutableListOf()
            )
        )

        settings.logWindows().first().columnWidths shouldBe mapOf("author" to 120, "date" to 90)
    }

    @Test
    fun `v4 migration is idempotent when logWindows already has entries`() {
        val existing = LogWindowConfig(id = "custom", name = "My Tab")
        val settings = JujutsuSettings()
        settings.loadState(
            JujutsuSettingsState(settingsVersion = 3, logWindows = mutableListOf(existing))
        )

        settings.logWindows().size shouldBe 1
        settings.logWindows().first().id shouldBe "custom"
    }

    // ── settings helpers ───────────────────────────────────────────────────────

    @Test
    fun `upsertLogWindow updates existing window by id`() {
        val settings = JujutsuSettings()
        settings.loadState(JujutsuSettingsState())

        val config1 = LogWindowConfig(id = "abc", name = "First")
        settings.upsertLogWindow(config1)
        settings.logWindows().size shouldBe 2 // default + abc

        val config2 = LogWindowConfig(id = "abc", name = "Updated")
        settings.upsertLogWindow(config2)
        settings.logWindows().size shouldBe 2
        settings.logWindows().first { it.id == "abc" }.name shouldBe "Updated"
    }

    @Test
    fun `removeLogWindow removes window by id`() {
        val settings = JujutsuSettings()
        settings.loadState(JujutsuSettingsState())
        settings.upsertLogWindow(LogWindowConfig(id = "abc", name = "Tab"))

        settings.removeLogWindow("abc")
        settings.logWindows().none { it.id == "abc" } shouldBe true
    }

    @Test
    fun `ensureDefaultWindow returns existing default when present`() {
        val settings = JujutsuSettings()
        settings.loadState(JujutsuSettingsState())
        val first = settings.ensureDefaultWindow()
        val second = settings.ensureDefaultWindow()
        first.id shouldBe second.id
        settings.logWindows().count { it.id == JujutsuSettings.DEFAULT_LOG_WINDOW_ID } shouldBe 1
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun mockRepo(path: String): JujutsuRepository {
        val dir = mockk<VirtualFile>()
        every { dir.path } returns path
        every { dir.name } returns path.substringAfterLast("/")
        val repo = mockk<JujutsuRepository>()
        every { repo.directory } returns dir
        return repo
    }
}
