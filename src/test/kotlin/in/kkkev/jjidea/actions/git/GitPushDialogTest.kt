package `in`.kkkev.jjidea.actions.git

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import `in`.kkkev.jjidea.jj.Revision
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private class FakeExecutor(private val onBookmarkList: (Remote?, Revision?) -> String) :
    CommandExecutor by mockk(relaxed = true) {
    val bookmarkListCalls = mutableListOf<Pair<Remote?, Revision?>>()

    override fun gitRemoteList() =
        CommandExecutor.CommandResult(0, "origin https://example.com/repo.git\n", "")

    override fun bookmarkList(template: String?, remote: Remote?, tracked: Boolean, revision: Revision?):
        CommandExecutor.CommandResult {
        bookmarkListCalls += remote to revision
        return CommandExecutor.CommandResult(0, onBookmarkList(remote, revision), "")
    }
}

class GitPushDialogTest {
    // Null byte: the field separator emitted by the JJ template
    private val nul = Char(0).toString()

    private fun entry(name: String, present: Boolean) = name + nul + (if (present) "true" else "false") + nul

    @Nested
    inner class `parseBookmarks` {
        @Test
        fun `returns empty list for empty stdout`() =
            GitPushDialog.parseBookmarks("", tracked = true) shouldBe emptyList()

        @Test
        fun `parses single present bookmark`() =
            GitPushDialog.parseBookmarks(entry("main", present = true), tracked = true) shouldBe
                listOf(Bookmark("main", tracked = true, deleted = false))

        @Test
        fun `parses single deleted bookmark`() =
            GitPushDialog.parseBookmarks(entry("main", present = false), tracked = true) shouldBe
                listOf(Bookmark("main", tracked = true, deleted = true))

        @Test
        fun `parses multiple bookmarks including deletions`() =
            GitPushDialog.parseBookmarks(
                entry("main", present = true) + entry("feature", present = true) + entry("old", present = false),
                tracked = true
            ) shouldBe listOf(
                Bookmark("main", tracked = true, deleted = false),
                Bookmark("feature", tracked = true, deleted = false),
                Bookmark("old", tracked = true, deleted = true)
            )

        @Test
        fun `propagates tracked=false to all results`() =
            GitPushDialog.parseBookmarks(entry("main", present = true), tracked = false) shouldBe
                listOf(Bookmark("main", tracked = false, deleted = false))

        @Test
        fun `ignores incomplete trailing pair`() =
            GitPushDialog.parseBookmarks(
                entry("main", present = true) + "partial" + nul,
                tracked = true
            ) shouldBe listOf(Bookmark("main", tracked = true, deleted = false))

        @Test
        fun `remote-tracking entries produce empty tokens that are filtered out`() {
            // Remote tracking entries emit "" in the template; these appear as extra NULs
            // in the concatenated output and must be silently discarded
            val stdout = nul + entry("main", present = true) + nul
            val result = GitPushDialog.parseBookmarks(stdout, tracked = true)
            result shouldBe listOf(Bookmark("main", tracked = true, deleted = false))
        }
    }

    @Nested
    inner class `mergeBookmarks` {
        @Test
        fun `keeps tracked entry over a same-named local one`() {
            val tracked = listOf(Bookmark("main", tracked = true))
            val allLocal = listOf(Bookmark("main", tracked = false))
            GitPushDialog.mergeBookmarks(tracked, allLocal) shouldBe listOf(Bookmark("main", tracked = true))
        }

        @Test
        fun `does not duplicate a bookmark present in both lists`() {
            val tracked = listOf(Bookmark("main", tracked = true), Bookmark("feature", tracked = true))
            val allLocal = listOf(Bookmark("main", tracked = false), Bookmark("feature", tracked = false))
            GitPushDialog.mergeBookmarks(tracked, allLocal).map { it.localName } shouldBe listOf("main", "feature")
        }

        @Test
        fun `keeps untracked-only entries`() {
            val tracked = listOf(Bookmark("main", tracked = true))
            val allLocal = listOf(Bookmark("main", tracked = false), Bookmark("new-thing", tracked = false))
            GitPushDialog.mergeBookmarks(tracked, allLocal) shouldBe listOf(
                Bookmark("main", tracked = true),
                Bookmark("new-thing", tracked = false)
            )
        }

        @Test
        fun `orders all tracked entries before any untracked-only entry`() {
            val tracked = listOf(Bookmark("b-tracked", tracked = true), Bookmark("a-tracked", tracked = true))
            val allLocal = listOf(Bookmark("z-local", tracked = false), Bookmark("a-tracked", tracked = false))
            GitPushDialog.mergeBookmarks(tracked, allLocal).map { it.localName } shouldBe
                listOf("b-tracked", "a-tracked", "z-local")
        }

        @Test
        fun `returns empty list for two empty inputs`() =
            GitPushDialog.mergeBookmarks(emptyList(), emptyList()) shouldBe emptyList()
    }

