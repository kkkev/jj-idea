package `in`.kkkev.jjidea.changelog

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * jj-idea-j8s6: unit coverage for the CHANGELOG-release-drift guard, including the 2026-09-13
 * incident (see [ChangelogReleaseGuard.kt]) as a regression fixture.
 */
class ChangelogReleaseGuardTest {
    @Test
    fun `clean release - no entries added since the tag - reports nothing`() {
        val current =
            """
            ## [0.8.15] - 2026-09-07

            ### Fixed
            - Something that really did ship. (#100)
            """.trimIndent()
        val atTag =
            """
            ## [Unreleased]

            ### Fixed
            - Something that really did ship. (#100)
            """.trimIndent()

        val section = newestReleasedSection(parseChangelogSections(current))!!
        val tagged = taggedSection(atTag, "0.8.15")
        addedSinceRelease(section, tagged) shouldBe emptyList()
    }

    @Test
    fun `2026-09-13 incident - two entries added after v0-8-15 was tagged - both reported`() {
        // Real regression case: b14d47f3/953729c3 landed ~3h after v0.8.15's tag under the
        // already-released "## [0.8.15]" heading, so jj-idea's own bookmarks-panel users were
        // told (via #110/#111) to retest against a build that didn't contain the fix.
        val current =
            """
            ## [0.8.15] - 2026-09-07

            ### Changed
            - Removed the git pseudo-remote from the bookmarks panel entirely.
            - The bookmarks panel remembers which groups you collapsed and restores it next launch.

            ### Fixed
            - Something that really did ship. (#100)
            """.trimIndent()
        val atTag =
            """
            ## [Unreleased]

            ### Fixed
            - Something that really did ship. (#100)
            """.trimIndent()

        val section = newestReleasedSection(parseChangelogSections(current))!!
        val tagged = taggedSection(atTag, "0.8.15")

        addedSinceRelease(section, tagged) shouldBe listOf(
            "Removed the git pseudo-remote from the bookmarks panel entirely.",
            "The bookmarks panel remembers which groups you collapsed and restores it next launch."
        )
        releaseDriftMessage("0.8.15", addedSinceRelease(section, tagged)) shouldNotBe null
    }

    @Test
    fun `tag already carries the rewritten heading - compares against its own version section`() {
        // Covers the workflow_dispatch release path: by the time the tag lands, the
        // [Unreleased] -> ## [version] rewrite commit may already have been included.
        val current =
            """
            ## [0.8.15] - 2026-09-07

            ### Fixed
            - Something that really did ship. (#100)
            """.trimIndent()
        val atTag =
            """
            ## [0.8.15] - 2026-09-07

            ### Fixed
            - Something that really did ship. (#100)

            ## [0.8.14] - 2026-09-01

            ### Fixed
            - Older, unrelated entry.
            """.trimIndent()

        val section = newestReleasedSection(parseChangelogSections(current))!!
        val tagged = taggedSection(atTag, "0.8.15")
        addedSinceRelease(section, tagged) shouldBe emptyList()
    }

    @Test
    fun `an entry removed after release is not reported - only additions are drift`() {
        val current =
            """
            ## [0.8.15] - 2026-09-07

            ### Fixed
            - Kept entry. (#100)
            """.trimIndent()
        val atTag =
            """
            ## [Unreleased]

            ### Fixed
            - Kept entry. (#100)
            - Entry later removed as a duplicate. (#101)
            """.trimIndent()

        val section = newestReleasedSection(parseChangelogSections(current))!!
        val tagged = taggedSection(atTag, "0.8.15")
        addedSinceRelease(section, tagged) shouldBe emptyList()
    }

    @Test
    fun `a reworded entry is reported as drift by default`() {
        val current =
            """
            ## [0.7.0] - 2026-03-01

            ### Added
            - "Squash from Here into..." action in the log context menu. (#50)
            """.trimIndent()
        val atTag =
            """
            ## [Unreleased]

            ### Added
            - "Squash Into..." action in the log context menu. (#50)
            """.trimIndent()

        val section = newestReleasedSection(parseChangelogSections(current))!!
        val tagged = taggedSection(atTag, "0.7.0")
        addedSinceRelease(section, tagged) shouldBe listOf(
            "\"Squash from Here into...\" action in the log context menu. (#50)"
        )
    }

    @Test
    fun `a reworded entry with the opt-out marker is not reported`() {
        val current =
            """
            ## [0.7.0] - 2026-03-01
            <!-- changelog-guard: edited-after-release — action renamed, see #50 -->

            ### Added
            - "Squash from Here into..." action in the log context menu. (#50)
            """.trimIndent()

        exemptionReason(current, "0.7.0") shouldBe "action renamed, see #50"
        exemptionReason(current, "0.8.15") shouldBe null
    }

    @Test
    fun `nested sub-bullets are matched as their own entries`() {
        val current =
            """
            ## [0.8.11] - 2026-08-20

            ### Added
            - Two new settings:
              - Setting A. (#80)
              - Setting B. (#35)
            """.trimIndent()
        val atTag =
            """
            ## [Unreleased]

            ### Added
            - Two new settings:
              - Setting A. (#80)
            """.trimIndent()

        val section = newestReleasedSection(parseChangelogSections(current))!!
        val tagged = taggedSection(atTag, "0.8.11")
        addedSinceRelease(section, tagged) shouldBe listOf("Setting B. (#35)")
    }

    @Test
    fun `a section ends at the trailing link-reference block`() {
        val current =
            """
            ## [0.8.15] - 2026-09-07

            ### Fixed
            - Real entry. (#100)

            [Unreleased]: https://example.invalid/compare/v0.8.15...HEAD
            [0.8.15]: https://example.invalid/releases/tag/v0.8.15
            """.trimIndent()

        val section = newestReleasedSection(parseChangelogSections(current))!!
        section.bullets shouldBe listOf("Real entry. (#100)")
    }

    @Test
    fun `releaseDriftMessage is null when nothing was added`() {
        releaseDriftMessage("0.8.15", emptyList()) shouldBe null
    }
}
