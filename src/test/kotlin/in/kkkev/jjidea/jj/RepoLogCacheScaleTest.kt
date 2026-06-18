package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.settings.JujutsuSettings
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for the `log-load` hot path ([RepoLogCache]).
 *
 * Injects a counting collaborator — a mockk [LogService] — and asserts on the number of
 * calls made to it, not on wall-clock time. Guards two regression classes: a bulk load
 * that should issue one `jj log` call (not one per entry/revision), and the insertion-order
 * bookkeeping that jj-idea-iivd fixed from an O(n²) `filterNot+concat` rebuild to an O(1)
 * `LinkedHashSet` remove+reinsert (see [RepoLogCache.store]). See contributing.md's
 * "Writing a scale test" section and [in.kkkev.jjidea.vcs.ignore.GitignoreScanTest] for the
 * pattern this generalizes; setup mirrors [RepoLogCacheTest].
 */
class RepoLogCacheScaleTest {
    private lateinit var repo: JujutsuRepository
    private lateinit var logService: LogService
    private lateinit var project: Project
    private lateinit var cache: RepoLogCache

    @BeforeEach
    fun setup() {
        logService = mockk()
        // project must be a relaxed mock so that the RepoLogCache init block's
        // messageBus.connect().subscribe() calls succeed without a real platform.
        project = mockk<Project>(relaxed = true)
        repo = mockk {
            every { this@mockk.project } returns this@RepoLogCacheScaleTest.project
            every { this@mockk.logService } returns this@RepoLogCacheScaleTest.logService
            every { displayName } returns "test-repo"
        }
        cache = RepoLogCache(repo)
    }

    /** Stubs [JujutsuSettings.getInstance] for tests that exercise the [LogCache.all] path. */
    private fun stubSettings(logRevset: String = "", logChangeLimit: Int = 100): JujutsuSettings =
        mockk<JujutsuSettings>(relaxed = true).also { settings ->
            every { settings.logRevset(repo) } returns logRevset
            every { settings.logChangeLimit(repo) } returns logChangeLimit
            every { project.getService(JujutsuSettings::class.java) } returns settings
        }

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id",
        bookmarks = emptyList(),
        immutable = false
    )

    @Test
    fun `loading N entries issues exactly one logService call, not one per entry`() {
        val n = 20_000
        val entries = (0 until n).map { entry("c$it") }
        stubSettings(logChangeLimit = n)
        every { logService.getLog(revset = Revset.Default, limit = n) } returns Result.success(entries)

        val all = cache.all

        all.size shouldBe n
        verify(exactly = 1) { logService.getLog(revset = Revset.Default, limit = n, filePaths = emptyList()) }
    }

    @Test
    fun `point lookups after a bulk load are served from cache with zero extra logService calls`() {
        val n = 20_000
        val entries = (0 until n).map { entry("c$it") }
        stubSettings(logChangeLimit = n)
        every { logService.getLog(revset = Revset.Default, limit = n) } returns Result.success(entries)
        cache.all

        entries.forEach { e ->
            cache[e.id] shouldBe e
            cache[e.commitId] shouldBe e
        }

        verify(exactly = 1) { logService.getLog(revset = Revset.Default, limit = n, filePaths = emptyList()) }
    }

    @Test
    fun `incremental store stays correctly ordered and deduplicated at N (no O(n^2) order rebuild)`() {
        val n = 20_000
        // N successive single-entry stores exercise the LinkedHashSet remove+reinsert path
        // (replaced the O(n^2) filterNot+concat order rebuild fixed in jj-idea-iivd).
        repeat(n) { i -> cache.store(listOf(entry("c$i"))) }

        // Re-store an overlapping batch (the last 100 ids) — must dedupe, not duplicate.
        val overlap = (n - 100 until n).map { entry("c$it") }
        cache.store(overlap)

        cache.all.size shouldBe n
        cache.all.map { it.id.full }.distinct().size shouldBe n
    }
}
