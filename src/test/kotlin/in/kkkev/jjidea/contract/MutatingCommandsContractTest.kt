package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.actions.change.parseRemainingChangeId
import `in`.kkkev.jjidea.jj.cli.CliLogService
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import io.kotest.matchers.string.shouldNotContain as shouldNotContainString

abstract class MutatingCommandsContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjBackend

    abstract fun createBackend(tempDir: Path): JjBackend

    private val fields = CliLogService.LogFields()
    private val basicSpec = listOf(
        fields.changeId,
        fields.commitId,
        fields.description,
        fields.bookmarks,
        fields.parents,
        fields.currentWorkingCopy,
        fields.conflict,
        fields.empty,
        fields.immutable
    ).joinToString(" ++ ") { it.spec }

    @BeforeEach
    fun setUp() {
        jj = createBackend(tempDir)
        jj.init()
    }

    @Test
    fun `describe changes description`() {
        val result = jj.run("describe", "-m", "New description")
        result.isSuccess shouldBe true

        val logResult = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val logFields = logResult.stdout.trim().split("\u0000")
        logFields[2] shouldBe "New description\n"
    }

    @Test
    fun `new creates new working copy`() {
        val beforeLog = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val beforeChangeId = beforeLog.stdout.trim().split("\u0000")[0].split("~")[0]

        val result = jj.run("new")
        result.isSuccess shouldBe true

        val afterLog = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val afterChangeId = afterLog.stdout.trim().split("\u0000")[0].split("~")[0]

        // New working copy should have a different change id
        (afterChangeId != beforeChangeId) shouldBe true
    }

    @Test
    fun `abandon removes revision from log`() {
        jj.describe("Will be abandoned")
        jj.newChange()

        // Get parent's change id
        val parentLog = jj.run("log", "-r", "@-", "--no-graph", "-T", basicSpec)
        val parentChangeId = parentLog.stdout.trim().split("\u0000")[0].split("~")[0]

        val result = jj.run("abandon", "-r", "@-")
        result.isSuccess shouldBe true

        // The abandoned revision should not appear in log
        val allLog = jj.run("log", "-r", "all()", "--no-graph", "-T", basicSpec)
        val allChangeIds = allLog.stdout.trim().split("\u0000")
            .chunked(9)
            .filter { it.size == 9 }
            .map { it[0].split("~")[0] }

        allChangeIds shouldNotContain parentChangeId
    }

    @Test
    fun `edit moves working copy to target revision`() {
        jj.describe("Target revision")
        jj.newChange("Next revision")

        // Get parent change id
        val parentLog = jj.run("log", "-r", "@-", "--no-graph", "-T", basicSpec)
        val parentChangeId = parentLog.stdout.trim().split("\u0000")[0].split("~")[0]

        val result = jj.run("edit", "@-")
        result.isSuccess shouldBe true

        // Working copy should now be at the parent
        val afterLog = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val currentChangeId = afterLog.stdout.trim().split("\u0000")[0].split("~")[0]
        currentChangeId shouldBe parentChangeId
    }

    @Test
    fun `bookmark create adds bookmark`() {
        jj.describe("Bookmarked")

        val result = jj.run("bookmark", "create", "new-bm")
        result.isSuccess shouldBe true

        val logResult = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val logFields = logResult.stdout.trim().split("\u0000")
        logFields[3] shouldContain "new-bm"
    }

    @Test
    fun `bookmark set moves bookmark`() {
        jj.describe("Original target")
        jj.bookmarkCreate("movable-bm")
        jj.newChange("New target")

        val result = jj.run("bookmark", "set", "movable-bm", "-r", "@")
        result.isSuccess shouldBe true

        val logResult = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val logFields = logResult.stdout.trim().split("\u0000")
        logFields[3] shouldContain "movable-bm"
    }

    @Test
    fun `bookmark delete removes bookmark`() {
        jj.describe("Had bookmark")
        jj.bookmarkCreate("doomed-bm")

        val result = jj.run("bookmark", "delete", "doomed-bm")
        result.isSuccess shouldBe true

        val logResult = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val logFields = logResult.stdout.trim().split("\u0000")
        logFields[3] shouldBe ""
    }

    @Test
    fun `bookmark rename changes bookmark name`() {
        jj.describe("Renamed")
        jj.bookmarkCreate("old-name")

        val result = jj.run("bookmark", "rename", "old-name", "new-name")
        result.isSuccess shouldBe true

        val logResult = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val logFields = logResult.stdout.trim().split("\u0000")
        logFields[3] shouldContain "new-name"
    }

    @Test
    fun `split creates two changes from one`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Original commit")

        val result = jj.run("split", "-r", "@", "-m", "First part", "a.txt")
        result.isSuccess shouldBe true

        // First part should have "First part" description
        val parentLog = jj.run("log", "-r", "@-", "--no-graph", "-T", basicSpec)
        val parentFields = parentLog.stdout.trim().split("\u0000")
        parentFields[2] shouldBe "First part\n"

        // Remaining (working copy) should have the original description
        val wcLog = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val wcFields = wcLog.stdout.trim().split("\u0000")
        wcFields[2] shouldBe "Original commit\n"
    }

    @Test
    fun `split stderr contains Remaining changes line with change id`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Original commit")

        val result = jj.run("split", "-r", "@", "-m", "First part", "a.txt")
        result.isSuccess shouldBe true

        // The stderr should contain a parseable "Remaining changes:" line
        val childId = parseRemainingChangeId(result.stderr)
        childId.shouldNotBeNull()

        // The parsed (short) change ID should resolve when used as a revset
        val childLog = jj.run("log", "-r", childId.full, "--no-graph", "-T", basicSpec)
        childLog.isSuccess shouldBe true
        // The resolved change should have the original description
        val childFields = childLog.stdout.trim().split("\u0000")
        childFields[2] shouldBe "Original commit\n"
    }

    @Test
    fun `split with describe chains correctly`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Original commit")

        // Split, then describe the child with a new message
        val splitResult = jj.run("split", "-r", "@", "-m", "Parent desc", "a.txt")
        splitResult.isSuccess shouldBe true

        val childId = parseRemainingChangeId(splitResult.stderr)
        childId.shouldNotBeNull()

        val descResult = jj.run("describe", "-r", childId.full, "-m", "Child desc")
        descResult.isSuccess shouldBe true

        // Verify child has the new description
        val childLog = jj.run("log", "-r", childId.full, "--no-graph", "-T", basicSpec)
        val childFields = childLog.stdout.trim().split("\u0000")
        childFields[2] shouldBe "Child desc\n"

        // Verify parent still has its description
        val parentLog = jj.run("log", "-r", "@-", "--no-graph", "-T", basicSpec)
        val parentFields = parentLog.stdout.trim().split("\u0000")
        parentFields[2] shouldBe "Parent desc\n"
    }

    @Test
    fun `split preserves file content in each part`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Two files")

        jj.split("First part", listOf("a.txt"))

        // First part (parent) should have a.txt changes
        val parentDiff = jj.run("diff", "-r", "@-", "--summary")
        parentDiff.isSuccess shouldBe true
        parentDiff.stdout shouldContain "a.txt"

        // Remaining (child/WC) should have b.txt changes
        val wcDiff = jj.run("diff", "-r", "@", "--summary")
        wcDiff.isSuccess shouldBe true
        wcDiff.stdout shouldContain "b.txt"
    }

    @Test
    fun `split non-working-copy rebases descendants`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Target")
        jj.newChange("Child of target")

        // Get the parent change id
        val parentLog = jj.run("log", "-r", "@-", "--no-graph", "-T", basicSpec)
        val targetChangeId = parentLog.stdout.trim().split("\u0000")[0].split("~")[0]

        val result = jj.run("split", "-r", targetChangeId, "-m", "First part", "a.txt")
        result.isSuccess shouldBe true

        // stderr should mention rebased descendants
        result.stderr shouldContain "Rebased"
        result.stderr shouldContain "descendant"
    }

    // -- `jj split -B` ("Split into New Parent", jj-idea-tkog) --

    @Test
    fun `split -B keeps the original change ID on the remaining side, at the original location`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Original commit")

        val beforeLog = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val originalChangeId = beforeLog.stdout.trim().split(" ")[0].split("~")[0]

        val result = jj.run("split", "-r", "@", "-B", "@", "-m", "New parent (a.txt)", "a.txt")
        result.isSuccess shouldBe true

        // The working copy itself keeps the original change ID unchanged.
        val afterLog = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val afterFields = afterLog.stdout.trim().split(" ")
        afterFields[0].split("~")[0] shouldBe originalChangeId
        // Remaining side keeps the original description (not re-described by -m).
        afterFields[2] shouldBe "Original commit\n"

        // The new parent got a genuinely different change ID and the -m description.
        val newParentLog = jj.run("log", "-r", "@-", "--no-graph", "-T", basicSpec)
        val newParentFields = newParentLog.stdout.trim().split(" ")
        newParentFields[0].split("~")[0] shouldNotBe originalChangeId
        newParentFields[2] shouldBe "New parent (a.txt)\n"
    }

    @Test
    fun `split -B places the new commit as the parent, containing only the selected files`() {
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Original commit")

        jj.run("split", "-r", "@", "-B", "@", "-m", "New parent (a.txt)", "a.txt")

        val newParentDiff = jj.run("diff", "-r", "@-", "--summary")
        newParentDiff.stdout shouldContain "a.txt"
        newParentDiff.stdout shouldNotContainString "b.txt"

        val remainingDiff = jj.run("diff", "-r", "@", "--summary")
        remainingDiff.stdout shouldContain "b.txt"
        remainingDiff.stdout shouldNotContainString "a.txt"
    }

    @Test
    fun `split -B then describing the original change ID (no stderr parsing needed) redescribes the remaining side`() {
        // Mirrors onSplitSuccess's newParent-mode chaining: since the remaining side keeps the
        // source's own change ID, it can be re-described directly by that ID - no need to parse
        // "Remaining changes" from split's stderr, unlike the default (no -B) mode.
        jj.createFile("a.txt", "content a")
        jj.createFile("b.txt", "content b")
        jj.describe("Original commit")

        val beforeLog = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val originalChangeId = beforeLog.stdout.trim().split(" ")[0].split("~")[0]

        jj.run("split", "-r", "@", "-B", "@", "-m", "New parent", "a.txt")
        val describeResult = jj.run("describe", "-r", originalChangeId, "-m", "Edited remaining desc")
        describeResult.isSuccess shouldBe true

        val afterLog = jj.run("log", "-r", "@", "--no-graph", "-T", basicSpec)
        val afterFields = afterLog.stdout.trim().split(" ")
        afterFields[0].split("~")[0] shouldBe originalChangeId
        afterFields[2] shouldBe "Edited remaining desc\n"
    }
}
