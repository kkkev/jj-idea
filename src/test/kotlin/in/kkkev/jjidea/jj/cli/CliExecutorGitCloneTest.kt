package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [gitCloneArgs] — the argument building logic
 * used by [CliExecutor.gitClone].
 */
class CliExecutorGitCloneTest {
    @Test
    fun `clone with colocate`() {
        gitCloneArgs(
            source = "https://github.com/example/repo.git",
            destination = "/tmp/repo",
            colocate = true
        ) shouldBe listOf("git", "clone", "--colocate", "https://github.com/example/repo.git", "/tmp/repo")
    }

    @Test
    fun `clone without colocate`() {
        gitCloneArgs(
            source = "https://github.com/example/repo.git",
            destination = "/tmp/repo",
            colocate = false
        ) shouldBe listOf("git", "clone", "--no-colocate", "https://github.com/example/repo.git", "/tmp/repo")
    }

    @Test
    fun `clone with SSH URL`() {
        gitCloneArgs(
            source = "git@github.com:example/repo.git",
            destination = "/home/user/projects/repo",
            colocate = true
        ) shouldBe listOf("git", "clone", "--colocate", "git@github.com:example/repo.git", "/home/user/projects/repo")
    }
}
