package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * `update()` hides/disables [advanceClosestBookmarkAction] whenever `repo()`/`closest()` is null,
 * so the platform shouldn't invoke `actionPerformed` while either is - reaching it anyway means
 * `update()` and `actionPerformed()` disagreed about state, not a normal user path. Guards that
 * this throws rather than silently no-oping (contributing.md's error-handling rules).
 */
@Tag("platform")
@TestApplication
class AdvanceClosestBookmarkActionInvariantTest {
    @Test
    fun `throws rather than silently no-oping when performed with no repo bound`() {
        val action = advanceClosestBookmarkAction({ null }, { null })
        shouldThrow<IllegalStateException> { action.actionPerformed(TestActionEvent.createTestEvent(action)) }
    }
}
