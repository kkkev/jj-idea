package `in`.kkkev.jjidea.integration

import `in`.kkkev.jjidea.contract.JjStub
import `in`.kkkev.jjidea.contract.StubCommandExecutor
import `in`.kkkev.jjidea.jj.FileChangeStatus
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.CliLogService
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Integration tests exercising the full parsing pipeline:
 * JjStub → StubCommandExecutor → CliLogService → parsed domain objects.
 *
 * These catch mismatches between template generation and output parsing
 * without needing a real jj installation or IntelliJ platform.
 */
@Tag("stub")
class LogServiceIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var stub: JjStub
    private lateinit var logService: CliLogService

    @BeforeEach
    fun setUp() {
        stub = JjStub(tempDir)
        stub.init()
        val executor = StubCommandExecutor(stub)
        val repo = mockk<JujutsuRepository> { every { commandExecutor } returns executor }
        logService = CliLogService(repo)
    }

    @Nested
    inner class GetLogBasic {
        @Test
        fun `single entry with description`() {
            stub.describe("Hello world")

            val entries = logService.getLogBasic().getOrThrow()
            val wc = entries.first { it.isWorkingCopy }

            wc.description.actual shouldBe "Hello world"
            wc.id.full.shouldNotBeBlank()
            wc.commitId.full.shouldNotBeBlank()
        }

        @Test
        fun `multiple entries returns correct count`() {
            stub.describe("First")
            stub.newChange("Second")

            val entries = logService.getLogBasic().getOrThrow()
            // root + first + second (working copy)
            entries.size shouldBe 3
        }

        @Test
        fun `entry with bookmarks`() {
            stub.describe("Bookmarked")
            stub.bookmarkCreate("my-bookmark")

            val entries = logService.getLogBasic().getOrThrow()
            val wc = entries.first { it.isWorkingCopy }

            wc.bookmarks.map { it.name } shouldBe listOf("my-bookmark")
        }

        @Test
        fun `multiline description preserved`() {
            stub.describe("Line one\nLine two\nLine three")

            val entries = logService.getLogBasic().getOrThrow()
            val wc = entries.first { it.isWorkingCopy }

            wc.description.actual shouldBe "Line one\nLine two\nLine three"
        }

        @Test
        fun `empty working copy has isEmpty true`() {
            // Fresh working copy with no file changes
            val entries = logService.getLogBasic().getOrThrow()
            val wc = entries.first { it.isWorkingCopy }

            wc.isEmpty shouldBe true
        }

        @Test
        fun `non-empty working copy has isEmpty false`() {
            stub.createFile("test.txt", "content")

            val entries = logService.getLogBasic().getOrThrow()
            val wc = entries.first { it.isWorkingCopy }

            wc.isEmpty shouldBe false
        }

        @Test
        fun `working copy is identified`() {
            val entries = logService.getLogBasic().getOrThrow()

            entries.count { it.isWorkingCopy } shouldBe 1
        }

        @Test
        fun `parent identifiers populated`() {
            stub.describe("Parent")
            stub.newChange("Child")

            val entries = logService.getLogBasic().getOrThrow()
            val child = entries.first { it.isWorkingCopy }

            child.parentIdentifiers shouldHaveSize 1
            child.parentIdentifiers[0].changeId.full.shouldNotBeBlank()
            child.parentIdentifiers[0].commitId.full.shouldNotBeBlank()
        }

        @Test
        fun `root commit is immutable`() {
            val entries = logService.getLogBasic().getOrThrow()
            val root = entries.last()

            root.immutable shouldBe true
        }
    }

    @Nested
    inner class GetLogFull {
        @Test
        fun `author fields populated`() {
            stub.describe("Test commit")

            val entries = logService.getLog().getOrThrow()
            val wc = entries.first { it.isWorkingCopy }

            wc.author.shouldNotBeNull()
            wc.author!!.name shouldBe "Test User"
            wc.author!!.email shouldBe "test@example.com"
            wc.authorTimestamp.shouldNotBeNull()
        }

        @Test
        fun `committer fields populated`() {
            stub.describe("Test commit")

            val entries = logService.getLog().getOrThrow()
            val wc = entries.first { it.isWorkingCopy }

            wc.committer.shouldNotBeNull()
            wc.committer!!.name shouldBe "Test User"
            wc.committer!!.email shouldBe "test@example.com"
            wc.committerTimestamp.shouldNotBeNull()
        }
    }

    @Nested
    inner class GetFileChanges {
        @Test
        fun `added file`() {
            stub.createFile("new.txt", "hello")

            val changes = logService.getFileChanges(WorkingCopy).getOrThrow()

            changes shouldHaveSize 1
            changes[0].filePath shouldBe "new.txt"
            changes[0].status shouldBe FileChangeStatus.ADDED
        }

        @Test
        fun `modified file`() {
            stub.createFile("file.txt", "original")
            stub.newChange()
            stub.createFile("file.txt", "modified")

            val changes = logService.getFileChanges(WorkingCopy).getOrThrow()

            changes shouldHaveSize 1
            changes[0].filePath shouldBe "file.txt"
            changes[0].status shouldBe FileChangeStatus.MODIFIED
        }

        @Test
        fun `deleted file`() {
            stub.createFile("file.txt", "content")
            stub.newChange()
            // Delete by writing to the working copy and removing from filesystem
            tempDir.resolve("file.txt").toFile().delete()

            val changes = logService.getFileChanges(WorkingCopy).getOrThrow()

            changes shouldHaveSize 1
            changes[0].filePath shouldBe "file.txt"
            changes[0].status shouldBe FileChangeStatus.DELETED
        }

        @Test
        fun `mixed changes`() {
            stub.createFile("existing.txt", "original")
            stub.createFile("to-delete.txt", "will be removed")
            stub.newChange()
            stub.createFile("existing.txt", "modified")
            stub.createFile("brand-new.txt", "new file")
            tempDir.resolve("to-delete.txt").toFile().delete()

            val changes = logService.getFileChanges(WorkingCopy).getOrThrow()
                .sortedBy { it.filePath }

            changes shouldHaveSize 3
            changes[0].filePath shouldBe "brand-new.txt"
            changes[0].status shouldBe FileChangeStatus.ADDED
            changes[1].filePath shouldBe "existing.txt"
            changes[1].status shouldBe FileChangeStatus.MODIFIED
            changes[2].filePath shouldBe "to-delete.txt"
            changes[2].status shouldBe FileChangeStatus.DELETED
        }

        @Test
        fun `no changes returns empty list`() {
            val changes = logService.getFileChanges(WorkingCopy).getOrThrow()

            changes shouldHaveSize 0
        }
    }

    @Nested
    inner class GetBookmarks {
        @Test
        fun `single bookmark`() {
            stub.describe("Bookmarked")
            stub.bookmarkCreate("main")

            val bookmarks = logService.getBookmarks().getOrThrow()

            bookmarks shouldHaveSize 1
            bookmarks[0].bookmark.name shouldBe "main"
            bookmarks[0].id.full.shouldNotBeBlank()
        }

        @Test
        fun `multiple bookmarks`() {
            stub.describe("First")
            stub.bookmarkCreate("alpha")
            stub.newChange("Second")
            stub.bookmarkCreate("beta")

            val bookmarks = logService.getBookmarks().getOrThrow()

            bookmarks shouldHaveSize 2
            bookmarks.map { it.bookmark.name }.toSet() shouldBe setOf("alpha", "beta")
        }
    }
}
