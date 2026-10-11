package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/** The pure detection rules behind [ImmutableWorkingCopyTracker] (jj-idea-bzkf, GitHub #143). */
class ImmutableWorkingCopyTrackerTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(id: String, immutable: Boolean = false, parents: List<String> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = "",
        parentIds = parents.map { ChangeId(it, it) },
        immutable = immutable
    )

    private fun op(snapshot: Boolean, id: String = "op") = OperationEntry(
        id = OperationId(id),
        parents = emptyList(),
        description = if (snapshot) "snapshot working copy" else "new empty commit",
        time = Instant.fromEpochSeconds(1_788_000_000),
        user = "u@h",
        isSnapshot = snapshot,
        isRoot = false,
        args = ""
    )

    private val trunk = entry("trunk", immutable = true)
    private val child = entry("child", parents = listOf("trunk"))

    @Test
    fun `matches the warning jj prints, in both phrasings`() {
        immutableWorkingCopyMoved(
            "Warning: The working-copy commit is immutable; a new commit has been created on top of it.\n"
        ) shouldBe true
        immutableWorkingCopyMoved("The working-copy commit is immutable") shouldBe true
    }

    @Test
    fun `ignores other stderr`() {
        immutableWorkingCopyMoved("") shouldBe false
        immutableWorkingCopyMoved("Warning: Refused to snapshot some files") shouldBe false
    }

    @Test
    fun `immutable previous, its child, and a snapshot op is an auto-created child`() {
        isAutoCreatedChild(trunk, child, listOf(op(snapshot = true))) shouldBe true
    }

    @Test
    fun `a snapshot op counts even when later ops landed after it`() {
        isAutoCreatedChild(trunk, child, listOf(op(snapshot = false), op(snapshot = true))) shouldBe true
    }

    @Test
    fun `a plain jj new on top of an immutable working copy is not`() {
        isAutoCreatedChild(trunk, child, listOf(op(snapshot = false))) shouldBe false
        isAutoCreatedChild(trunk, child, emptyList()) shouldBe false
    }

    @Test
    fun `a mutable previous working copy is not (jj protecting a just-pushed commit)`() {
        isAutoCreatedChild(entry("prev"), entry("next", parents = listOf("prev")), listOf(op(snapshot = true))) shouldBe
            false
    }

    @Test
    fun `an unrelated new working copy is not`() {
        isAutoCreatedChild(trunk, entry("other", parents = listOf("elsewhere")), listOf(op(snapshot = true))) shouldBe
            false
    }

    @Test
    fun `first load, with no previous working copy, is not`() {
        isAutoCreatedChild(null, child, listOf(op(snapshot = true))) shouldBe false
    }

    @Test
    fun `the same working copy again is not`() {
        isAutoCreatedChild(trunk, trunk, listOf(op(snapshot = true))) shouldBe false
    }
}
