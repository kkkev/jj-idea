package `in`.kkkev.jjidea.contract

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class ConfigContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjBackend

    abstract fun createBackend(tempDir: Path): JjBackend

    @BeforeEach
    fun setUp() {
        jj = createBackend(tempDir)
        jj.init()
        jj.run("config", "set", "--repo", "user.name", "Test User")
        jj.run("config", "set", "--repo", "user.email", "test@example.com")
    }

    @Test
    fun `configGet returns plain value without quotes`() {
        val result = jj.run("config", "get", "user.name")
        result.isSuccess shouldBe true
        result.stdout.trim() shouldBe "Test User"
    }

    @Test
    fun `configGet email returns plain value`() {
        val result = jj.run("config", "get", "user.email")
        result.isSuccess shouldBe true
        result.stdout.trim() shouldBe "test@example.com"
    }

    @Test
    fun `configGet unknown key fails`() {
        val result = jj.run("config", "get", "no.such.key")
        result.isSuccess shouldBe false
    }

    @Test
    fun `configList returns key-value entry in key equals quoted-value format`() {
        val result = jj.run("config", "list", "user.name")
        result.isSuccess shouldBe true
        result.stdout.trim() shouldBe "user.name = \"Test User\""
    }

    @Test
    fun `configList returns blank for missing key`() {
        val result = jj.run("config", "list", "no.such.key")
        result.stdout.isBlank() shouldBe true
    }
}
