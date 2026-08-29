package `in`.kkkev.jjidea.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DiffbaseStrategyTest {
    @Test
    fun `WORKING_COPY_PARENT revset is null, meaning no override`() {
        DiffbaseStrategy.WORKING_COPY_PARENT.revset("ignored") shouldBe null
    }

    @Test
    fun `IMMUTABLE_ANCESTOR revset is the latest immutable ancestor expression`() {
        DiffbaseStrategy.IMMUTABLE_ANCESTOR.revset("ignored") shouldBe "latest(ancestors(@-) & immutable())"
    }

    @Test
    fun `CUSTOM_REVSET revset returns the trimmed custom revset`() {
        DiffbaseStrategy.CUSTOM_REVSET.revset("  trunk()  ") shouldBe "trunk()"
    }

    @Test
    fun `CUSTOM_REVSET revset is null when the custom revset is blank`() {
        DiffbaseStrategy.CUSTOM_REVSET.revset("   ") shouldBe null
    }

    @Test
    fun `CUSTOM_REVSET revset is null when the custom revset is empty`() {
        DiffbaseStrategy.CUSTOM_REVSET.revset("") shouldBe null
    }
}
