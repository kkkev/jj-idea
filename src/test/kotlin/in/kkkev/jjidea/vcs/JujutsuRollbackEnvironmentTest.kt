package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.rollback.RollbackProgressListener
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class JujutsuRollbackEnvironmentTest {
    private val project = mockk<Project>()
    private val accepted = mutableListOf<Change>()
    private val listener = mockk<RollbackProgressListener>(relaxed = true).also {
        every { it.accept(any<Change>()) } answers {
            accepted.add(firstArg())
            Unit
        }
    }
    private val restored = mutableListOf<JujutsuRepository>()

    private fun executor(result: CommandResult = success()) =
        mockk<CommandExecutor> {
            every { withUndoTracking() } returns this
            every { restore(any(), any()) } returns result
        }

    private fun success() = CommandResult.Success.Irreversible(
        "",
        "",
        CommandResult.Success.Irreversible.Reason.entries.first()
    )

    private fun repo(executor: CommandExecutor) =
        mockk<JujutsuRepository> { every { commandExecutor } returns executor }

    private fun path(name: String) = mockk<FilePath>(name = name)

    private fun revision(path: FilePath) = mockk<ContentRevision> { every { file } returns path }

    private fun change(before: FilePath?, after: FilePath?) = Change(before?.let(::revision), after?.let(::revision))

    private fun env(repoFor: (FilePath) -> JujutsuRepository?) =
        JujutsuRollbackEnvironment(project, repoFor, onRestored = { restored.add(it) })

    @Test
    fun `operation name is Restore`() {
        env { null }.rollbackOperationName shouldBe "Restore"
    }

    @Test
    fun `restores all changes of a repo in one invocation and notifies the listener per change`() {
        val exec = executor()
        val repo = repo(exec)
        val a = path("a")
        val b = path("b")
        val c1 = change(a, a)
        val c2 = change(b, b)
        val errors = mutableListOf<VcsException>()

        env { repo }.rollbackChanges(listOf(c1, c2), errors, listener)

        errors.shouldBeEmpty()
        verify(exactly = 1) { exec.restore(listOf(a, b), WorkingCopy.parent) }
        accepted.size shouldBe 2
        (accepted[0] === c1) shouldBe true
        (accepted[1] === c2) shouldBe true
        restored shouldContainExactly listOf(repo)
    }

    @Test
    fun `rename passes both the old and the new path`() {
        val exec = executor()
        val old = path("old")
        val new = path("new")

        env { repo(exec) }.rollbackChanges(listOf(change(old, new)), mutableListOf(), listener)

        verify { exec.restore(listOf(old, new), WorkingCopy.parent) }
    }

    @Test
    fun `changes across two repos issue exactly one restore per repo`() {
        val exec1 = executor()
        val exec2 = executor()
        val repo1 = repo(exec1)
        val repo2 = repo(exec2)
        val files = (1..6).map { path("f$it") }
        val repoByPath = files.associateWith { if (files.indexOf(it) < 3) repo1 else repo2 }

        env { repoByPath[it] }.rollbackChanges(files.map { change(it, it) }, mutableListOf(), listener)

        verify(exactly = 1) { exec1.restore(any(), any()) }
        verify(exactly = 1) { exec2.restore(any(), any()) }
    }

    @Test
    fun `failure is reported as a VcsException and the listener is not notified`() {
        val exec = executor(CommandResult.Failure.Exited("", "boom", 1))
        val a = path("a")
        val errors = mutableListOf<VcsException>()

        env { repo(exec) }.rollbackChanges(listOf(change(a, a)), errors, listener)

        errors shouldHaveSize 1
        errors.single().message shouldContain "boom"
        accepted.shouldBeEmpty()
        restored.shouldBeEmpty()
    }

    @Test
    fun `empty list is a no-op`() {
        val errors = mutableListOf<VcsException>()

        env { error("unused") }.rollbackChanges(emptyList(), errors, listener)

        errors.shouldBeEmpty()
        restored.shouldBeEmpty()
    }

    @Test
    fun `path outside any jj repo yields a VcsException`() {
        val errors = mutableListOf<VcsException>()
        val a = path("a")
        every { a.toString() } returns "/outside/a"

        env { null }.rollbackChanges(listOf(change(a, a)), errors, listener)

        errors shouldHaveSize 1
    }
}
