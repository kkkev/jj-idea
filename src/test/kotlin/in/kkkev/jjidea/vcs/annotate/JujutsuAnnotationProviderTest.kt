package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.testFramework.LoggedErrorProcessor
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
}
