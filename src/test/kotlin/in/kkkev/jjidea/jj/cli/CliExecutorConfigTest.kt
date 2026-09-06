package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.CommandExecutor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Tests for [configListDetailedArgs] — jj-idea-i0e6's provenance-aware `config list` used by
 * [Config.ScopedConfig.resolve] to show *where* an identity value came from (e.g. a
 * `--when.repositories` scope), not just its value.
 */
class CliExecutorConfigTest {
    @Test
    fun `no scope omits the scope flag`() {
        val args = configListDetailedArgs("user.name", scope = null).args
        args.take(4) shouldBe listOf("config", "list", "user.name", "-T")
        args.size shouldBe 5
    }

    @Test
    fun `user scope`() {
        val args = configListDetailedArgs("user.name", CommandExecutor.ConfigScope.USER).args
        args.take(4) shouldBe listOf("config", "list", "--user", "user.name")
        args[4] shouldBe "-T"
        args.size shouldBe 6
    }

    @Test
    fun `repo scope`() {
        val args = configListDetailedArgs("user.email", CommandExecutor.ConfigScope.REPO).args
        args.take(4) shouldBe listOf("config", "list", "--repo", "user.email")
        args[4] shouldBe "-T"
        args.size shouldBe 6
    }

    @Test
    fun `is read-only, not tracked for undo`() {
        configListDetailedArgs("user.name", scope = null).reversibility shouldBe Reversibility.READ_ONLY
    }

    @Test
    fun `template renders value, source, and path separated by the delimiter, then a newline`() {
        // jj's template language must be able to parse this without a syntax error - the closest
        // an arg-builder unit test can get to that is asserting its shape, since the full
        // pipeline (an actual `jj` invocation) is covered by ScopedIdentityContractCliTest.
        val template = configListDetailedArgs("user.name", scope = null).args.last()
        template shouldContain "value ++"
        template shouldContain "source ++"
        template shouldContain "path ++"
        template shouldContain CONFIG_PROVENANCE_DELIMITER
        template shouldNotContain "\n"
    }
}
