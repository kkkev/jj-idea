package `in`.kkkev.jjidea.contract

import org.junit.jupiter.api.Tag
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class MutatingCommandsContractCliTest : MutatingCommandsContractTest() {
    override fun createBackend(tempDir: Path) = JjCli(tempDir)
}
