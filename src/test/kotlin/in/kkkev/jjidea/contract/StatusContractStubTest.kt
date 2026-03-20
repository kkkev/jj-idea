package `in`.kkkev.jjidea.contract

import org.junit.jupiter.api.Tag
import java.nio.file.Path

@Tag("stub")
class StatusContractStubTest : StatusContractTest() {
    override fun createBackend(tempDir: Path) = JjStub(tempDir)
}