    @Nested
    inner class `pickInitialRemote` {
        private val origin = Remote("origin")
        private val github = Remote("github")

        @Test
        fun `picks the remote that already tracks the bookmark`() {
            val data = GitPushDialog.DialogData(
                remotes = listOf(origin, github),
                trackedByRemote = mapOf(
                    origin to emptyList(),
                    github to listOf(Bookmark("main", tracked = true))
                ),
                allLocal = emptyList()
            )
            GitPushDialog.pickInitialRemote(data, Bookmark("main")) shouldBe github
        }

        @Test
        fun `falls back to the first remote for a bookmark tracked nowhere`() {
            val data = GitPushDialog.DialogData(
                remotes = listOf(origin, github),
                trackedByRemote = mapOf(origin to emptyList(), github to emptyList()),
                allLocal = listOf(Bookmark("new-thing", tracked = false))
            )
            GitPushDialog.pickInitialRemote(data, Bookmark("new-thing", tracked = false)) shouldBe origin
        }

        @Test
        fun `returns null when there are no remotes at all`() {
            val data = GitPushDialog.DialogData(
                remotes = emptyList(),
                trackedByRemote = emptyMap(),
                allLocal = emptyList()
            )
            GitPushDialog.pickInitialRemote(data, Bookmark("main")) shouldBe null
        }

        @Test
        fun `prefers the first remote in list order when tracked by more than one`() {
            val data = GitPushDialog.DialogData(
                remotes = listOf(origin, github),
                trackedByRemote = mapOf(
                    origin to listOf(Bookmark("main", tracked = true)),
                    github to listOf(Bookmark("main", tracked = true))
                ),
                allLocal = emptyList()
            )
            GitPushDialog.pickInitialRemote(data, Bookmark("main")) shouldBe origin
        }
    }

    @Nested
    inner class `parsePushScope` {
        @Test
        fun `round-trips every scope by name`() {
            GitPushDialog.PushScope.entries.forEach { scope ->
                GitPushDialog.parsePushScope(scope.name) shouldBe scope
            }
        }

        @Test
        fun `falls back to DEFAULT for an unrecognised value`() {
            GitPushDialog.parsePushScope("NOT_A_REAL_SCOPE") shouldBe GitPushDialog.PushScope.DEFAULT
        }

        @Test
        fun `falls back to DEFAULT for an empty value`() {
            GitPushDialog.parsePushScope("") shouldBe GitPushDialog.PushScope.DEFAULT
        }
    }

    @Nested
    inner class `loadDialogData` {
        private fun repoWith(executor: CommandExecutor): JujutsuRepository {
            val repo = mockk<JujutsuRepository>()
            io.mockk.every { repo.commandExecutor } returns executor
            return repo
        }

        @Test
        fun `includes a deleted bookmark dropped by revision scoping`() {
            val revision = ChangeId("target", "targ")
            val executor = FakeExecutor { remote, queryRevision ->
                when {
                    remote != null && queryRevision == revision -> entry("main", present = true)
                    remote != null && queryRevision == null ->
                        entry("main", present = true) + entry("old", present = false)

                    else -> ""
                }
            }

            val data = GitPushDialog.loadDialogData(repoWith(executor), revision)

            data.trackedByRemote.values.single().map { it.name.name to it.deleted } shouldBe
                listOf("main" to false, "old" to true)
        }

        @Test
        fun `does not add a non-deleted bookmark absent from the scoped query`() {
            val revision = ChangeId("target", "targ")
            val executor = FakeExecutor { remote, queryRevision ->
                when {
                    remote != null && queryRevision == revision -> entry("main", present = true)
                    remote != null && queryRevision == null ->
                        entry("main", present = true) + entry("unrelated", present = true)

                    else -> ""
                }
            }

            val data = GitPushDialog.loadDialogData(repoWith(executor), revision)

            data.trackedByRemote.values.single().map { it.name.name } shouldBe listOf("main")
        }

        @Test
        fun `queries bookmarkList once per remote when no revision is given`() {
            val executor = FakeExecutor { _, _ -> entry("main", present = true) }

            GitPushDialog.loadDialogData(repoWith(executor), revision = null)

            // One call for the per-remote tracked list, one for the unscoped allLocal list.
            executor.bookmarkListCalls.count { it.first != null } shouldBe 1
        }
    }
}
