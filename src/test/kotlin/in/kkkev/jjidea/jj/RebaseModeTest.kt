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
        RebaseDestinationMode.ONTO.flag shouldBe "--onto"
        RebaseDestinationMode.INSERT_AFTER.flag shouldBe "-A"
        RebaseDestinationMode.INSERT_BEFORE.flag shouldBe "-B"
    }

    // region parseRebaseSourceMode (jj-idea-j8ij)

    @Test
    fun `parseRebaseSourceMode round-trips every enum name`() {
        parseRebaseSourceMode("REVISION") shouldBe RebaseSourceMode.REVISION
        parseRebaseSourceMode("SOURCE") shouldBe RebaseSourceMode.SOURCE
        parseRebaseSourceMode("BRANCH") shouldBe RebaseSourceMode.BRANCH
    }

    @Test
    fun `parseRebaseSourceMode falls back to REVISION for an unrecognized or stale value`() {
        parseRebaseSourceMode("") shouldBe RebaseSourceMode.REVISION
        parseRebaseSourceMode("nonsense") shouldBe RebaseSourceMode.REVISION
    }

    // endregion
}
