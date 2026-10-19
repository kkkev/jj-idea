package `in`.kkkev.jjidea.ui.common

import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** Platform-tagged because the line diff is the platform's `ComparisonManager` (an app service). */
@Tag("platform")
@TestApplication
class HunkTogglesTest {
    private val before = "a\nb\nc\nd\ne\nf\ng\n"
    private val after = "a\nB\nc\nd\ne\nF\nf2\ng\n" // hunk 0: b→B, hunk 1: f→F,f2

    private fun toggles() = HunkToggles.of(before, after).shouldNotBeNull()

    @Test
    fun `finds one hunk per separated change`() {
        toggles().hunks.size shouldBe 2
    }

    @Test
    fun `compose with none applied is before and all applied is after`() {
        val t = toggles()
        t.compose(emptySet()) shouldBe before
        t.compose(setOf(0, 1)) shouldBe after
    }

    @Test
    fun `compose applies exactly the chosen hunk`() {
        val t = toggles()
        t.compose(setOf(0)) shouldBe "a\nB\nc\nd\ne\nf\ng\n"
        t.compose(setOf(1)) shouldBe "a\nb\nc\nd\ne\nF\nf2\ng\n"
    }

    @Test
    fun `appliedIn round-trips every subset`() {
        val t = toggles()
        for (subset in listOf(emptySet(), setOf(0), setOf(1), setOf(0, 1))) {
            t.appliedIn(t.compose(subset)) shouldBe subset
        }
    }

    @Test
    fun `appliedIn returns null for a line-level edit that is not a whole-hunk subset`() {
        toggles().appliedIn("a\nb\nc\nd\ne\nF\ng\n").shouldBeNull() // hunk 1 half-applied
    }

    @Test
    fun `pure insertion and pure deletion hunks round-trip`() {
        val t = HunkToggles.of("a\nb\nc\n", "a\nX\nb\n").shouldNotBeNull() // insert X, delete c
        t.hunks.size shouldBe 2
        t.appliedIn(t.compose(setOf(0))) shouldBe setOf(0)
        t.appliedIn(t.compose(setOf(1))) shouldBe setOf(1)
        t.compose(setOf(0, 1)) shouldBe "a\nX\nb\n"
    }

    @Test
    fun `new file is a single hunk and missing trailing newline round-trips`() {
        val added = HunkToggles.of("", "x\ny").shouldNotBeNull()
        added.hunks.size shouldBe 1
        added.compose(setOf(0)) shouldBe "x\ny"
        added.compose(emptySet()) shouldBe ""
    }

    @Test
    fun `no toggles when there is nothing to toggle`() {
        HunkToggles.of("same\n", "same\n").shouldBeNull()
    }

    @Test
    fun `building and deriving cost one line diff each, not one per hunk`() {
        val calls = AtomicInteger()
        val counting = LineDiffer { a, b ->
            calls.incrementAndGet()
            platformLineDiffer.diff(a, b)
        }
        val big = (0 until 400).joinToString("") { "line$it\n" }
        val changed = (0 until 400).joinToString("") { (if (it % 10 == 0) "CHANGED$it" else "line$it") + "\n" }
        val t = HunkToggles.of(big, changed, counting).shouldNotBeNull()
        t.hunks.size shouldBe 40
        calls.get() shouldBe 1
        // Content this model composed is recognised without re-diffing...
        t.appliedIn(t.compose(setOf(3, 7))) shouldBe setOf(3, 7)
        calls.get() shouldBe 1
        // ...while foreign content costs exactly one diff.
        t.appliedIn(changed.replace("CHANGED10\n", "line10\n")) shouldBe (0 until 40).filter { it != 1 }.toSet()
        calls.get() shouldBe 2
    }
}
