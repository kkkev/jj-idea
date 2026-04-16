package `in`.kkkev.jjidea.contract

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

abstract class GitRemoteContractTest {
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
    fun `git remote list on fresh repo returns empty output`() {
        val result = jj.run("git", "remote", "list")
        result.isSuccess shouldBe true
        result.stdout.trim() shouldBe ""
    }

    @Test
    fun `git remote list shows added remote`() {
        val remoteUrl = tempDir.resolve("remote.git").toString()
        jj.addGitRemote("origin", remoteUrl)

        val result = jj.run("git", "remote", "list")
        result.isSuccess shouldBe true
        result.stdout shouldContain "origin"
        result.stdout shouldContain remoteUrl
    }

    @Test
    fun `git remote list shows multiple remotes`() {
        val url1 = tempDir.resolve("remote1.git").toString()
        val url2 = tempDir.resolve("remote2.git").toString()
        jj.addGitRemote("origin", url1)
        jj.addGitRemote("upstream", url2)

        val result = jj.run("git", "remote", "list")
        result.isSuccess shouldBe true
        result.stdout shouldContain "origin"
        result.stdout shouldContain "upstream"
        result.stdout shouldContain url1
        result.stdout shouldContain url2
    }
}
