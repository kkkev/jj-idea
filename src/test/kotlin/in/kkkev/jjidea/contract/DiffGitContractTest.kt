package `in`.kkkev.jjidea.contract

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class DiffGitContractTest {
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
    fun `diff git for added file has no rename lines`() {
        jj.createFile("new.txt", "content")

        val result = jj.run("diff", "--git", "-r", "@")
        result.isSuccess shouldBe true
        result.stdout shouldNotContain "rename from"
        result.stdout shouldNotContain "rename to"
    }

    @Test
    fun `diff git for deleted file has no rename lines`() {
        jj.createFile("doomed.txt", "bye")
        jj.newChange()
        tempDir.resolve("doomed.txt").toFile().delete()

        val result = jj.run("diff", "--git", "-r", "@")
        result.isSuccess shouldBe true
        result.stdout shouldNotContain "rename from"
        result.stdout shouldNotContain "rename to"
    }

    @Test
    fun `diff git for modified file has no rename lines`() {
        jj.createFile("file.txt", "original")
        jj.newChange()
        jj.createFile("file.txt", "modified")

        val result = jj.run("diff", "--git", "-r", "@")
        result.isSuccess shouldBe true
        result.stdout shouldNotContain "rename from"
        result.stdout shouldNotContain "rename to"
    }

    @Test
    fun `diff git for renamed file contains rename lines`() {
        jj.createFile("old.txt", "content")
        jj.newChange()
        jj.renameFile("old.txt", "new.txt")

        val result = jj.run("diff", "--git", "-r", "@")
        result.isSuccess shouldBe true
        result.stdout shouldContain "rename from old.txt"
        result.stdout shouldContain "rename to new.txt"
    }

    @Test
    fun `diff git for multiple renames contains all rename pairs`() {
        jj.createFile("alpha.txt", "aaa")
        jj.createFile("beta.txt", "bbb")
        jj.newChange()
        jj.renameFile("alpha.txt", "alpha_renamed.txt")
        jj.renameFile("beta.txt", "beta_renamed.txt")

        val result = jj.run("diff", "--git", "-r", "@")
        result.isSuccess shouldBe true
        result.stdout shouldContain "rename from alpha.txt"
        result.stdout shouldContain "rename to alpha_renamed.txt"
        result.stdout shouldContain "rename from beta.txt"
        result.stdout shouldContain "rename to beta_renamed.txt"
    }
}
