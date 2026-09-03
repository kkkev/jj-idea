package `in`.kkkev.jjidea.ui.statusbar

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JujutsuStatusBarWidgetTest {
    @Test
    fun `widget is enabled by default`() {
        JujutsuStatusBarWidgetFactory().isEnabledByDefault() shouldBe true
    }
}
