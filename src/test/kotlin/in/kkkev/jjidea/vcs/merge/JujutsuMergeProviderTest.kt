package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.commandResult
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JujutsuMergeProviderTest {
    private val project = mockk<Project>()
    private val extractor = mockk<ConflictExtractor>()
    private val provider = JujutsuMergeProvider(project, extractor, repoFor = { null }, refreshEditorNotifications = {})

    @Test
    fun `loadRevisions - conflict content - returns correct MergeData`() {
        val bytes = "content".toByteArray()
        val mergeData = MergeData().also {
            it.CURRENT = "ours".toByteArray(Charsets.UTF_8)
            it.ORIGINAL = "base".toByteArray(Charsets.UTF_8)
            it.LAST = "theirs".toByteArray(Charsets.UTF_8)
        }

        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns bytes
        every { extractor.extract(bytes) } returns mergeData

        val result = provider.loadRevisions(file)

        result.CURRENT.toString(Charsets.UTF_8) shouldBe "ours"
        result.ORIGINAL.toString(Charsets.UTF_8) shouldBe "base"
        result.LAST.toString(Charsets.UTF_8) shouldBe "theirs"
    }

    @Test
    fun `loadRevisions - no conflict markers - throws VcsException`() {
        val bytes = "no conflicts here".toByteArray()
        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns bytes
        every { file.name } returns "test.txt"
        every { extractor.extract(bytes) } returns null

        shouldThrow<VcsException> { provider.loadRevisions(file) }
    }

    // -------------------------------------------------------------------------
    // getMergeInfoColumns
    //
    // Regression test for jj-idea-qfgl / GitHub #55: IntelliJ 2026.2's iterative merge
    // dialog indexes unconditionally into [file name column] + getMergeInfoColumns() and
    // throws IndexOutOfBoundsException if that list isn't exactly 3 long. Pin the column
    // count so nobody reverts this to emptyArray().
    // -------------------------------------------------------------------------

    @Test
    fun `getMergeInfoColumns - returns two non-blank columns`() {
        val session = provider.createMergeSession(emptyList()) as com.intellij.openapi.vcs.merge.MergeSessionEx
        val columns = session.mergeInfoColumns

        columns.size shouldBe 2
        columns.all { it.name.isNotBlank() } shouldBe true
    }

    @Test
    fun `isBinary - binary file type - returns true`() {
        val file = mockk<VirtualFile>()
        every { file.fileType } returns mockk { every { isBinary } returns true }

        provider.isBinary(file) shouldBe true
    }

    @Test
    fun `isBinary - text file type - returns false`() {
        val file = mockk<VirtualFile>()
        every { file.fileType } returns mockk { every { isBinary } returns false }

        provider.isBinary(file) shouldBe false
    }

    // -------------------------------------------------------------------------
    // Refresh-after-resolve tests
    // -------------------------------------------------------------------------

    private val dirtyScopeManager = mockk<VcsDirtyScopeManager>(relaxed = true)

    @BeforeEach
    fun setUpDirtyManager() {
        mockkStatic(VcsDirtyScopeManager::class)
        every { VcsDirtyScopeManager.getInstance(project) } returns dirtyScopeManager
    }

    @AfterEach
    fun tearDownDirtyManager() = unmockkStatic(VcsDirtyScopeManager::class)

    private fun refreshProvider(
        repoFor: (VirtualFile) -> JujutsuRepository?,
        refreshAfterResolve: (JujutsuRepository) -> Unit
    ) = JujutsuMergeProvider(project, extractor, repoFor, refreshAfterResolve, refreshEditorNotifications = {})

    @Test
    fun `conflictResolvedForFile - known repo - calls refreshAfterResolve once`() {
        val repo = mockk<JujutsuRepository>()
        val file = mockk<VirtualFile>()
        val refreshed = mutableListOf<JujutsuRepository>()
        val p = refreshProvider(repoFor = { repo }, refreshAfterResolve = { refreshed += it })

        p.conflictResolvedForFile(file)

        refreshed shouldBe listOf(repo)
        verify { dirtyScopeManager.fileDirty(file) }
    }

    @Test
    fun `conflictResolvedForFile - no repo - skips refresh`() {
        val file = mockk<VirtualFile>()
        val refreshed = mutableListOf<JujutsuRepository>()
        val p = refreshProvider(repoFor = { null }, refreshAfterResolve = { refreshed += it })

        p.conflictResolvedForFile(file)

        refreshed shouldBe emptyList()
        verify { dirtyScopeManager.fileDirty(file) }
    }

    @Test
    fun `conflictResolvedForFile - refreshes the editor notification banner for the resolved file`() {
        // jj-idea-aunm: without this, a conflict banner already open in the editor could linger
        // stale after the user resolves the file through some other entry point.
        val file = mockk<VirtualFile>()
        val notified = mutableListOf<VirtualFile>()
        val p = JujutsuMergeProvider(
            project,
            extractor,
            repoFor = { null },
            refreshAfterResolve = {},
            refreshEditorNotifications = { notified += it }
        )

        p.conflictResolvedForFile(file)

        notified shouldBe listOf(file)
    }

    @Test
    fun `conflictResolvedForFiles - two files same repo - refreshes repo once`() {
        val repo = mockk<JujutsuRepository>()
        val file1 = mockk<VirtualFile>()
        val file2 = mockk<VirtualFile>()
        val refreshed = mutableListOf<JujutsuRepository>()
        val p = refreshProvider(repoFor = { repo }, refreshAfterResolve = { refreshed += it })

        p.createMergeSession(listOf(file1, file2))
            .let { it as com.intellij.openapi.vcs.merge.MergeSessionEx }
            .conflictResolvedForFiles(listOf(file1, file2), MergeSession.Resolution.Merged)

        refreshed shouldBe listOf(repo)
    }

    @Test
    fun `conflictResolvedForFiles - files from distinct repos - refreshes each repo once`() {
        val repo1 = mockk<JujutsuRepository>()
        val repo2 = mockk<JujutsuRepository>()
        val file1 = mockk<VirtualFile>()
        val file2 = mockk<VirtualFile>()
        val refreshed = mutableListOf<JujutsuRepository>()
        val p = refreshProvider(
            repoFor = { f -> if (f === file1) repo1 else repo2 },
            refreshAfterResolve = { refreshed += it }
        )

        p.createMergeSession(listOf(file1, file2))
            .let { it as com.intellij.openapi.vcs.merge.MergeSessionEx }
            .conflictResolvedForFiles(listOf(file1, file2), MergeSession.Resolution.Merged)

        refreshed.toSet() shouldBe setOf(repo1, repo2)
        refreshed.size shouldBe 2
    }

    // -------------------------------------------------------------------------
    // acceptFilesRevisions
    //
    // jj-idea-x283: routed through `jj resolve --tool :ours|:theirs` instead of writing
    // MergeData bytes directly, so a modify/delete conflict resolves to an actual deletion
    // rather than an empty file.
    // -------------------------------------------------------------------------

    private fun mockFile(filePath: String): VirtualFile {
        val file = mockk<VirtualFile>()
        every { file.path } returns filePath
        return file
    }

    private fun mockRepo(directoryPath: String, executor: CommandExecutor): JujutsuRepository {
        val directory = mockk<VirtualFile>()
        every { directory.path } returns directoryPath
        val repo = mockk<JujutsuRepository>()
        every { repo.directory } returns directory
        every { repo.commandExecutor } returns executor
        return repo
    }

    private fun acceptSession(
        repoFor: (VirtualFile) -> JujutsuRepository?,
        notifyError: (String, String) -> Unit = { _, _ -> }
    ) = JujutsuMergeProvider(
        project,
        extractor,
        repoFor = repoFor,
        refreshEditorNotifications = {},
        notifyError = notifyError
    ).createMergeSession(emptyList()) as com.intellij.openapi.vcs.merge.MergeSessionEx

    @Test
    fun `acceptFilesRevisions - AcceptedYours - resolves with the ours tool`() {
        val executor = mockk<CommandExecutor>()
        every { executor.resolve(listOf("foo.txt"), ":ours", WorkingCopy) } returns
            commandResult(0, "", "")
        val repo = mockRepo("/repo", executor)
        val file = mockFile("/repo/foo.txt")

        acceptSession(repoFor = { repo }).acceptFilesRevisions(listOf(file), MergeSession.Resolution.AcceptedYours)

        verify { executor.resolve(listOf("foo.txt"), ":ours", WorkingCopy) }
    }

    @Test
    fun `acceptFilesRevisions - AcceptedTheirs - resolves with the theirs tool`() {
        val executor = mockk<CommandExecutor>()
        every { executor.resolve(listOf("foo.txt"), ":theirs", WorkingCopy) } returns
            commandResult(0, "", "")
        val repo = mockRepo("/repo", executor)
        val file = mockFile("/repo/foo.txt")

        acceptSession(repoFor = { repo }).acceptFilesRevisions(listOf(file), MergeSession.Resolution.AcceptedTheirs)

        verify { executor.resolve(listOf("foo.txt"), ":theirs", WorkingCopy) }
    }

    @Test
    fun `acceptFilesRevisions - resolve fails - notifies with the failure reason, does not throw`() {
        val executor = mockk<CommandExecutor>()
        every { executor.resolve(listOf("foo.txt"), ":ours", WorkingCopy) } returns
            commandResult(1, "", "boom")
        val repo = mockRepo("/repo", executor)
        val file = mockk<VirtualFile> {
            every { path } returns "/repo/foo.txt"
            every { name } returns "foo.txt"
        }
        val notifications = mutableListOf<Pair<String, String>>()

        acceptSession(repoFor = { repo }, notifyError = { title, message -> notifications += title to message })
            .acceptFilesRevisions(listOf(file), MergeSession.Resolution.AcceptedYours)

        notifications.size shouldBe 1
        notifications.single().second shouldContain "foo.txt: boom"
    }

    @Test
    fun `acceptFilesRevisions - no repo for file - notifies, does not throw`() {
        val file = mockk<VirtualFile> {
            every { path } returns "/repo/foo.txt"
            every { name } returns "foo.txt"
        }
        val notifications = mutableListOf<Pair<String, String>>()

        acceptSession(repoFor = { null }, notifyError = { title, message -> notifications += title to message })
            .acceptFilesRevisions(listOf(file), MergeSession.Resolution.AcceptedYours)

        notifications.size shouldBe 1
    }

    @Test
    fun `acceptFilesRevisions - Merged resolution - does nothing`() {
        val executor = mockk<CommandExecutor>(relaxed = true)
        val repo = mockRepo("/repo", executor)
        val file = mockFile("/repo/foo.txt")

        acceptSession(repoFor = { repo }).acceptFilesRevisions(listOf(file), MergeSession.Resolution.Merged)

        verify(exactly = 0) { executor.resolve(any(), any(), any()) }
    }
}
