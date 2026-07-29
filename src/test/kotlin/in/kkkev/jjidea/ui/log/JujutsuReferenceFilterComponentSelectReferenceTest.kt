package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RepositoryReferences
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.TagItem
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.util.SimpleNotifiableState
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * Platform tests for [JujutsuReferenceFilterComponent.selectReference] (jj-idea-iesq): the
 * programmatic entry point a clicked bookmark/tag chip drives (via
 * [in.kkkev.jjidea.jj.JujutsuStateModel.filterToReference]) to apply the References filter,
 * mirroring the role [JujutsuAuthorFilterComponent.setSelectedAuthors] plays for author filtering.
 *
 * Platform-tagged because [JujutsuReferenceFilterComponent] reads the project-level
 * [in.kkkev.jjidea.jj.JujutsuStateModel.references] light service (see project memory on IJPGP
 * test infrastructure).
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class JujutsuReferenceFilterComponentSelectReferenceTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>()

    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    private fun entry(
        changeId: String,
        parentId: String? = null,
        bookmarks: List<Bookmark> = emptyList(),
        tags: List<Tag> = emptyList()
    ) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        bookmarks = bookmarks,
        tags = tags,
        parentIdentifiers = parentId?.let {
            listOf(LogEntry.Identifiers(ChangeId(it, it, null), CommitId("1".repeat(40))))
        } ?: emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = false,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null
    )

    /** Builds a fully-initialized component, mirroring CommitTablePanel.createFilterComponents(). */
    private fun component(tableModel: JujutsuLogTableModel, disposable: Disposable) =
        JujutsuReferenceFilterComponent(tableModel, project.get(), disposable).apply {
            initUi()
            initialize()
        }

    @Test
    fun `selectReference narrows the log to a bookmark and its ancestors`() {
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(
            listOf(
                entry("root111"),
                entry("mid222", parentId = "root111"),
                entry("tip333", parentId = "mid222", bookmarks = listOf(Bookmark("main"))),
                entry("other444")
            )
        )
        val disposable = Disposer.newDisposable()
        try {
            component(tableModel, disposable).selectReference("main")

            tableModel.getFilteredEntries().map { it.id.short } shouldContainExactly
                listOf("root111", "mid222", "tip333")
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `selectReference narrows the log to a tag and its ancestors`() {
        // references.value is read-only through the NotifiableState interface (it's normally
        // populated by the background loader) - cast to the concrete type to seed it directly,
        // so the component's type-detection recognizes "v1" as a tag rather than defaulting to
        // ReferenceType.BOOKMARK (see JujutsuReferenceFilterComponent.setInitialReference).
        (project.get().stateModel.references as SimpleNotifiableState).value = mapOf(
            repo to RepositoryReferences(tags = listOf(TagItem(Tag("v1"), id = null)))
        )
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(
            listOf(
                entry("root111"),
                entry("tip222", parentId = "root111", tags = listOf(Tag("v1"))),
                entry("other333")
            )
        )
        val disposable = Disposer.newDisposable()
        try {
            component(tableModel, disposable).selectReference("v1")

            tableModel.getFilteredEntries().map { it.id.short } shouldContainExactly listOf("root111", "tip222")
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `selectReference with an empty name is a no-op`() {
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(listOf(entry("aaa111"), entry("bbb222")))
        val disposable = Disposer.newDisposable()
        try {
            component(tableModel, disposable).selectReference("")

            tableModel.getFilteredEntries().size shouldBe 2
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `selectReference not matching any entry falls back to expansion request without filtering`() {
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(listOf(entry("aaa111"), entry("bbb222")))
        val disposable = Disposer.newDisposable()
        try {
            var expansionRequestedFor: String? = null
            val comp = component(tableModel, disposable)
            comp.onReferenceExpansionNeeded = { expansionRequestedFor = it }

            comp.selectReference("missing-bookmark")

            expansionRequestedFor shouldBe "missing-bookmark"
            tableModel.getFilteredEntries().size shouldBe 2
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `clearReference restores the full log and clears the selected reference name`() {
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(
            listOf(
                entry("root111"),
                entry("tip222", parentId = "root111", bookmarks = listOf(Bookmark("main"))),
                entry("other333")
            )
        )
        val disposable = Disposer.newDisposable()
        try {
            val comp = component(tableModel, disposable)
            comp.selectReference("main")
            tableModel.getFilteredEntries().size shouldBe 2
            // Single source of truth for the "Filter Log to '...'" checkmark (jj-idea-iesq).
            project.get().stateModel.activeReferenceFilter shouldBe "main"

            comp.clearReference()

            comp.getSelectedReferenceName() shouldBe ""
            tableModel.getFilteredEntries().size shouldBe 3
            project.get().stateModel.activeReferenceFilter shouldBe ""
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `clicking the same bookmark's filter action twice toggles it off - mirrors CommitTablePanel's wiring`() {
        // CommitTablePanel.createFilterComponents wires the filterToReference notifier to:
        // if (getSelectedReferenceName() == name) clearReference() else selectReference(name).
        // This test exercises that exact toggle decision against the real component so a future
        // change to selectReference/clearReference/getSelectedReferenceName can't silently break
        // the toggle contract without a test noticing (jj-idea-iesq).
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(
            listOf(
                entry("root111"),
                entry("tip222", parentId = "root111", bookmarks = listOf(Bookmark("main"))),
                entry("other333")
            )
        )
        val disposable = Disposer.newDisposable()
        try {
            val comp = component(tableModel, disposable)
            fun toggle(name: String) {
                if (comp.getSelectedReferenceName() == name) comp.clearReference() else comp.selectReference(name)
            }

            toggle("main")
            tableModel.getFilteredEntries().size shouldBe 2

            toggle("main")
            comp.getSelectedReferenceName() shouldBe ""
            tableModel.getFilteredEntries().size shouldBe 3
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
