package `in`.kkkev.jjidea.contract

import org.junit.jupiter.api.Tag
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class DiffGitContractCliTest : DiffGitContractTest() {
    override fun createBackend(tempDir: Path) = JjCli(tempDir)
}
