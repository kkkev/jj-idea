package `in`.kkkev.jjidea.util

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [SimpleNotifiableState.hasLoaded] (jj-idea-a52h): consumers like
 * [in.kkkev.jjidea.ui.log.JujutsuReferenceFilterComponent] need to distinguish "hasn't loaded yet"
 * from "loaded, and genuinely empty" - both look identical via [NotifiableState.value] alone, since
 * an unloaded state just sits at its start value.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class SimpleNotifiableStateHasLoadedTest {
    private val project = projectFixture()

    @Test
    fun `hasLoaded is false before the first invalidate, true after it completes`() {
        val state = SimpleNotifiableState(
            project.get(),
            "Test State ${System.nanoTime()}",
            startValue = 0,
            equalityCheck = { a, b -> a == b }
        ) { 42 }

        state.hasLoaded shouldBe false

        state.invalidate()
        drainBackgroundLoads()

        state.hasLoaded shouldBe true
        state.value shouldBe 42
    }

    @Test
    fun `hasLoaded becomes true even when the loaded value equals the start value`() {
        // A loaded-but-still-empty result (e.g. a repo with no bookmarks/tags) must still be
        // distinguishable from "never loaded" - this is exactly the case a same-as-start value
        // would otherwise be indistinguishable from.
        val state = SimpleNotifiableState(
            project.get(),
            "Test State ${System.nanoTime()}",
            startValue = 0,
            equalityCheck = { a, b -> a == b }
        ) { 0 }

        state.invalidate()
        drainBackgroundLoads()

        state.hasLoaded shouldBe true
        state.value shouldBe 0
    }
}
