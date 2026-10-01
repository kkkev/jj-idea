package `in`.kkkev.jjidea.ui.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.ui.services.JujutsuUndoService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The banner's accept command offers undo (jj-idea-5k16) only when `jj resolve` succeeds with an identified op. */
@Tag("platform")
@TestApplication
@RunInEdt
class ConflictAcceptUndoPlatformTest {
    private val undoService = JujutsuUndoService()
    private val project = mockk<Project> { every { getService(JujutsuUndoService::class.java) } returns undoService }
    private val executor = mockk<CommandExecutor>()
    private val repo = mockk<JujutsuRepository>().also { r ->
        every { r.project } returns project
        every { r.commandExecutor } returns executor
        every { r.directory } returns mockk { every { path } returns "/repo" }
    }
    private val file = mockk<VirtualFile> { every { path } returns "/repo/a.txt" }

    private data class Notified(val operation: OperationId, val label: String)

    private fun run(tool: String, result: CommandResult): List<Notified> {
        every { executor.withUndoTracking() } returns executor
        every { executor.resolve(listOf("a.txt"), tool) } returns result
        val notified = mutableListOf<Notified>()
        acceptSideCommand(project, repo, file, tool) { _, op, label -> notified += Notified(op, label) }
            .action(executor)
        UIUtil.dispatchAllInvocationEvents()
        return notified
    }

    @Test
    fun `offers undo for a reversible accept`() {
        run(":theirs", CommandResult.Success.Reversible("", "", OperationId("op1"))) shouldBe
            listOf(Notified(OperationId("op1"), "Resolve conflict"))
        verify { executor.resolve(listOf("a.txt"), ":theirs") }
    }

    @Test
    fun `no undo on failure`() {
        run(":ours", CommandResult.Failure.Exited("", "boom", 1)) shouldBe emptyList()
    }
}
