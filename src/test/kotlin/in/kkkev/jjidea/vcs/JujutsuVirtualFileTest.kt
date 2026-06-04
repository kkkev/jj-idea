package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
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

    @Test
    fun `immutable revision caches content and never re-fetches`() {
        val logEntry = mockk<LogEntry>().also { every { it.immutable } returns true }
        val (repo, contentRevision) = mockRepo(logEntry)
        val file = JujutsuVirtualFile(FileAtVersion(mockFilePath(), contentLocator), repo)

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
        val file = JujutsuVirtualFile(FileAtVersion(mockFilePath(), contentLocator), repo)

        verify(exactly = 0) { registry.register(file) }
    }

    @Test
    fun `mutable revision cache invalidates and re-fetches on next access`() {
        val logEntry = mockk<LogEntry>().also { every { it.immutable } returns false }
        val (repo) = mockRepo(logEntry)
        val file = JujutsuVirtualFile(FileAtVersion(mockFilePath(), contentLocator), repo)

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
        val file = JujutsuVirtualFile(FileAtVersion(mockFilePath(), contentLocator), repo)

        verify(exactly = 1) { registry.register(file) }
    }

    @Test
    fun `null log entry treated as mutable and invalidates on request`() {
        val (repo) = mockRepo(logEntry = null)
        val file = JujutsuVirtualFile(FileAtVersion(mockFilePath(), contentLocator), repo)

        file.contentsToByteArray() shouldBe "a".toByteArray()
        file.invalidateContent()
        file.contentsToByteArray() shouldBe "b".toByteArray()
    }

    @Test
    fun `null log entry is registered with MutableContentRegistry`() {
        val (repo) = mockRepo(logEntry = null)
        val project = repo.project
        val registry = project.getService(MutableContentRegistry::class.java)
        val file = JujutsuVirtualFile(FileAtVersion(mockFilePath(), contentLocator), repo)

        verify(exactly = 1) { registry.register(file) }
    }
}
