package `in`.kkkev.jjidea.changelog

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * jj-idea-j8s6: runs [ChangelogReleaseGuard] against this repository's real `CHANGELOG.md` and
 * the tag for its newest released version, so a recurrence of the 2026-09-13 incident (an entry
 * added under an already-released heading) fails `./gradlew check` instead of shipping unnoticed.
 *
 * Resolves the tagged content via `jj file show` first, falling back to `git show` — lane
 * workspaces have jj but no `.git`, CI runners have git but not necessarily jj (the same shell-out
 * pattern as `in.kkkev.jjidea.contract.JjCli`, which the contract tests use for real `jj`
 * output). Only skips when *neither* VCS can see the repository at all — a repository that's
 * reachable but missing the expected tag fails outright (e.g. CI checked out with `fetch-depth: 1`
 * and no tags), rather than silently passing.
 */
class ChangelogReleaseDriftTest {
    @Test
    fun `the newest released CHANGELOG section matches what was actually released at its tag`() {
        val repoRoot = findRepoRoot()
        val changelog = File(repoRoot, "CHANGELOG.md")
        assumeTrue(changelog.exists(), "CHANGELOG.md not found - skipping (not a full checkout?)")

        val current = changelog.readText()
        val section = newestReleasedSection(parseChangelogSections(current))
        assumeTrue(section != null, "No released section in CHANGELOG.md - nothing to check")
        section!!

        val tag = "v${section.name}"
        val taggedContent = readFileAtRevision(repoRoot, tag, "CHANGELOG.md")
        assumeTrue(
            taggedContent != VcsUnavailable,
            "Neither jj nor git is available to resolve $tag - skipping"
        )
        check(taggedContent != TagNotFound) {
            "CHANGELOG.md's newest released section is \"## [${section.name}]\", but tag $tag " +
                "wasn't found. Fetch tags (e.g. `actions/checkout` with fetch-depth: 0) before " +
                "running this test."
        }

        if (exemptionReason(current, section.name) != null) return

        val tagged = taggedSection(taggedContent as String, section.name)
        val added = addedSinceRelease(section, tagged)
        val message = releaseDriftMessage(section.name, added)
        check(message == null) { message!! }
    }
}

private object VcsUnavailable
private object TagNotFound

/** Walks up from the working directory to find the checkout root (the directory with CHANGELOG.md). */
private fun findRepoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null) {
        if (File(dir, "CHANGELOG.md").exists()) return dir
        dir = dir.parentFile
    }
    return File(System.getProperty("user.dir"))
}

/**
 * Reads [path] as it was at [revision], trying `jj file show` then `git show`. Returns
 * [VcsUnavailable] if neither tool could run at all, or [TagNotFound] if a tool ran but the
 * revision doesn't exist (e.g. tags weren't fetched).
 */
private fun readFileAtRevision(repoRoot: File, revision: String, path: String): Any {
    runCommand(repoRoot, listOf("jj", "file", "show", "-r", revision, path))?.let { return it }
    runCommand(repoRoot, listOf("git", "show", "$revision:$path"))?.let { return it }

    val jjRan = commandExists(repoRoot, listOf("jj", "--version"))
    val gitRan = commandExists(repoRoot, listOf("git", "--version"))
    return if (jjRan || gitRan) TagNotFound else VcsUnavailable
}

private fun runCommand(repoRoot: File, command: List<String>): String? =
    try {
        val process = ProcessBuilder(command).directory(repoRoot).redirectErrorStream(false).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode == 0) stdout else null
    } catch (_: Exception) {
        null
    }

private fun commandExists(repoRoot: File, command: List<String>): Boolean =
    try {
        val process = ProcessBuilder(command).directory(repoRoot).start()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }
