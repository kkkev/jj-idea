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
}
