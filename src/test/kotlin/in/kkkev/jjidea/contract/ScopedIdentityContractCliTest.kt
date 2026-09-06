package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.Config
import `in`.kkkev.jjidea.jj.cli.config
import `in`.kkkev.jjidea.setup.JjUserConfigChecker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * jj-idea-i0e6 (GitHub #89): CaptaiNiveau uses a `--when.repositories` config scope to set a
 * different identity per directory. Verifies both halves of the acceptance criteria against a
 * real `jj` - [JjStub]'s `config list` (`cmdConfigList`) fakes `--user` as "stub has no
 * user-level config" and can't evaluate a `--when` condition, so this is CLI-only:
 * - [Config.ScopedConfig.resolve] resolves the *scoped* value for a repo-rooted executor (unlike
 *   `rootlessConfig`, whose executor has no working directory for `--when.repositories` to match
 *   against).
 * - [JjUserConfigChecker] - already repo-rooted before this fix - doesn't regress to prompting
 *   "Configure Jujutsu User" when only a scope (no `[user]` table, no repo-level override)
 *   supplies the identity.
 */
@Tag("contract")
@RequiresJj
class ScopedIdentityContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var scopeConfigPath: String

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()

        // `--when.repositories` matches `jj root`'s output, which on macOS can differ from
        // tempDir.toString() itself (a /tmp path resolves through the /private symlink) - always
        // ask jj, never assume the two are the same string.
        val repoRoot = jj.run("root").stdout.trim()

        // Written straight to this repo's own file-based config (`jj config path --repo`
        // creates its containing directory even before the file exists) rather than the real
        // user's ~/.config/jj/config.toml, or via a JJ_CONFIG env var CliExecutor has no way to
        // inject per-test. Same "writes a real per-repo config file" pattern as
        // ConfigContractTest's setUp (`jj config set --repo user.name ...`).
        scopeConfigPath = jj.run("config", "path", "--repo").stdout.trim()
        Path.of(scopeConfigPath).writeText(
            """
            [[--scope]]
            --when.repositories = ["$repoRoot"]
            [--scope.user]
            name = "Scoped Name"
            email = "scoped@example.com"
            """.trimIndent()
        )
    }

    private fun repo(): JujutsuRepository {
        val root = mockk<VirtualFile>(relaxed = true) { every { path } returns tempDir.toString() }
        val executor = CliExecutor(root)
        return mockk(relaxed = true) {
            every { commandExecutor } returns executor
            every { directory } returns root
        }
    }

    @Test
    fun `effective identity resolves the scoped value, with the scope's file as its source`() {
        val name = repo().config.effective.resolve(Config.Key.USER_NAME)
        val email = repo().config.effective.resolve(Config.Key.USER_EMAIL)

        name shouldBe Config.Resolved("Scoped Name", "repo", scopeConfigPath)
        email shouldBe Config.Resolved("scoped@example.com", "repo", scopeConfigPath)
    }

    @Test
    fun `JjUserConfigChecker considers identity complete when only a scope supplies it`() {
        val status = JjUserConfigChecker(repo()).checkConfig()

        status.isComplete shouldBe true
    }
}
