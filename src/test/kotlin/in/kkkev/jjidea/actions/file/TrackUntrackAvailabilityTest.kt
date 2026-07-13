package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the pure decision logic behind [TrackedToggleAction] (jj-idea-i9ol): visibility
 * gating ([trackedToggleVisible]), the bounded ignored-prediction check ([anySelectedIgnored]),
 * and the checkbox/partitioning logic ([isFullyTracked], [pathsNeedingChange]) that turns a
 * reliable per-file tracked/untracked answer (from `jj file list`, see
 * docs/jj-track-untrack-model.md) into a single toggle's behavior.
 */
class TrackUntrackAvailabilityTest {
    @Nested
    inner class `trackedToggleVisible` {
        @Test
        fun `hidden in historical context`() {
            trackedToggleVisible(
                isHistoricalContext = true,
                hasSingleRepo = true,
                anyPredictedIgnored = true
            ) shouldBe false
        }

        @Test
        fun `hidden with no single repo`() {
            trackedToggleVisible(
                isHistoricalContext = false,
                hasSingleRepo = false,
                anyPredictedIgnored = true
            ) shouldBe false
        }

        @Test
        fun `hidden when nothing in the selection is predicted-ignored`() {
            trackedToggleVisible(
                isHistoricalContext = false,
                hasSingleRepo = true,
                anyPredictedIgnored = false
            ) shouldBe false
        }

        @Test
        fun `visible in working copy context with a single repo and a predicted-ignored file`() {
            trackedToggleVisible(
                isHistoricalContext = false,
                hasSingleRepo = true,
                anyPredictedIgnored = true
            ) shouldBe true
        }
    }

    @Nested
    inner class `isFullyTracked` {
        @Test
        fun `empty list is not fully tracked (nothing to check)`() {
            isFullyTracked(emptyList()) shouldBe false
        }

        @Test
        fun `all tracked is fully tracked - checkbox checked`() {
            isFullyTracked(
                listOf(TrackedPath(path(1), tracked = true), TrackedPath(path(2), tracked = true))
            ) shouldBe true
        }

        @Test
        fun `all untracked is not fully tracked - checkbox unchecked`() {
            isFullyTracked(
                listOf(TrackedPath(path(1), tracked = false), TrackedPath(path(2), tracked = false))
            ) shouldBe false
        }

        @Test
        fun `mixed selection reads as unchecked - there's still something to track`() {
            isFullyTracked(
                listOf(TrackedPath(path(1), tracked = true), TrackedPath(path(2), tracked = false))
            ) shouldBe false
        }
    }

    @Nested
    inner class `pathsNeedingChange` {
        @Test
        fun `toggling to tracked selects only the untracked members`() {
            val paths = listOf(
                TrackedPath(path(1), tracked = true),
                TrackedPath(path(2), tracked = false),
                TrackedPath(path(3), tracked = false)
            )

            pathsNeedingChange(paths, targetState = true) shouldBe listOf(path(2), path(3))
        }

        @Test
        fun `toggling to untracked selects only the tracked members`() {
            val paths = listOf(
                TrackedPath(path(1), tracked = true),
                TrackedPath(path(2), tracked = true),
                TrackedPath(path(3), tracked = false)
            )

            pathsNeedingChange(paths, targetState = false) shouldBe listOf(path(1), path(2))
        }

        @Test
        fun `already-at-target selection needs no change`() {
            val paths = listOf(TrackedPath(path(1), tracked = true))

            pathsNeedingChange(paths, targetState = true) shouldBe emptyList()
        }
    }

    @Nested
    inner class `anySelectedIgnored bound` {
        private val root = mockk<VirtualFile>(relaxed = true)

        @Test
        fun `no paths is vacuously false`() {
            anySelectedIgnored(emptyList(), root) { _, _ -> true } shouldBe false
        }

        @Test
        fun `short-circuits as soon as an ignored path is found`() {
            var checkedCount = 0
            val paths = (0 until 10).map(::path)

            val result = anySelectedIgnored(paths, root) { _, _ ->
                checkedCount++
                checkedCount == 3
            }

            result shouldBe true
            checkedCount shouldBe 3
        }

        @Test
        fun `checks at most TRACKED_TOGGLE_SELECTION_LIMIT paths`() {
            var checkedCount = 0
            val paths = (0 until TRACKED_TOGGLE_SELECTION_LIMIT * 3).map(::path)

            val result = anySelectedIgnored(paths, root) { _, _ ->
                checkedCount++
                false
            }

            result shouldBe false
            checkedCount shouldBe TRACKED_TOGGLE_SELECTION_LIMIT
        }
    }
}

private fun path(i: Int) = LocalFilePath("/project/file$i.txt", false)
