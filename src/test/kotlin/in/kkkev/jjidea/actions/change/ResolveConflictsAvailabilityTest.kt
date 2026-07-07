package `in`.kkkev.jjidea.actions.change

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [resolveAvailability] and [resolveSelectedAvailability], the pure decision logic
 * behind the "Resolve Conflicts" actions (jj-idea-sm1s).
 */
class ResolveConflictsAvailabilityTest {
    @Nested
    inner class `resolveAvailability (log-commit action)` {
        @Test
        fun `working copy with conflicted files is enabled`() {
            val result = resolveAvailability(isWorkingCopy = true, hasConflict = true, workingCopyConflictCount = 2)

            result shouldBe ResolveAvailability.ENABLED
        }

        @Test
        fun `working copy with no conflicted files is disabled`() {
            val result = resolveAvailability(isWorkingCopy = true, hasConflict = false, workingCopyConflictCount = 0)

            result shouldBe ResolveAvailability.DISABLED
        }

        @Test
        fun `non-working-copy commit with a conflict needs edit`() {
            // e.g. a child that inherits a conflict from an ancestor merge (jj-idea-sm1s)
            val result = resolveAvailability(isWorkingCopy = false, hasConflict = true, workingCopyConflictCount = 0)

            result shouldBe ResolveAvailability.NEEDS_EDIT
        }

        @Test
        fun `non-working-copy commit with no conflict is hidden`() {
            val result = resolveAvailability(isWorkingCopy = false, hasConflict = false, workingCopyConflictCount = 0)

            result shouldBe ResolveAvailability.HIDDEN
        }
    }

    @Nested
    inner class `resolveSelectedAvailability (file-level action)` {
        @Test
        fun `no context conflicts is hidden regardless of context`() {
            resolveSelectedAvailability(
                hasContextConflicts = false,
                isWorkingCopyContext = true
            ) shouldBe ResolveAvailability.HIDDEN
            resolveSelectedAvailability(
                hasContextConflicts = false,
                isWorkingCopyContext = false
            ) shouldBe ResolveAvailability.HIDDEN
        }

        @Test
        fun `conflicts in working copy context are enabled`() {
            val result = resolveSelectedAvailability(hasContextConflicts = true, isWorkingCopyContext = true)

            result shouldBe ResolveAvailability.ENABLED
        }

        @Test
        fun `conflicts in a historical (non-working-copy) context need edit`() {
            val result = resolveSelectedAvailability(hasContextConflicts = true, isWorkingCopyContext = false)

            result shouldBe ResolveAvailability.NEEDS_EDIT
        }
    }
}
