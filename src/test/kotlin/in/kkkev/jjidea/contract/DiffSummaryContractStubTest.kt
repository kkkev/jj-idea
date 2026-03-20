package `in`.kkkev.jjidea.contract

import org.junit.jupiter.api.Tag
import java.nio.file.Path

@Tag("stub")
class DiffSummaryContractStubTest : DiffSummaryContractTest() {
    override fun createBackend(tempDir: Path) = JjStub(tempDir)
}
