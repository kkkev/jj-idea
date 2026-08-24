package `in`.kkkev.jjidea.jj

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * jj-idea-9ife: a `.jj` directory jj itself can't read (broken/stale store, moved repo, an
 * incompatible jj version) must not crash [JujutsuStateModel.workingCopies] — the repo should be
 * skipped, leaving the rest of the working copies loaded, and [JujutsuRepositoryHealth] must
 * reflect it so [in.kkkev.jjidea.vcs.JujutsuRootChecker] and the Working Copy tool window can too.
 */
class LoadWorkingCopiesTest {
    private val project = mockk<Project>()
    private val log = mockk<Logger>(relaxed = true)

    // JujutsuRepositoryHealth is a process-global cache; clear the paths this file writes so runs
    // don't leak state into each other.
    @AfterEach
    fun clearHealthCache() {
        listOf("/healthy", "/broken", "/a", "/b").forEach(JujutsuRepositoryHealth::markReadable)
    }

    /** A readable repo at [path]: `logCache[WorkingCopy]` returns a [LogEntry] pointing back at this same repo. */
    private fun readableRepoAt(path: String): JujutsuRepository {
        val virtualFile = mockk<VirtualFile> { every { this@mockk.path } returns path }
        lateinit var repo: JujutsuRepository
        repo = mockk {
            every { directory } returns virtualFile
            every { logCache } returns mockk {
                every { get(WorkingCopy) } answers {
                    LogEntry(
                        repo = repo,
                        id = ChangeId(path, path, null),
                        commitId = CommitId("commit-$path"),
                        underlyingDescription = ""
                    )
                }
            }
        }
        return repo
    }

    /** A repo at [path] whose `logCache[WorkingCopy]` throws [exception]. */
    private fun unreadableRepoAt(path: String, exception: Throwable): JujutsuRepository {
        val virtualFile = mockk<VirtualFile> { every { this@mockk.path } returns path }
        return mockk {
            every { directory } returns virtualFile
            every { logCache } returns mockk { every { get(WorkingCopy) } throws exception }
        }
    }

    @Test
    fun `a repo whose working copy can't be read is skipped, not thrown, notified once, and marked unreadable`() {
        val healthy = readableRepoAt("/healthy")
        val broken = unreadableRepoAt("/broken", VcsException("Error from jj log: Internal error: broken repo"))
        val notified = mutableListOf<Triple<Project, JujutsuRepository, String>>()

        val result = loadWorkingCopies(project, listOf(healthy, broken), log) { p, r, detail ->
            notified.add(Triple(p, r, detail))
        }

        result shouldContainKey "/healthy"
        result shouldNotContainKey "/broken"
        result.size shouldBe 1
        notified.size shouldBe 1
        notified.single().second shouldBe broken
        JujutsuRepositoryHealth.isUnreadable("/broken").shouldBeTrue()
        JujutsuRepositoryHealth.isUnreadable("/healthy").shouldBeFalse()
    }

    @Test
    fun `all repos readable returns an entry for each, and no notification`() {
        val a = readableRepoAt("/a")
        val b = readableRepoAt("/b")

        val result = loadWorkingCopies(project, listOf(a, b), log) { _, _, _ -> error("should not notify") }

        result.keys shouldBe setOf("/a", "/b")
    }

    @Test
    fun `a repaired repo is cleared from JujutsuRepositoryHealth on its next successful load`() {
        JujutsuRepositoryHealth.markUnreadable("/healthy", "previously broken")

        loadWorkingCopies(project, listOf(readableRepoAt("/healthy")), log) { _, _, _ -> error("should not notify") }

        JujutsuRepositoryHealth.isUnreadable("/healthy").shouldBeFalse()
    }

    @Test
    fun `a non-VcsException is not caught`() {
        val broken = unreadableRepoAt("/broken", IllegalStateException("plugin bug"))

        val thrown = runCatching {
            loadWorkingCopies(project, listOf(broken), log) { _, _, _ -> error("should not notify") }
        }.exceptionOrNull()

        thrown.shouldBe(thrown as? IllegalStateException)
        thrown?.message shouldBe "plugin bug"
    }
}
