package `in`.kkkev.jjidea.contract

import org.junit.jupiter.api.Tag
import java.nio.file.Path

@Tag("stub")
class GitRemoteContractStubTest : GitRemoteContractTest() {
    override fun createBackend(tempDir: Path) = JjStub(tempDir)
}
