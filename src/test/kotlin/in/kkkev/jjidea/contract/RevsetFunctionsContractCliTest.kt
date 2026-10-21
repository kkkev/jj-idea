package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.revset.REVSET_FUNCTIONS
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins, against real jj, that every builtin offered by revset completion (jj-idea-ehwm.7) still parses, so
 * a function jj renames or removes breaks this test instead of silently completing a dead name.
 */
@Tag("contract")
@RequiresJj
class RevsetFunctionsContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `every completion sample parses under the real jj`() {
        val jj = JjCli(tempDir)
        jj.init()

        REVSET_FUNCTIONS.forEach {
            val result = jj.run("log", "--no-graph", "-r", it.sample, "--limit", "0", "-T", "''")
            withClue("${it.name}: ${it.sample}\n${result.stderr}") { result.isSuccess shouldBe true }
        }
    }
}
