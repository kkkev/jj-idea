package `in`.kkkev.jjidea.vcs.ignore

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [JujutsuTrackedFilesService]'s cache read/write and dedup logic (jj-idea-i9ol rounds
 * 4-5, the latter adding [JujutsuTrackedFilesService.setKnown] for optimistic UI updates).
 *
 * Needs the full platform test environment (`@TestApplication`), not the lightweight unit-test
 * tier - the service's constructor builds a real `MergingUpdateQueue`, which requires more
 * platform machinery than a plain unit test provides (confirmed: constructing it there throws
 * "Do not use an alarm in an early executing code"). This is exactly why
 * [JujutsuIgnoredFilesService] (the sibling this mirrors) has no unit test of its own either - its
 * async timing is a manual-test surface, not a unit-test target. These tests drive [refresh]
 * directly (bypassing the real queue's 300ms debounce) rather than asserting on real queue timing.
 */
@Tag("platform")
@TestApplication
class JujutsuTrackedFilesServicePlatformTest {
    private val project = mockk<Project>()
    private val commandExecutor = mockk<CommandExecutor>()
    private val directory = MockVirtualFile("repo")
    private val repo = mockk<JujutsuRepository>()
    private val service = JujutsuTrackedFilesService(project)

    init {
        every { repo.commandExecutor } returns commandExecutor
        every { repo.directory } returns directory
    }

    // The service registers its MergingUpdateQueue's Disposer parent as itself when constructed
    // directly (bypassing the project-service container, which normally handles disposal
    // automatically) - dispose it manually or the platform's LeakHunter flags a retained instance.
    @AfterEach
    fun disposeService() {
        Disposer.dispose(service)
    }

    private fun path(name: String) = LocalFilePath("${directory.path}/$name", false)

    private fun stubFileList(stdout: String) {
        every { commandExecutor.fileList(any()) } returns CommandExecutor.CommandResult(0, stdout, "")
    }

    @Test
    fun `trackedStateOrNull is null before any refresh`() {
        service.trackedStateOrNull(repo, path("foo.txt")) shouldBe null
    }

    @Test
    fun `refresh populates the cache from fileList stdout`() {
        stubFileList("tracked.txt\n")
        val state = service.stateFor(repo)

        service.refresh(repo, state, listOf("tracked.txt", "untracked.txt"))

        service.trackedStateOrNull(repo, path("tracked.txt")) shouldBe true
        service.trackedStateOrNull(repo, path("untracked.txt")) shouldBe false
    }

    @Test
    fun `refresh clears the pending marker for the paths it processed`() {
        stubFileList("")
        val state = service.stateFor(repo)
        state.pending.add("a.txt")

        service.refresh(repo, state, listOf("a.txt"))

        state.pending shouldBe emptySet()
    }

    @Test
    fun `requestRefresh does not re-request an already-cached path`() {
        stubFileList("cached.txt\n")
        val state = service.stateFor(repo)
        service.refresh(repo, state, listOf("cached.txt"))

        // A second requestRefresh for the same, now-cached path must not mark it pending again -
        // it should already have a cached answer and skip re-querying.
        service.requestRefresh(repo, listOf(path("cached.txt")))

        state.pending shouldBe emptySet()
    }

    @Test
    fun `invalidatePaths removes only the specified entries`() {
        stubFileList("a.txt\nb.txt\n")
        val state = service.stateFor(repo)
        service.refresh(repo, state, listOf("a.txt", "b.txt"))

        service.invalidatePaths(repo, listOf(path("a.txt")))

        service.trackedStateOrNull(repo, path("a.txt")) shouldBe null
        service.trackedStateOrNull(repo, path("b.txt")) shouldBe true
    }

    @Test
    fun `invalidate clears the whole repo cache`() {
        stubFileList("a.txt\n")
        val state = service.stateFor(repo)
        service.refresh(repo, state, listOf("a.txt"))

        service.invalidate(repo)

        service.trackedStateOrNull(repo, path("a.txt")) shouldBe null
    }

    @Test
    fun `setKnown writes a value immediately without any jj query`() {
        service.setKnown(repo, listOf(path("optimistic.txt")), tracked = true)

        service.trackedStateOrNull(repo, path("optimistic.txt")) shouldBe true
    }

    @Test
    fun `setKnown can overwrite a previously cached value (e_g_ revert on failure)`() {
        stubFileList("a.txt\n")
        val state = service.stateFor(repo)
        service.refresh(repo, state, listOf("a.txt"))
        service.trackedStateOrNull(repo, path("a.txt")) shouldBe true

        service.setKnown(repo, listOf(path("a.txt")), tracked = false)

        service.trackedStateOrNull(repo, path("a.txt")) shouldBe false
    }
}
