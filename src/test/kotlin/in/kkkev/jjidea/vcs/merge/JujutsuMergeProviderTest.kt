package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.commandResult
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict
import `in`.kkkev.jjidea.vcs.filePath
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

    // Default: extraction fails for any byte array unless a test stubs a specific one -
    // acceptFilesRevisions's toolFor() then falls back to the pre-existing :ours/:theirs
    // mapping (see JujutsuMergeProviderTest's "acceptFilesRevisions" section below).
    private val extractor = mockk<ConflictExtractor>().also { every { it.extract(any()) } returns null }
    private val provider = JujutsuMergeProvider(project, extractor, repoFor = { null }, refreshEditorNotifications = {})

    private fun extractedConflict(
        current: String = "ours",
        original: String = "base",
        theirs: String = "theirs",
        currentTitle: String? = null,
        lastTitle: String? = null,
        currentIsJjSide1: Boolean = true
    ) = ExtractedConflict(
        mergeData = MergeData().also {
            it.CURRENT = current.toByteArray(Charsets.UTF_8)
            it.ORIGINAL = original.toByteArray(Charsets.UTF_8)
            it.LAST = theirs.toByteArray(Charsets.UTF_8)
        },
        currentTitle = currentTitle,
        lastTitle = lastTitle,
        currentIsJjSide1 = currentIsJjSide1
    )

    @Test
    fun `loadRevisions - conflict content - returns correct MergeData`() {
        val bytes = "content".toByteArray()
        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns bytes
        every { extractor.extract(bytes) } returns extractedConflict()

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

    @Test
    fun `loadConflict - returns the extracted titles`() {
        val bytes = "content".toByteArray()
        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns bytes
        every { extractor.extract(bytes) } returns
            extractedConflict(
                currentTitle = "my change (rebased revision)",
                lastTitle = "modified externally (rebase destination)"
            )

        val result = provider.loadConflict(file)

        result.currentTitle shouldBe "my change (rebased revision)"
        result.lastTitle shouldBe "modified externally (rebase destination)"
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

    // toolFor() (see below) re-extracts the file to check for a reoriented conflict, which
    // means it evaluates the real VirtualFile.filePath extension - stub the extension itself
    // rather than let it fall through to VcsUtil.getFilePath, which needs a live Application
    // this plain unit test doesn't boot.
    @BeforeEach
    fun setUpFilePathExtension() = mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")

    @AfterEach
    fun tearDownFilePathExtension() = unmockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")

    private fun mockFile(filePath: String): VirtualFile {
        val file = mockk<VirtualFile>()
        every { file.path } returns filePath
        every { file.name } returns filePath.substringAfterLast('/')
        every { file.filePath } returns mockk<FilePath>()
        return file
    }

    private fun mockRepo(directoryPath: String, executor: CommandExecutor): JujutsuRepository {
        val directory = mockk<VirtualFile>()
        every { directory.path } returns directoryPath
        val repo = mockk<JujutsuRepository>()
        every { repo.directory } returns directory
        every { repo.commandExecutor } returns executor
        // toolFor() (see acceptFilesRevisions tests) re-extracts each file to check for a
        // reoriented conflict; give it something to read so it doesn't fall through to
        // file.contentsToByteArray(), which these tests don't stub.
        every { repo.createContentRevision(any(), WorkingCopy) } returns mockk<ContentRevision> {
            every { content } returns "irrelevant - extractor default is stubbed to return null"
        }
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
            every { filePath } returns mockk<FilePath>()
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

    // -------------------------------------------------------------------------
    // GitHub #112: "Yours" isn't always jj's side #1 - a reoriented rebase conflict's bulk
    // accept must pick the opposite jj tool from the un-reoriented default, so it still resolves
    // to the same content the interactive dialog just showed as "Yours"/"Theirs".
    // -------------------------------------------------------------------------

    private fun reorientedContentRevision() = mockk<ContentRevision> { every { content } returns "reoriented" }

    @Test
    fun `acceptFilesRevisions - AcceptedYours on a reoriented file - resolves with the theirs tool`() {
        val executor = mockk<CommandExecutor>()
        every { executor.resolve(listOf("foo.txt"), ":theirs", WorkingCopy) } returns commandResult(0, "", "")
        val repo = mockRepo("/repo", executor)
        val file = mockFile("/repo/foo.txt")
        val bytes = "reoriented".toByteArray(Charsets.UTF_8)
        every { repo.createContentRevision(file.filePath, WorkingCopy) } returns reorientedContentRevision()
        every { extractor.extract(bytes) } returns
            ExtractedConflict(MergeData(), currentTitle = null, lastTitle = null, currentIsJjSide1 = false)

        acceptSession(repoFor = { repo }).acceptFilesRevisions(listOf(file), MergeSession.Resolution.AcceptedYours)

        verify { executor.resolve(listOf("foo.txt"), ":theirs", WorkingCopy) }
    }

    @Test
    fun `acceptFilesRevisions - AcceptedTheirs on a reoriented file - resolves with the ours tool`() {
        val executor = mockk<CommandExecutor>()
        every { executor.resolve(listOf("foo.txt"), ":ours", WorkingCopy) } returns commandResult(0, "", "")
        val repo = mockRepo("/repo", executor)
        val file = mockFile("/repo/foo.txt")
        val bytes = "reoriented".toByteArray(Charsets.UTF_8)
        every { repo.createContentRevision(file.filePath, WorkingCopy) } returns reorientedContentRevision()
        every { extractor.extract(bytes) } returns
            ExtractedConflict(MergeData(), currentTitle = null, lastTitle = null, currentIsJjSide1 = false)

        acceptSession(repoFor = { repo }).acceptFilesRevisions(listOf(file), MergeSession.Resolution.AcceptedTheirs)

        verify { executor.resolve(listOf("foo.txt"), ":ours", WorkingCopy) }
    }

    @Test
    fun `acceptFilesRevisions - file no longer extractable - falls back to the literal ours-theirs mapping`() {
        // mockRepo's default createContentRevision + this class's default extractor stub already
        // exercise this (extraction fails, toolFor() catches VcsException) - this test just makes
        // the fallback explicit and pins it against a future change to either default.
        val executor = mockk<CommandExecutor>()
        every { executor.resolve(listOf("foo.txt"), ":ours", WorkingCopy) } returns commandResult(0, "", "")
        val repo = mockRepo("/repo", executor)
        val file = mockFile("/repo/foo.txt")

        acceptSession(repoFor = { repo }).acceptFilesRevisions(listOf(file), MergeSession.Resolution.AcceptedYours)

        verify { executor.resolve(listOf("foo.txt"), ":ours", WorkingCopy) }
    }
}
