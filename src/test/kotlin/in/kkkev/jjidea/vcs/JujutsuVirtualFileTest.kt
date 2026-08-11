package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.vfs.VcsVirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.history.JujutsuFileRevision
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class JujutsuVirtualFileTest {
    private val contentLocator = ChangeId("abc123", "ab")

    private fun mockFilePath() = mockk<FilePath>().also {
        every { it.path } returns "/repo/file.txt"
        every { it.name } returns "file.txt"
        every { it.parentPath } returns null
    }

    private fun mockRepo(
        logEntry: LogEntry?,
        content1: String = "a",
        content2: String = "b"
    ): Pair<JujutsuRepository, ContentRevision> {
        val contentRevision = mockk<ContentRevision>()
        every { contentRevision.content } returnsMany listOf(content1, content2)

        val registry = mockk<MutableContentRegistry>(relaxed = true)
        val project = mockk<Project>()
        every { project.getService(MutableContentRegistry::class.java) } returns registry

        // Use a delegate so getLogEntry(ContentLocator) is overridden directly in Kotlin, bypassing
        // MockK's overload-resolution and nullable-return-type inference issues.
        val base = mockk<JujutsuRepository>(relaxed = true)
        every { base.project } returns project
        every { base.createContentRevision(any<FileAtVersion>()) } returns contentRevision

        val repo = object : JujutsuRepository by base {
            override fun getLogEntry(contentLocator: ContentLocator): LogEntry? = logEntry
        }
        return Pair(repo, contentRevision)
    }

    /** Convenience: create a [JujutsuVirtualFile] with test-safe defaults (no real Application needed). */
    private fun makeFile(
        repo: JujutsuRepository,
        readAccessAllowed: () -> Boolean = { false },
        backgroundExecutor: (Runnable) -> Unit = { /* no-op: tests control threading explicitly */ }
    ) = JujutsuVirtualFile(
        FileAtVersion(mockFilePath(), contentLocator),
        repo,
        isReadAccessAllowed = readAccessAllowed,
        backgroundExecutor = backgroundExecutor
    )

    /** Background path (no read access held): should fetch and cache synchronously. */
    @Test
    fun `immutable revision caches content and never re-fetches`() {
        val logEntry = mockk<LogEntry>().also { every { it.immutable } returns true }
        val (repo, contentRevision) = mockRepo(logEntry)
        val file = makeFile(repo)

        file.contentsToByteArray() shouldBe "a".toByteArray()
        file.contentsToByteArray() shouldBe "a".toByteArray()

        verify(exactly = 1) { contentRevision.content }
    }

    @Test
    fun `immutable revision is not registered with MutableContentRegistry`() {
        val logEntry = mockk<LogEntry>().also { every { it.immutable } returns true }
        val (repo) = mockRepo(logEntry)
        val project = repo.project
        val registry = project.getService(MutableContentRegistry::class.java)
        val file = makeFile(repo)

        verify(exactly = 0) { registry.register(file) }
    }

    @Test
    fun `mutable revision cache invalidates and re-fetches on next access`() {
        val logEntry = mockk<LogEntry>().also { every { it.immutable } returns false }
        val (repo) = mockRepo(logEntry)
        val file = makeFile(repo)

        file.contentsToByteArray() shouldBe "a".toByteArray()
        file.invalidateContent()
        file.contentsToByteArray() shouldBe "b".toByteArray()
    }

    @Test
    fun `mutable revision is registered with MutableContentRegistry`() {
        val logEntry = mockk<LogEntry>().also { every { it.immutable } returns false }
        val (repo) = mockRepo(logEntry)
        val project = repo.project
        val registry = project.getService(MutableContentRegistry::class.java)
        val file = makeFile(repo)

        verify(exactly = 1) { registry.register(file) }
    }

    @Test
    fun `null log entry treated as mutable and invalidates on request`() {
        val (repo) = mockRepo(logEntry = null)
        val file = makeFile(repo)

        file.contentsToByteArray() shouldBe "a".toByteArray()
        file.invalidateContent()
        file.contentsToByteArray() shouldBe "b".toByteArray()
    }

    @Test
    fun `null log entry is registered with MutableContentRegistry`() {
        val (repo) = mockRepo(logEntry = null)
        val project = repo.project
        val registry = project.getService(MutableContentRegistry::class.java)
        val file = makeFile(repo)

        verify(exactly = 1) { registry.register(file) }
    }

    /** Guard path (read access held, cache cold): must NOT call the CLI; must return empty immediately. */
    @Test
    fun `cold read under read access does not fetch and returns empty`() {
        val logEntry = mockk<LogEntry>().also { every { it.immutable } returns true }
        val (repo, contentRevision) = mockRepo(logEntry)
        val file = makeFile(repo, readAccessAllowed = { true })

        val result = file.contentsToByteArray()

        result shouldBe byteArrayOf()
        verify(exactly = 0) { contentRevision.content }
    }

    /** Guard path (read access held, cache warm): must return cached content without re-fetching. */
    @Test
    fun `warm read under read access returns cached content without fetching`() {
        val logEntry = mockk<LogEntry>().also { every { it.immutable } returns true }
        val (repo, contentRevision) = mockRepo(logEntry)
        var readAccessAllowed = false
        val file = makeFile(repo, readAccessAllowed = { readAccessAllowed })

        // Pre-warm on background thread (read access not held)
        file.contentsToByteArray() shouldBe "a".toByteArray()
        verify(exactly = 1) { contentRevision.content }

        // Simulate read access held — should hit cache, not re-fetch
        readAccessAllowed = true
        file.contentsToByteArray() shouldBe "a".toByteArray()

        verify(exactly = 1) { contentRevision.content } // still just 1 call
    }

    // jj-idea-hq4d: extending VcsVirtualFile (rather than AbstractVcsVirtualFile) is what lets the
    // platform's built-in Annotate action recognize files opened from a historical commit — see
    // AnnotationData.extractFrom, which only matches VcsVirtualFile/ContentRevisionVirtualFile.
    @Test
    fun `is a VcsVirtualFile so the platform's Annotate action recognizes it`() {
        val logEntry = mockk<LogEntry>().also {
            every { it.immutable } returns true
            every { it.id } returns contentLocator
        }
        val (repo) = mockRepo(logEntry)

        makeFile(repo).shouldBeInstanceOf<VcsVirtualFile>()
    }

    @Test
    fun `fileRevision carries the locator's revision number when a log entry is present`() {
        val logEntry = mockk<LogEntry>().also {
            every { it.immutable } returns true
            every { it.id } returns contentLocator
        }
        val (repo) = mockRepo(logEntry)

        val revision = makeFile(repo).fileRevision

        revision.shouldBeInstanceOf<JujutsuFileRevision>()
        revision.revisionNumber shouldBe ChangeIdRevisionNumber(contentLocator)
    }

    @Test
    fun `fileRevision is null when there is no log entry`() {
        val (repo) = mockRepo(logEntry = null)

        makeFile(repo).fileRevision shouldBe null
    }
}
