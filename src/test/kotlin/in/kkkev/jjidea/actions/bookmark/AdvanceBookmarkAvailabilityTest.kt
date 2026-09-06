package `in`.kkkev.jjidea.actions.bookmark

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [singleAdvanceAvailability] and [closestAdvanceAvailability], the pure decision logic
 * behind the two "Advance" actions (jj-idea-l7wd). Version gating is exercised here via a plain
 * boolean rather than a live [in.kkkev.jjidea.jj.JjAvailabilityChecker] — see [JjFeatureTest] for
 * the version-comparison logic itself.
 */
class AdvanceBookmarkAvailabilityTest {
    @Nested
    inner class `singleAdvanceAvailability (per-bookmark action)` {
        @Test
        fun `local bookmark on a supported jj version is enabled`() {
            singleAdvanceAvailability(Bookmark("main"), featureSupported = true) shouldBe
                SingleAdvanceAvailability.ENABLED
        }

        @Test
        fun `local bookmark on an unsupported jj version is disabled`() {
            singleAdvanceAvailability(Bookmark("main"), featureSupported = false) shouldBe
                SingleAdvanceAvailability.UNSUPPORTED_VERSION
        }

        @Test
        fun `remote bookmark is not applicable even when supported`() {
            singleAdvanceAvailability(Bookmark("main@origin"), featureSupported = true) shouldBe
                SingleAdvanceAvailability.NOT_APPLICABLE
        }

        @Test
        fun `deleted bookmark is not applicable even when supported`() {
            singleAdvanceAvailability(Bookmark("main", deleted = true), featureSupported = true) shouldBe
                SingleAdvanceAvailability.NOT_APPLICABLE
        }

        @Test
        fun `deleted takes priority over the version check`() {
            singleAdvanceAvailability(Bookmark("main", deleted = true), featureSupported = false) shouldBe
                SingleAdvanceAvailability.NOT_APPLICABLE
        }
    }

    @Nested
    inner class `closestAdvanceAvailability (advance-whichever-is-closest action)` {
        private val closest = ClosestBookmarks(listOf(BookmarkName("main")), distance = 3, distanceCapped = false)

        @Test
        fun `no repository context is hidden`() {
            closestAdvanceAvailability(hasRepo = false, closest = closest, featureSupported = true) shouldBe
                ClosestAdvanceAvailability.HIDDEN
        }

        @Test
        fun `unsupported jj version is disabled`() {
            closestAdvanceAvailability(hasRepo = true, closest = closest, featureSupported = false) shouldBe
                ClosestAdvanceAvailability.UNSUPPORTED_VERSION
        }

        @Test
        fun `no ancestor bookmark is disabled`() {
            closestAdvanceAvailability(hasRepo = true, closest = null, featureSupported = true) shouldBe
                ClosestAdvanceAvailability.NO_BOOKMARK
        }

        @Test
        fun `repo, closest bookmark, and supported version is enabled`() {
            closestAdvanceAvailability(hasRepo = true, closest = closest, featureSupported = true) shouldBe
                ClosestAdvanceAvailability.ENABLED
        }

        @Test
        fun `missing repo takes priority over the version check`() {
            closestAdvanceAvailability(hasRepo = false, closest = null, featureSupported = false) shouldBe
                ClosestAdvanceAvailability.HIDDEN
        }
    }

    @Nested
    inner class `closestAdvanceTooltip (names the target, jj-idea-xsa8 follow-up)` {
        @Test
        fun `one candidate names it`() {
            closestAdvanceTooltip(
                ClosestBookmarks(listOf(BookmarkName("main")), distance = 1, distanceCapped = false)
            ) shouldBe
                "Move 'main' forward to the working copy (jj bookmark advance)"
        }

        @Test
        fun `several equidistant candidates are comma-joined, each individually quoted`() {
            closestAdvanceTooltip(
                ClosestBookmarks(
                    listOf(BookmarkName("main"), BookmarkName("feature")),
                    distance = 2,
                    distanceCapped = false
                )
            ) shouldBe "Move 'main', 'feature' forward to the working copy (jj bookmark advance)"
        }

        @Test
        fun `null falls back to the generic wording`() {
            closestAdvanceTooltip(null) shouldBe
                "Move the nearest bookmark forward to the working copy (jj bookmark advance)"
        }
    }

    @Nested
    inner class `closest-advance label text (jj-idea-0dq3, GitHub #104)` {
        // A per-row log context menu makes "Here" read as "to this row" - jj bookmark advance
        // always targets @, never the clicked row - so the label must name the working copy
        // explicitly instead, matching the per-bookmark action's own "Advance 'x' to Working Copy".
        @Test
        fun `label names the working copy, not the generic 'Here' wording`() {
            JujutsuBundle.message("action.bookmark.advance.closest") shouldBe "Advance Bookmark to Working Copy"
            JujutsuBundle.message("action.bookmark.advance.closest") shouldNotContain "Here"
        }

        @Test
        fun `disabled-reason suffixes also name the working copy`() {
            JujutsuBundle.message("action.bookmark.advance.closest.disabled.none.suffix") shouldBe
                "Advance Bookmark to Working Copy (nothing to advance)"
            JujutsuBundle.message("action.bookmark.advance.closest.disabled.version", "0.39") shouldBe
                "Advance Bookmark to Working Copy (needs jj 0.39+)"
        }
    }
}
