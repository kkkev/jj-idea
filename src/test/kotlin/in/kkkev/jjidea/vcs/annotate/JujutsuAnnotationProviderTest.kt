package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.testFramework.LoggedErrorProcessor
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuStateModel
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.util.NotifiableState
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

/**
 * Regression tests for jj-idea-v72r / GitHub #45: annotateInternal must rethrow control-flow
 * exceptions (ProcessCanceledException, and CancellationException such as
 * ContainerDisposedException raised while the project is being disposed on window close)
 * instead of routing them through Logger.error(), which IntelliJ forbids for control flow.
 */
class JujutsuAnnotationProviderTest {
    private val project = mockk<Project>()
    private val vcs = JujutsuVcs(project)
    private val repo = mockk<JujutsuRepository>()
    private val commandExecutor = mockk<CommandExecutor>()
    private val file = MockVirtualFile("test.txt")
    private val provider = JujutsuAnnotationProvider(project, vcs)

    @Test
    fun `rethrows ProcessCanceledException instead of wrapping it in VcsException`() {
        every { repo.commandExecutor } returns commandExecutor
        every { commandExecutor.annotate(any(), any(), any()) } throws ProcessCanceledException()

        shouldThrow<ProcessCanceledException> {
            provider.annotateInternal(file, WorkingCopy, repo)
        }
    }

    @Test
    fun `rethrows CancellationException (e_g_ ContainerDisposedException) instead of wrapping it in VcsException`() {
        every { repo.commandExecutor } returns commandExecutor
        every { commandExecutor.annotate(any(), any(), any()) } throws
            CancellationException("Container 'foo' was disposed")

        shouldThrow<CancellationException> {
            provider.annotateInternal(file, WorkingCopy, repo)
        }
    }

    @Test
    fun `wraps other exceptions in VcsException`() {
        every { repo.commandExecutor } returns commandExecutor
        every { commandExecutor.annotate(any(), any(), any()) } throws RuntimeException("boom")

        // This path legitimately calls log.error(), which the test framework's logger turns into
        // a hard failure unless we tell it the error is expected.
        LoggedErrorProcessor.executeAndReturnLoggedError {
            shouldThrow<VcsException> {
                provider.annotateInternal(file, WorkingCopy, repo)
            }
        }
    }

    // Regression test for jj-idea reported crash: annotating a merge commit (working copy or
    // historical) must not pass the literal "@-" revset to `jj file annotate`, since for a merge
    // that resolves to multiple revisions and jj fails with "resolved to more than one revision".
    @Test
    fun `beforeRevisionFor falls back to first parent for a merge commit`() {
        val child: Revision = ChangeId("child1", "child1")
        val parent1 = ChangeId("parent1", "parent1")
        val parent2 = ChangeId("parent2", "parent2")
        val logEntry = mockk<LogEntry>()
        every { logEntry.parentIds } returns listOf(parent1, parent2)
        every { repo.getLogEntry(child) } returns logEntry

        val result = provider.beforeRevisionFor(MergeParentOf(child), WorkingCopy, repo)

        result shouldBe parent1
    }

    @Test
    fun `beforeRevisionFor uses the before revision directly when it is a real revision`() {
        val parent = ChangeId("parent1", "parent1")

        val result = provider.beforeRevisionFor(parent, WorkingCopy, repo)

        result shouldBe parent
    }

    // Regression test for jj-idea-hpvu / GitHub #64: a timed-out annotate used to surface as
    // "Failed to annotate file: " with an empty stderr, because CommandResult had no way to
    // distinguish a timeout from an ordinary failure. It must now render a real, non-empty message.
    @Test
    fun `surfaces a real message instead of an empty string when annotate times out`() {
        every { repo.commandExecutor } returns commandExecutor
        every { repo.directory } returns MockVirtualFile("repo")
        every { commandExecutor.annotate(any(), any(), any()) } returns
            CommandExecutor.CommandResult(exitCode = -1, stdout = "", stderr = "", timedOut = true)

        val exception = shouldThrow<VcsException> {
            provider.annotateInternal(file, WorkingCopy, repo)
        }

        exception.message shouldNotBe null
        exception.message!!.isBlank() shouldBe false
    }

    // Regression test for jj-idea-a921: the cache used to be a plain, never-invalidated HashMap,
    // so a stale annotation (computed against the old @-) would keep being served forever after
    // a working-copy change. It must now be cleared when the working-copy state model notifies.
    @Test
    fun `working-copy change invalidates the annotation cache`() {
        val listenerSlot = slot<NotifiableState.Listener<Map<String, LogEntry>>>()
        val workingCopies = mockk<NotifiableState<Map<String, LogEntry>>> {
            every { connect(any(), capture(listenerSlot)) } just Runs
        }
        val stateModel = mockk<JujutsuStateModel> {
            every { this@mockk.workingCopies } returns workingCopies
        }
        every { project.getService(JujutsuStateModel::class.java) } returns stateModel

        val fakeAnnotation = mockk<FileAnnotation>()
        // First cache access subscribes to working-copy-change invalidation as a side effect.
        provider.getFromCache(file) shouldBe null
        provider.cacheForTest()[file] = fakeAnnotation
        provider.getFromCache(file) shouldBe fakeAnnotation

        listenerSlot.captured.changed(emptyMap())

        provider.getFromCache(file) shouldBe null
    }

    // jj-idea-hq4d: annotate(FilePath, VcsRevisionNumber) is the AnnotationProviderEx entry point
    // the platform's built-in Annotate action calls for a VcsVirtualFile (e.g. a file opened from
    // the log or File History). Unlike annotate(VirtualFile), which deliberately annotates at @-
    // to match the LineStatusTracker base, this must annotate *at* the requested revision.
    @Test
    fun `annotate(path, revision) annotates at the requested revision, not the working-copy parent`() {
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        try {
            val filePath = LocalFilePath("/repo/file.txt", false)
            every { project.jujutsuRepositoryFor(filePath) } returns repo
            val targetRevision = ChangeId("target", "target")
            every { repo.getVirtualFile(FileAtVersion(filePath, targetRevision)) } returns file
            every { repo.commandExecutor } returns commandExecutor
            every { repo.directory } returns MockVirtualFile("repo")
            every { repo.workingCopy } returns mockk { every { id } returns ChangeId("wc", "wc") }
            every { commandExecutor.annotate(file, targetRevision, any()) } returns
                CommandExecutor.CommandResult(exitCode = 0, stdout = "", stderr = "")

            provider.annotate(filePath, ChangeIdRevisionNumber(targetRevision))

            verify(exactly = 1) { commandExecutor.annotate(file, targetRevision, any()) }
        } finally {
            unmockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        }
    }

    @Test
    fun `annotate(path, revision) throws VcsException when no virtual file can be resolved`() {
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        try {
            val filePath = LocalFilePath("/repo/file.txt", false)
            every { project.jujutsuRepositoryFor(filePath) } returns repo
            val targetRevision = ChangeId("target", "target")
            every { repo.getVirtualFile(FileAtVersion(filePath, targetRevision)) } returns null

            shouldThrow<VcsException> {
                provider.annotate(filePath, ChangeIdRevisionNumber(targetRevision))
            }
        } finally {
            unmockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        }
    }
}
