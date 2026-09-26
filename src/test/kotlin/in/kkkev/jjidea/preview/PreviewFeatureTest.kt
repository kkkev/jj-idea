package `in`.kkkev.jjidea.preview

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PreviewFeatureTest {
    @Test
    fun `every feature has a resolvable display name`() {
        for (feature in PreviewFeature.entries) {
            feature.displayName.isBlank() shouldBe false
        }
    }

    @Test
    fun `every feature has a unique id`() {
        val ids = PreviewFeature.entries.map { it.id }
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `every feature has a unique bit in range 0 to 6, leaving bit 7 for ALL`() {
        val bits = PreviewFeature.entries.map { it.bit }
        bits.toSet().size shouldBe bits.size
        for (bit in bits) {
            (bit in 0..6) shouldBe true
        }
    }
}
