package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Regression coverage for [DropZone.toDestinationMode]. Manual testing of jj-idea-6jvh's DnD spike
 * caught this as a live bug: the original conversion was a naive same-name identity mapping (top
 * zone -> `RebaseDestinationMode.INSERT_BEFORE`), which rebased the dragged commit to the visually
 * *opposite* side of where it was dropped. The log renders newest-first (children above parents),
 * so the top band - which sits between the destination and its children - is where `jj rebase -A`
 * (`INSERT_AFTER`) lands, not `-B`. See [DropZone.toDestinationMode]'s doc for the full reasoning.
 */
class DropZoneToDestinationModeTest {
    @Test
    fun `the top band (screen INSERT_BEFORE) maps to destination-mode INSERT_AFTER, not INSERT_BEFORE`() {
        DropZone.INSERT_BEFORE.toDestinationMode() shouldBe RebaseDestinationMode.INSERT_AFTER
    }

    @Test
    fun `the bottom band (screen INSERT_AFTER) maps to destination-mode INSERT_BEFORE, not INSERT_AFTER`() {
        DropZone.INSERT_AFTER.toDestinationMode() shouldBe RebaseDestinationMode.INSERT_BEFORE
    }

    @Test
    fun `ONTO maps to destination-mode ONTO`() {
        DropZone.ONTO.toDestinationMode() shouldBe RebaseDestinationMode.ONTO
    }
}
