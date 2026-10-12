package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.ui.log.LogFilterReference
import `in`.kkkev.jjidea.ui.log.LogFilterState
import `in`.kkkev.jjidea.ui.log.compileLogFilterRevset
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins, against real jj, the revset syntax [compileLogFilterRevset] emits for each log filter
 * (jj-idea-ehwm.1 (d) / ehwm.2): every term must parse and select exactly the commits the
 * client-side predicate it replaces would. Findings are written up in
 * docs/design/jj-idea-ehwm-revset-log-filtering.md.
 */
@Tag("contract")
@RequiresJj
class LogFilterRevsetContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var logService: CliLogService

    /** description -> change id for every commit the fixture created. */
    private val ids = mutableMapOf<String, String>()

    private fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        val repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns CliExecutor(root)
            every { directory } returns root
        }
        logService = CliLogService(repo)

        commit("one", "dir one/f \"q\".txt")
        jj.bookmarkCreate("foo")
        jj.run("tag", "set", "v1", "-r", "@")
        commit("two", "b.txt", email = "other@example.com")
        commit("three", "c.txt")
    }

    private fun commit(message: String, file: String, email: String? = null) {
        jj.createFile(file, message)
        val config = email?.let { arrayOf("--config", "user.email=$it") } ?: emptyArray()
        check(jj.run(*config, "metaedit", "--update-author", "-m", message).isSuccess)
        ids[message] = jj.run("log", "--no-graph", "-r", "@", "-T", "change_id").stdout.trim()
        jj.newChange("")
    }

    private fun matching(state: LogFilterState): List<String> {
        val revset = compileLogFilterRevset(Revset.Default, state)!!
        val matched = logService.getChangeIds(revset).getOrThrow().map { it.full }
        return ids.filterValues { it in matched }.keys.toList()
    }

    @Test
    fun `author exact email selects only that author's commits`() {
        setUp()
        matching(LogFilterState(authorEmails = setOf("other@example.com"))) shouldContainExactlyInAnyOrder listOf("two")
    }

    @Test
    fun `date after a past cutoff selects everything and after a future cutoff selects nothing`() {
        setUp()
        matching(LogFilterState(dateCutoff = Instant.parse("2020-01-01T00:00:00Z"))) shouldContainExactlyInAnyOrder
            listOf("one", "two", "three")
        matching(LogFilterState(dateCutoff = Instant.parse("2999-01-01T00:00:00Z"))) shouldBe emptyList()
    }

    @Test
    fun `paths select commits touching a file or anything under a directory, with awkward characters`() {
        setUp()
        matching(LogFilterState(paths = setOf("b.txt"))) shouldContainExactlyInAnyOrder listOf("two")
        matching(LogFilterState(paths = setOf("dir one"))) shouldContainExactlyInAnyOrder listOf("one")
        matching(LogFilterState(paths = setOf("dir one/f \"q\".txt", "c.txt"))) shouldContainExactlyInAnyOrder
            listOf("one", "three")
    }

    @Test
    fun `bookmark and tag references select the true ancestry`() {
        setUp()
        matching(LogFilterState(reference = LogFilterReference.Bookmark("foo"))) shouldContainExactlyInAnyOrder
            listOf("one")
        matching(LogFilterState(reference = LogFilterReference.Tag("v1"))) shouldContainExactlyInAnyOrder listOf("one")
        matching(LogFilterState(reference = LogFilterReference.WorkingCopy)) shouldContainExactlyInAnyOrder
            listOf("one", "two", "three")
    }

    @Test
    fun `text search and revset mode both parse`() {
        setUp()
        matching(LogFilterState(searchText = "two")) shouldContainExactlyInAnyOrder listOf("two")
        matching(
            LogFilterState(searchText = "description(substring:\"three\")", revsetMode = true)
        ) shouldContainExactlyInAnyOrder
            listOf("three")
    }

    @Test
    fun `combined filters intersect`() {
        setUp()
        matching(
            LogFilterState(
                reference = LogFilterReference.Bookmark("foo"),
                authorEmails = setOf("other@example.com")
            )
        ) shouldBe emptyList()
        matching(LogFilterState(authorEmails = setOf("test@example.com"), paths = setOf("c.txt"))) shouldBe
            listOf("three")
    }
}
