package `in`.kkkev.jjidea.vcs

import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuTaskHandlerTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String, immutable: Boolean = false) = LogEntry(
        repo = repo,
        id = ChangeId(id, id.take(4), null),
        commitId = CommitId("c$id"),
        underlyingDescription = "",
        immutable = immutable
    )

    private fun item(name: String, deleted: Boolean = false, immutable: Boolean = false) =
        BookmarkItem(Bookmark(name, deleted = deleted), ChangeId("x", "x", null), immutable)

    /** Records the commands issued; `new` can be made to fail. */
    private class Recorder(private val failNew: Boolean = false) :
        CommandExecutor by mockk(relaxed = true) {
        val calls = mutableListOf<String>()

        override fun new(
            description: Description,
            parentRevisions: List<Revision>,
            destinationMode: RebaseDestinationMode,
            edit: Boolean
        ): CommandResult {
            calls += "new(${description.actual}; ${parentRevisions.joinToString()})"
            return if (failNew) commandResult(1) else commandResult(0)
        }

        override fun describe(description: Description, revision: Revision): CommandResult {
            calls += "describe(${description.actual})"
            return commandResult(0)
        }

        override fun bookmarkCreate(name: BookmarkName, revision: Revision): CommandResult {
            calls += "bookmarkCreate($name)"
            return commandResult(0)
        }

        override fun rebase(
            revisions: List<Revision>,
            destinations: List<Revision>,
            sourceMode: RebaseSourceMode,
            destinationMode: RebaseDestinationMode
        ): CommandResult {
            calls += "rebase(${sourceMode.flag} ${revisions.joinToString()} -> ${destinations.joinToString()})"
            return commandResult(0)
        }

        override fun bookmarkSet(name: BookmarkName, revision: Revision, allowBackwards: Boolean): CommandResult {
            calls += "bookmarkSet($name, $revision)"
            return commandResult(0)
        }

        override fun edit(revision: Revision): CommandResult {
            calls += "edit($revision)"
            return commandResult(0)
        }
    }

    @Test
    fun `name validation follows revset symbol grammar`() {
        listOf("PROJ-123-fix-thing", "feature/x", "a.b", "a_b+c").forEach { isValidTaskName(it) shouldBe true }
        listOf("", "has space", "a@origin", "-x", "x-", "a..b", "a--b").forEach { isValidTaskName(it) shouldBe false }
    }

    @Test
    fun `cleanup always yields a valid name for non-empty input`() {
        listOf("PROJ-123 fix thing", "  a  b  ", "a..b", "-x-", "weird: name!").forEach {
            isValidTaskName(cleanUpTaskName(it)) shouldBe true
        }
        cleanUpTaskName("PROJ-123 fix thing") shouldBe "PROJ-123-fix-thing"
    }

    @Test
    fun `existing tasks group local bookmarks across repos and flag remote-only ones`() {
        val infos = taskInfosFromBookmarks(
            mapOf(
                "/a" to listOf(item("main"), item("main@origin"), item("feat"), item("gone", deleted = true)),
                "/b" to listOf(item("main"), item("only-remote@origin"), item("feat@git"))
            )
        )
        infos.map { it.name } shouldContainExactly listOf("feat", "main", "only-remote")
        infos.single { it.name == "main" }.repositories shouldContainExactly listOf("/a", "/b")
        infos.single { it.name == "main" }.isRemote shouldBe false
        infos.single { it.name == "only-remote" }.isRemote shouldBe true
    }

    @Test
    fun `current tasks come from the closest bookmarks per repo`() {
        val infos = currentTaskInfos(
            mapOf(
                "/a" to listOf(BookmarkName("t1"), BookmarkName("t2")),
                "/b" to listOf(BookmarkName("t1")),
                "/c" to emptyList()
            )
        )
        infos.map { it.name to it.repositories.toList() } shouldContainExactly
            listOf("t1" to listOf("/a", "/b"), "t2" to listOf("/a"))
    }

    @Test
    fun `switch target revset is the stack head, or the bookmark itself when immutable`() {
        switchTargetRevset("t", immutable = true) shouldBe
            "(bookmarks(exact:\"t\") | remote_bookmarks(exact:\"t\"))"
        val stack = switchTargetRevset("t", immutable = false)
        stack.startsWith("latest(heads(") shouldBe true
        stack.contains("bookmarks(exact:\"t\")") shouldBe true
        switchTargetRevset("a\"b", immutable = true).contains("exact:\"a\\\"b\"") shouldBe true
    }

    @Test
    fun `switching edits a mutable target with a single command`() {
        val executor = Recorder()
        executor.switchToTarget(BookmarkName("t"), entry("m1"))
        executor.calls shouldContainExactly listOf("edit(cm1)")
    }

    @Test
    fun `switching creates a new change on an immutable target`() {
        val executor = Recorder()
        executor.switchToTarget(BookmarkName("t"), entry("i1", immutable = true))
        executor.calls shouldContainExactly listOf("new(; ci1)")
    }

    @Test
    fun `switching falls back to the bookmark when nothing resolved`() {
        val executor = Recorder()
        executor.switchToTarget(BookmarkName("t"), null)
        executor.calls shouldContainExactly listOf("edit(t)")
    }

    @Test
    fun `start on a non-blank working copy makes a new change then the bookmark`() {
        val executor = Recorder()
        executor.startTask(BookmarkName("t"), Description("PROJ-1 do it"), blankWorkingCopy = false)
        executor.calls shouldContainExactly listOf("new(PROJ-1 do it; @)", "bookmarkCreate(t)")
    }

    @Test
    fun `start on a blank working copy only bookmarks it, describing it when a message is given`() {
        val plain = Recorder()
        plain.startTask(BookmarkName("t"), Description.EMPTY, blankWorkingCopy = true)
        plain.calls shouldContainExactly listOf("bookmarkCreate(t)")

        val described = Recorder()
        described.startTask(BookmarkName("t"), Description("msg"), blankWorkingCopy = true)
        described.calls shouldContainExactly listOf("describe(msg)", "bookmarkCreate(t)")
    }

    @Test
    fun `start does not create the bookmark when new fails`() {
        val executor = Recorder(failNew = true)
        val result = executor.startTask(BookmarkName("t"), Description.EMPTY, blankWorkingCopy = false)
        result shouldBe commandResult(1)
        executor.calls.size shouldBe 1
    }

    @Test
    fun `a working copy is blank only when empty and undescribed`() {
        fun wc(empty: Boolean, desc: String) = LogEntry(
            repo = repo,
            id = ChangeId("w", "w", null),
            commitId = CommitId("cw"),
            underlyingDescription = desc,
            isEmpty = empty
        )
        wc(empty = true, desc = "").isBlank shouldBe true
        wc(empty = true, desc = "x").isBlank shouldBe false
        wc(empty = false, desc = "").isBlank shouldBe false
    }

    /** A log service answering by revset: the task head, the original bookmark entry, or the switch target. */
    private fun logService(original: LogEntry?, head: LogEntry?, switchTarget: LogEntry?) =
        object : LogService by mockk(relaxed = true) {
            override fun getLogBasic(revset: Revset, filePaths: List<com.intellij.openapi.vcs.FilePath>, limit: Int?) =
                Result.success(
                    listOfNotNull(
                        when {
                            revset.toString().startsWith("latest(heads(") &&
                                revset.toString().contains("\"task\"") -> head
                            revset.toString() == "bookmarks(exact:\"orig\")" -> original
                            else -> switchTarget
                        }
                    )
                )
        }

    @Test
    fun `rebase close advances the task bookmark, rebases the whole stack, fast-forwards a mutable original`() {
        val executor = Recorder()
        executor.integrateTask(
            logService(original = entry("o1"), head = entry("h1"), switchTarget = entry("t1")),
            "task",
            "orig",
            CloseMode.REBASE
        )
        executor.calls[0] shouldBe "bookmarkSet(task, ch1)"
        executor.calls[1].startsWith("rebase(-s roots(") shouldBe true
        executor.calls[2] shouldBe "bookmarkSet(orig, task)"
        executor.calls[3] shouldBe "edit(ct1)"
        executor.calls.size shouldBe 4
    }

    @Test
    fun `rebase close never moves an immutable original`() {
        val executor = Recorder()
        executor.integrateTask(
            logService(original = entry("o1", immutable = true), head = entry("h1"), switchTarget = entry("o1", true)),
            "task",
            "orig",
            CloseMode.REBASE
        )
        executor.calls.none { it.startsWith("bookmarkSet(orig") } shouldBe true
        executor.calls.last() shouldBe "new(; co1)"
    }

    @Test
    fun `merge close makes a merge change plus a fresh working copy and stays there`() {
        val executor = Recorder()
        executor.integrateTask(
            logService(original = entry("o1"), head = entry("h1"), switchTarget = null),
            "task",
            "orig",
            CloseMode.MERGE
        )
        executor.calls[1].startsWith("new(Merge task; latest(") shouldBe true
        executor.calls[2] shouldBe "new(; @)"
        executor.calls[3] shouldBe "bookmarkSet(orig, @-)"
        executor.calls.size shouldBe 4
    }

    @Test
    fun `switch back close only switches`() {
        val executor = Recorder()
        executor.integrateTask(
            logService(original = entry("o1"), head = entry("h1"), switchTarget = entry("t1")),
            "task",
            "orig",
            CloseMode.SWITCH_BACK
        )
        executor.calls shouldContainExactly listOf("edit(ct1)")
    }
}
