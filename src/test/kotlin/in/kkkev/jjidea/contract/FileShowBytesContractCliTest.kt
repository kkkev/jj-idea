package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Real-jj verification that [CliExecutor.showBytes] returns `jj file show`'s stdout byte-for-byte
 * (GitHub: image diffs rendered as text). Exercises the real process layer, which is where
 * [CliExecutor.show]'s String decoding used to corrupt binary content.
 */
@Tag("contract")
@RequiresJj
class FileShowBytesContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var executor: CliExecutor

    @BeforeEach
    fun setUp() {
        JjCli(tempDir).init()
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        executor = CliExecutor(root)
    }

    /** Every byte value, plus a PNG signature up front: nothing here survives a UTF-8 round-trip. */
    private val binary = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        ByteArray(256) { it.toByte() }

    private fun write(relativePath: String, bytes: ByteArray) = LocalFilePath(
        tempDir.resolve(relativePath).also {
            Files.createDirectories(it.parent)
            Files.write(it, bytes)
        }.toString(),
        false
    )

    @Test
    fun `showBytes returns binary content byte-for-byte`() {
        val filePath = write("img/logo.png", binary)

        executor.showBytes(filePath, WorkingCopy) shouldBe binary
    }

    @Test
    fun `show's decoded text does not preserve the same binary content`() {
        val filePath = write("img/logo.png", binary)

        executor.show(filePath, WorkingCopy).stdout.toByteArray() shouldNotBe binary
    }

    @Test
    fun `showBytes returns text content unchanged`() {
        val text = "line one\r\nline two — ünïcödé\n".toByteArray()
        val filePath = write("src/Main.kt", text)

        executor.showBytes(filePath, WorkingCopy) shouldBe text
    }

    @Test
    fun `showBytes handles content larger than a pipe buffer`() {
        // Well past a 64KiB pipe buffer, but under jj's default 1MiB snapshot.max-new-file-size.
        val large = ByteArray(512 * 1024) { (it * 31).toByte() }
        val filePath = write("big.bin", large)

        executor.showBytes(filePath, WorkingCopy) shouldBe large
    }

    @Test
    fun `showBytes throws VcsException when jj fails`() {
        shouldThrow<VcsException> {
            executor.showBytes(LocalFilePath(tempDir.resolve("missing.png").toString(), false), WorkingCopy)
        }
    }
}
