package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.CliLogService
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class MutatingCommandsContractTest {
    @TempDir
    lateinit var tempDir: Path
    lateinit var jj: JjCli

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
        jj = JjCli(tempDir)
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
}
