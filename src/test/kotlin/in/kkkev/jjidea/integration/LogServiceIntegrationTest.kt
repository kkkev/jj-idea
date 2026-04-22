package `in`.kkkev.jjidea.integration

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.contract.JjStub
import `in`.kkkev.jjidea.contract.StubCommandExecutor
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.vcs.getChildPath
import io.kotest.inspectors.forAtLeastOne
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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
    private val workingCopy get() = logService.getLog(WorkingCopy).getOrThrow().single()

    @BeforeEach
    fun setUp() {
        stub = JjStub(tempDir)
        stub.init()
        val executor = StubCommandExecutor(stub)
        val repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns executor
            every { directory } returns virtualFile("/foo")
        }
        logService = CliLogService(repo)

        mockkStatic(VirtualFile::getChildPath)
        every { any<VirtualFile>().getChildPath(any(), any()) } answers {
            mockk<FilePath> {
                every { path } returns secondArg<String>()
            }
        }
    }

    private fun virtualFile(relativePath: String): VirtualFile = mockk {
        every { path } returns relativePath
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
            wc.author.name shouldBe "Test User"
            wc.author.email shouldBe "test@example.com"
            wc.authorTimestamp.shouldNotBeNull()
        }

        @Test
        fun `committer fields populated`() {
            stub.describe("Test commit")

            val entries = logService.getLog().getOrThrow()
            val wc = entries.first { it.isWorkingCopy }

            wc.committer.shouldNotBeNull()
            wc.committer.name shouldBe "Test User"
            wc.committer.email shouldBe "test@example.com"
            wc.committerTimestamp.shouldNotBeNull()
        }
    }

    @Nested
    inner class GetFileChanges {
        @Test
        fun `added file`() {
            stub.createFile("new.txt", "hello")

            val changes = logService.getFileChanges(workingCopy).getOrThrow()

            changes shouldHaveSize 1
            changes[0].after?.filePath?.path shouldBe "new.txt"
            changes[0].status shouldBe FileChange.Status.ADDED
        }

        @Test
        fun `modified file`() {
            stub.createFile("file.txt", "original")
            stub.newChange()
            stub.createFile("file.txt", "modified")

            val changes = logService.getFileChanges(workingCopy).getOrThrow()

            changes shouldHaveSize 1
            changes[0].after?.filePath?.path shouldBe "file.txt"
            changes[0].status shouldBe FileChange.Status.MODIFIED
        }

        @Test
        fun `deleted file`() {
            stub.createFile("file.txt", "content")
            stub.newChange()
            // Delete by writing to the working copy and removing from filesystem
            tempDir.resolve("file.txt").toFile().delete()

            val changes = logService.getFileChanges(workingCopy).getOrThrow()

            changes shouldHaveSize 1
            changes[0].before?.filePath?.path shouldBe "file.txt"
            changes[0].status shouldBe FileChange.Status.DELETED
        }

        @Test
        fun `mixed changes`() {
            stub.createFile("existing.txt", "original")
            stub.createFile("to-delete.txt", "will be removed")
            stub.createFile("to-rename.txt", "original")
            stub.newChange()
            stub.createFile("existing.txt", "modified")
            stub.createFile("brand-new.txt", "new file")
            stub.renameFile("to-rename.txt", "renamed.txt")
            stub.workDir.resolve("to-delete.txt").toFile().delete()

            val changes = logService.getFileChanges(workingCopy).getOrThrow()

            changes shouldHaveSize 4
            changes.forAtLeastOne {
                it.after?.filePath?.path shouldBe "brand-new.txt"
                it.status shouldBe FileChange.Status.ADDED
            }
            changes.forAtLeastOne {
                it.before?.filePath?.path shouldBe "existing.txt"
                it.status shouldBe FileChange.Status.MODIFIED
            }
            changes.forAtLeastOne {
                it.before?.filePath?.path shouldBe "to-delete.txt"
                it.status shouldBe FileChange.Status.DELETED
            }
            changes.forAtLeastOne {
                it.before?.filePath?.path shouldBe "to-rename.txt"
                it.after?.filePath?.path shouldBe "renamed.txt"
                it.status shouldBe FileChange.Status.RENAMED
            }
        }

        @Test
        fun `no changes returns empty list`() {
            val changes = logService.getFileChanges(workingCopy).getOrThrow()

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
