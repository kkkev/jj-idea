package `in`.kkkev.jjidea.actions.file

import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.fragments.LineFragmentImpl
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TranslateRangeTest {
    // LineFragment changed from a Java interface (getters) to a Kotlin interface (val
    // properties) in 2025.2, so an anonymous implementation can't compile against the whole
    // supported range — use the platform's own impl class, whose ctor is stable.
    private fun fragment(sl1: Int, el1: Int, sl2: Int, el2: Int): LineFragment =
        LineFragmentImpl(sl1, el1, sl2, el2, 0, 0, 0, 0)

    @Nested
    inner class `no fragments` {
        @Test
        fun `single line passes through`() = translateRange(emptyList(), 5..5) shouldBe 5..5

        @Test
        fun `range passes through`() = translateRange(emptyList(), 3..7) shouldBe 3..7
    }

    @Nested
    inner class `context lines (unchanged)` {
        // remote: [A, B, C, D, E]  local: [A, X, Y, C, D, E]
        // fragment: remote [1,2), local [1,3)
        private val frags = listOf(fragment(1, 2, 1, 3))

        @Test
        fun `line before fragment unchanged`() = translateRange(frags, 1..1) shouldBe 1..1

        @Test
        fun `line after fragment shifted back`() = translateRange(frags, 4..4) shouldBe 3..3

        @Test
        fun `last line after fragment`() = translateRange(frags, 6..6) shouldBe 5..5
    }

    @Nested
    inner class `lines inside changed block` {
        // remote: [A, B, C]  local: [A, X, Y, Z, C]
        // fragment: remote [1,2), local [1,4)
        private val frags = listOf(fragment(1, 2, 1, 4))

        @Test
        fun `first local line in block maps to start of remote block`() =
            translateRange(frags, 2..2) shouldBe 2..2

        @Test
        fun `middle local line in block maps to start of remote block`() =
            translateRange(frags, 3..3) shouldBe 2..2

        @Test
        fun `last local line in block maps to start of remote block`() =
            translateRange(frags, 4..4) shouldBe 2..2
    }

    @Nested
    inner class `range spanning boundary` {
        // remote: [A, B, C, D]  local: [A, X, Y, C, D]
        // fragment: remote [1,2), local [1,3)  (B → X,Y)
        private val frags = listOf(fragment(1, 2, 1, 3))

        @Test
        fun `range starting in block ending after it`() =
            translateRange(frags, 2..4) shouldBe 2..3

        @Test
        fun `range entirely after fragment`() =
            translateRange(frags, 4..5) shouldBe 3..4
    }

    @Nested
    inner class `multiple fragments` {
        // remote: [A, B, C, D, E, F]
        // local:  [A, X, C, D, Y, Z, F]
        // fragment1: remote [1,2), local [1,2)  (B → X, same count, delta stays 0)
        // fragment2: remote [4,5), local [4,6)  (E → Y,Z, delta becomes 1)
        private val frags = listOf(fragment(1, 2, 1, 2), fragment(4, 5, 4, 6))

        @Test
        fun `line before first fragment`() = translateRange(frags, 1..1) shouldBe 1..1

        @Test
        fun `line in first fragment`() = translateRange(frags, 2..2) shouldBe 2..2

        @Test
        fun `context between fragments`() = translateRange(frags, 3..3) shouldBe 3..3

        @Test
        fun `line in second fragment`() = translateRange(frags, 5..5) shouldBe 5..5

        @Test
        fun `line after second fragment shifted`() = translateRange(frags, 7..7) shouldBe 6..6
    }

    @Nested
    inner class `pure deletion (remote has lines local doesn't)` {
        // remote: [A, B, C, D]  local: [A, D]
        // fragment: remote [1,3), local [1,1)
        private val frags = listOf(fragment(1, 3, 1, 1))

        @Test
        fun `line after deletion shifted back`() = translateRange(frags, 2..2) shouldBe 4..4
    }
}
