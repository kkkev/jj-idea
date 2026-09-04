package `in`.kkkev.jjidea.preview

import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private const val PROPERTY = "jjidea.preview.dragAndDrop"

// Runs against the sandboxed plugin jar, which bundles only src/main/resources - so unlike the
// plain-unit-test AccessCodeTest (which shadows that resource from src/test/resources), this uses
// the real shipped code rather than a test fixture.
private const val VALID_CODE = "onyx-amber-9769"

/**
 * Provider precedence for [PreviewEntitlement.isEnabled] (jj-idea-vpvz): system property beats
 * everything; an access code without the feature opted in is off; the feature opted in without a
 * valid code is off; both together is on; default is off. Needs the real app settings service, so
 * this is a platform test with the same global-state reset discipline as
 * `JujutsuSettingsPlatformTest`.
 */
@Tag("platform")
@TestApplication
class PreviewEntitlementTest {
    private val entitlement = PreviewEntitlement.getInstance()

    @AfterEach
    fun resetState() {
        System.clearProperty(PROPERTY)
        val state = JujutsuApplicationSettings.getInstance().state
        state.previewAccessCode = ""
        state.enabledPreviewFeatures = ""
    }

    @Test
    fun `default is off`() {
        entitlement.isEnabled(PreviewFeature.DRAG_AND_DROP) shouldBe false
    }

    @Test
    fun `system property turns the feature on with no code`() {
        System.setProperty(PROPERTY, "true")
        entitlement.isEnabled(PreviewFeature.DRAG_AND_DROP) shouldBe true
    }

    @Test
    fun `a valid code without the toggle is off`() {
        val state = JujutsuApplicationSettings.getInstance().state
        state.previewAccessCode = VALID_CODE
        entitlement.isEnabled(PreviewFeature.DRAG_AND_DROP) shouldBe false
    }

    @Test
    fun `the toggle without a valid code is off`() {
        val state = JujutsuApplicationSettings.getInstance().state
        state.enabledPreviewFeatures = PreviewFeature.DRAG_AND_DROP.id
        entitlement.isEnabled(PreviewFeature.DRAG_AND_DROP) shouldBe false
    }

    @Test
    fun `an invalid code with the toggle is off`() {
        val state = JujutsuApplicationSettings.getInstance().state
        state.previewAccessCode = "wrong-code"
        state.enabledPreviewFeatures = PreviewFeature.DRAG_AND_DROP.id
        entitlement.isEnabled(PreviewFeature.DRAG_AND_DROP) shouldBe false
    }

    @Test
    fun `a valid code with the toggle is on`() {
        val state = JujutsuApplicationSettings.getInstance().state
        state.previewAccessCode = VALID_CODE
        state.enabledPreviewFeatures = PreviewFeature.DRAG_AND_DROP.id
        entitlement.isEnabled(PreviewFeature.DRAG_AND_DROP) shouldBe true
    }

    @Test
    fun `system property beats an invalid code and no toggle`() {
        System.setProperty(PROPERTY, "true")
        val state = JujutsuApplicationSettings.getInstance().state
        state.previewAccessCode = "wrong-code"
        entitlement.isEnabled(PreviewFeature.DRAG_AND_DROP) shouldBe true
    }
}
