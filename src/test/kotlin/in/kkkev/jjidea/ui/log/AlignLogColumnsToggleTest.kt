package `in`.kkkev.jjidea.ui.log

import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettingsState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** jj-idea-t04a (GitHub #91): fixed-width status/change-id padding is opt-in. */
@Tag("platform")
@TestApplication
class AlignLogColumnsToggleTest {
    @AfterEach
    fun reset() {
        JujutsuApplicationSettings.getInstance().state.alignLogColumns = false
    }

    @Test
    fun `alignment is off by default`() {
        JujutsuApplicationSettingsState().alignLogColumns shouldBe false
        alignLogColumns() shouldBe false
    }

    @Test
    fun `alignment follows the setting`() {
        JujutsuApplicationSettings.getInstance().state.alignLogColumns = true
        alignLogColumns() shouldBe true
    }
}
