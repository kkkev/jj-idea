package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RebaseModeTest {
    @Test
    fun `source mode flags`() {
        RebaseSourceMode.REVISION.flag shouldBe "-r"
        RebaseSourceMode.SOURCE.flag shouldBe "-s"
        RebaseSourceMode.BRANCH.flag shouldBe "-b"
    }

    @Test
    fun `destination mode flags`() {
        RebaseDestinationMode.ONTO.flag shouldBe "-d"
        RebaseDestinationMode.INSERT_AFTER.flag shouldBe "-A"
        RebaseDestinationMode.INSERT_BEFORE.flag shouldBe "-B"
    }
}
