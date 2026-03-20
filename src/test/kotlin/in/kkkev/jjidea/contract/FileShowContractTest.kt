package `in`.kkkev.jjidea.contract

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class FileShowContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjBackend

    abstract fun createBackend(tempDir: Path): JjBackend

    @BeforeEach
    fun setUp() {
        jj = createBackend(tempDir)
        jj.init()
    }

    @Test
    fun `file show returns exact file content`() {
        val content = "Hello, World!\nSecond line\n"
        jj.createFile("test.txt", content)

        val result = jj.run("file", "show", "-r", "@", "test.txt")
        result.isSuccess shouldBe true
        result.stdout shouldBe content
    }

    @Test
    fun `file show for nested path`() {
        val content = "nested content\n"
        jj.createFile("src/main/file.kt", content)

        val result = jj.run("file", "show", "-r", "@", "src/main/file.kt")
        result.isSuccess shouldBe true
        result.stdout shouldBe content
    }

    @Test
    fun `file show at parent revision`() {
        val original = "original\n"
        jj.createFile("file.txt", original)
        jj.describe("Original version")
        jj.newChange()
        jj.createFile("file.txt", "modified\n")

        val result = jj.run("file", "show", "-r", "@-", "file.txt")
        result.isSuccess shouldBe true
        result.stdout shouldBe original
    }

    @Test
    fun `file show binary-safe`() {
        // Empty file
        jj.createFile("empty.txt", "")

        val result = jj.run("file", "show", "-r", "@", "empty.txt")
        result.isSuccess shouldBe true
        result.stdout shouldBe ""
    }
}
