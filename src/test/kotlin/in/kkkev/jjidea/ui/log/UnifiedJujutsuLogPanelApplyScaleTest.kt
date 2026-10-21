package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.settings.LogWindowConfig
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Operation-count scale guard for demand-paging landings (jj-idea-2570.9): each landing is a full snapshot
 * whose apply costs O(loaded rows), so a burst of landings must collapse into one apply of the latest.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class UnifiedJujutsuLogPanelApplyScaleTest {
    private val project = projectFixture()

    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    private fun data(rows: Int) = UnifiedJujutsuLogDataLoader.Data(emptyList(), emptyMap(), rows, paged = true)

    @Test
    fun `a burst of landings after the first applies once`() {
        val panel = UnifiedJujutsuLogPanel(project.get(), LogWindowConfig())
        try {
            panel.onDataLoaded(data(1)) // first paint is not delayed
            panel.applyCount shouldBe 1

            val burstSize = 1_000
            repeat(burstSize) { panel.onDataLoaded(data(it + 2)) }
            panel.flushApplyQueue()
            repeat(3) { UIUtil.dispatchAllInvocationEvents() }

            // Exactly one more apply, not burstSize - a regression that applies per landing blows through.
            panel.applyCount shouldBe 2
        } finally {
            Disposer.dispose(panel)
        }
    }
}
