package `in`.kkkev.jjidea.changelog

/**
 * jj-idea-j8s6: on 2026-09-13, two bookmarks-panel `CHANGELOG.md` entries sat under the
 * `## [0.8.15]` heading but were actually authored ~3 hours *after* `v0.8.15` was tagged and
 * published — they never shipped in that build. Two GitHub reporters were asked to retest
 * against "0.8.15" for behavior that didn't exist in it, wasting a round trip for both.
 *
 * The release workflow ([.github/workflows/build.yml]) cuts a release's tag from
 * `[Unreleased]` and only afterwards, in a separate follow-up commit, rewrites
 * `[Unreleased]` into `## [X.Y.Z] - <date>`. Nothing stops a later commit from adding more
 * entries under that now-released heading before/after that rewrite lands, which is exactly
 * what happened. This file is the parse/diff logic for a guard against that: it compares the
 * newest released section in the current `CHANGELOG.md` against the same section's content at
 * its release tag, and reports any entry that's present now but wasn't at the tag.
 *
 * Kept dependency-free (no I/O) so it's cheap to unit test; [ChangelogReleaseDriftTest] wires it
 * up to the real repository and its tags.
 */

/** One `## [name]` section of a Keep-a-Changelog-formatted file, with its bullet entries. */
data class ChangelogSection(val name: String, val bullets: List<String>)

private val HEADING = Regex("""^## \[([^]]+)]""")
private val LINK_REFERENCE = Regex("""^\[.+]:.*""")
private val BULLET = Regex("""^\s*-\s+(.*)$""")
private val RELEASED_VERSION = Regex("""^\d+\.\d+\.\d+$""")
private val GUARD_EXEMPTION = Regex("""^<!--\s*changelog-guard:\s*edited-after-release\s*—\s*(.+?)\s*-->$""")

/**
 * Splits a Keep-a-Changelog `CHANGELOG.md` into sections. A section runs from its `## [name]`
 * heading until the next such heading or the start of the trailing link-reference block —
 * mirroring the parsing already duplicated in `extractChangelogNotes()` (build.gradle.kts) and
 * the release-notes awk in `.github/workflows/build.yml`.
 */
fun parseChangelogSections(markdown: String): List<ChangelogSection> {
    val sections = mutableListOf<ChangelogSection>()
    var name: String? = null
    var bullets = mutableListOf<String>()

    fun flush() {
        name?.let { sections.add(ChangelogSection(it, bullets)) }
    }

    for (line in markdown.lineSequence()) {
        val heading = HEADING.find(line)
        when {
            heading != null -> {
                flush()
                name = heading.groupValues[1]
                bullets = mutableListOf()
            }
            name != null && LINK_REFERENCE.matches(line) -> {
                flush()
                name = null
            }
            name != null -> BULLET.find(line)?.let { bullets.add(it.groupValues[1].trim()) }
        }
    }
    flush()
    return sections
}

/** The first section whose heading is a released version number (`X.Y.Z`), i.e. not `Unreleased`. */
fun newestReleasedSection(sections: List<ChangelogSection>): ChangelogSection? =
    sections.firstOrNull { RELEASED_VERSION.matches(it.name) }

/**
 * The section `CHANGELOG.md` held for [version] at the moment its tag was cut. A release's tag
 * predates the follow-up commit that renames `[Unreleased]` to `## [version] - <date>` (see
 * class doc), so the content to compare against is whichever of the two headings is present at
 * the tag — `[version]` if the rewrite had already landed by tag time, `[Unreleased]` otherwise.
 */
fun taggedSection(taggedMarkdown: String, version: String): ChangelogSection? {
    val sections = parseChangelogSections(taggedMarkdown)
    return sections.firstOrNull { it.name == version } ?: sections.firstOrNull { it.name == "Unreleased" }
}

/** Entries in [current] that weren't present in [tagged] — i.e. added after the release shipped. */
fun addedSinceRelease(current: ChangelogSection, tagged: ChangelogSection?): List<String> {
    val taggedBullets = tagged?.bullets.orEmpty().toSet()
    return current.bullets.filterNot { it in taggedBullets }
}

/**
 * A `<!-- changelog-guard: edited-after-release — <reason> -->` marker directly under
 * [version]'s heading in [markdown], if present — the documented opt-out for a genuine
 * post-release correction (e.g. rewording an entry after the feature it describes was renamed).
 * Returns the reason text, or null if no such marker is present for that version.
 */
fun exemptionReason(markdown: String, version: String): String? {
    val lines = markdown.lines()
    val headingIndex = lines.indexOfFirst { HEADING.find(it)?.groupValues?.get(1) == version }
    if (headingIndex < 0) return null
    for (i in (headingIndex + 1) until lines.size) {
        val line = lines[i]
        if (line.isBlank()) continue
        return GUARD_EXEMPTION.find(line)?.groupValues?.get(1)
    }
    return null
}

/**
 * Human-readable failure message for [added] entries found under [version]'s heading that
 * weren't present when `v$version` was tagged, or null if [added] is empty.
 */
fun releaseDriftMessage(version: String, added: List<String>): String? {
    if (added.isEmpty()) return null
    val entries = added.joinToString("\n") { "  - " + it.take(160) + if (it.length > 160) "…" else "" }
    val isPlural = added.size != 1
    val entryWord = if (isPlural) "entries" else "entry"
    val wasWord = if (isPlural) "weren't" else "wasn't"
    val theyWord = if (isPlural) "they were" else "it was"
    return """
        CHANGELOG.md's "## [$version]" section contains ${added.size} $entryWord that $wasWord present
        when v$version was tagged — i.e. $theyWord never actually released in $version, even though
        the CHANGELOG says so:
        $entries

        If this describes work that hasn't shipped yet, move it to the "[Unreleased]" section instead.
        If it's a correction to an entry that genuinely did ship in $version (e.g. a rewording), add a
        marker line directly under "## [$version]":
          <!-- changelog-guard: edited-after-release — <reason> -->
        """.trimIndent()
}
