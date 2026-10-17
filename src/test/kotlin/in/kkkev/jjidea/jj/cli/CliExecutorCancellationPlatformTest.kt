package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.concurrency.AppExecutorUtil
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * jj-idea-innq: cancelling the progress a read-only jj command runs under destroys the jj process, while a
 * command that changes the repo still runs to completion. Uses a fake `jj` script that records its PID and
 * then sleeps, so the test can check the process itself is gone. Platform-tagged because cancellation
 * reaches the executor through [ProgressManager], which needs a live `Application`.
 */
@Tag("platform")
@TestApplication
class CliExecutorCancellationPlatformTest {
    @TempDir
    lateinit var tempDir: Path

    private fun fakeJj(sleepSeconds: Int): Pair<CliExecutor, Path> {
        val pidFile = tempDir.resolve("pid")
        val script = tempDir.resolve("jj").also {
            Files.writeString(it, "#!/bin/sh\necho $$ > '$pidFile'\nexec sleep $sleepSeconds\n")
            it.toFile().setExecutable(true)
        }
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        return CliExecutor(root, executableProvider = { script.toString() }) to pidFile
    }

    /** Runs [block] on a pooled thread under [indicator], returning whatever it threw (or its result). */
    private fun runUnder(indicator: EmptyProgressIndicator, block: () -> Any?) =
        AppExecutorUtil.getAppExecutorService().submit<Any?> {
            try {
                var result: Any? = null
                ProgressManager.getInstance().runProcess({ result = block() }, indicator)
                result
            } catch (t: Throwable) {
                t
            }
        }

    private fun awaitPid(pidFile: Path): Long {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (pidFile.exists()) pidFile.readText().trim().toLongOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("fake jj never started")
    }

    private fun isAlive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test
    fun `cancelling a read-only command kills the jj process`() {
        val (executor, pidFile) = fakeJj(sleepSeconds = 30)
        val indicator = EmptyProgressIndicator()

        val outcome = runUnder(indicator) { executor.opLog(1, null) }
        val pid = awaitPid(pidFile)
        indicator.cancel()

        outcome.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<ProcessCanceledException>()
        val deadline = System.currentTimeMillis() + 5_000
        while (isAlive(pid) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        isAlive(pid) shouldBe false
    }

    @Test
    fun `cancelling a read-only bytes command kills the jj process`() {
        val (executor, pidFile) = fakeJj(sleepSeconds = 30)
        val indicator = EmptyProgressIndicator()
        val filePath = LocalFilePath(tempDir.resolve("f.txt").toString(), false)

        val outcome = runUnder(indicator) { executor.showBytes(filePath, WorkingCopy) }
        val pid = awaitPid(pidFile)
        indicator.cancel()

        outcome.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<ProcessCanceledException>()
        val deadline = System.currentTimeMillis() + 5_000
        while (isAlive(pid) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        isAlive(pid) shouldBe false
    }

    @Test
    fun `a command that changes the repo runs to completion despite cancellation`() {
        val (executor, pidFile) = fakeJj(sleepSeconds = 1)
        val indicator = EmptyProgressIndicator()

        val outcome = runUnder(indicator) { executor.describe(Description("msg"), WorkingCopy) }
        awaitPid(pidFile)
        indicator.cancel()

        outcome.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<CommandExecutor.CommandResult.Success>()
    }

    @Test
    fun `an undo-tracked change still returns its result when cancelled before the op log read-back`() {
        val (executor, pidFile) = fakeJj(sleepSeconds = 1)
        val indicator = EmptyProgressIndicator()

        val outcome = runUnder(indicator) { executor.withUndoTracking().describe(Description("msg"), WorkingCopy) }
        awaitPid(pidFile)
        indicator.cancel()

        // The fake prints no op log, so the operation can't be identified - but the change did happen, so this is
        // still a Success rather than a cancellation.
        outcome.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<CommandExecutor.CommandResult.Success>()
    }

    @Test
    fun `an already-cancelled caller never starts a read-only process`() {
        val (executor, pidFile) = fakeJj(sleepSeconds = 30)
        val indicator = EmptyProgressIndicator()

        // Cancelled inside the block: ProgressManager.runProcess starts the indicator, which clears a cancel made before it.
        runUnder(indicator) {
            indicator.cancel()
            executor.opLog(1, null)
        }.get(10, TimeUnit.SECONDS)
            .shouldBeInstanceOf<ProcessCanceledException>()
        pidFile.exists() shouldBe false
    }
}
