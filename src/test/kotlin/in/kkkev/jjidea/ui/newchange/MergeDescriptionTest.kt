package `in`.kkkev.jjidea.ui.newchange

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class MergeDescriptionTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val default = DEFAULT_MERGE_DESCRIPTION_TEMPLATE

    private fun entry(
        id: String,
        vararg bookmarks: String,
        committed: Long? = null,
        authored: Long? = null
    ) = LogEntry(
        repo = repo,
        id = ChangeId(id, id),
        commitId = CommitId(id),
        underlyingDescription = "",
        bookmarks = bookmarks.map { Bookmark(it) },
        committerTimestamp = committed?.let { Instant.fromEpochSeconds(it) },
        authorTimestamp = authored?.let { Instant.fromEpochSeconds(it) }
    )

    @Test
    fun `null unless exactly two targets`() {
        mergeDescriptionSuggestion(emptyList(), default) shouldBe null
        mergeDescriptionSuggestion(listOf(entry("a", "x")), default) shouldBe null
        mergeDescriptionSuggestion(
            listOf(entry("a", "x"), entry("b", "y"), entry("c", "z")),
            default
        ) shouldBe null
    }

    @Test
    fun `null when a target has no bookmark`() {
        mergeDescriptionSuggestion(listOf(entry("a", "x"), entry("b")), default) shouldBe null
    }

    @Test
    fun `older tip becomes destination`() {
        val s = mergeDescriptionSuggestion(
            listOf(entry("a", "feat", committed = 200), entry("b", "main", committed = 100)),
            default
        )!!
        s.primary shouldBe "Merge branch 'feat' into main"
        s.alternatives shouldBe listOf(
            "Merge branch 'main' into feat",
            "Merge branches 'feat' and 'main'"
        )
    }

    @Test
    fun `ties and missing timestamps keep selection order`() {
        mergeDescriptionSuggestion(
            listOf(entry("a", "one", committed = 5), entry("b", "two", committed = 5)),
            default
        )!!.primary shouldBe "Merge branch 'two' into one"
        mergeDescriptionSuggestion(
            listOf(entry("a", "one"), entry("b", "two", committed = 5)),
            default
        )!!.primary shouldBe "Merge branch 'two' into one"
    }

    @Test
    fun `author timestamp is the fallback`() {
        mergeDescriptionSuggestion(
            listOf(entry("a", "new", authored = 200), entry("b", "old", authored = 100)),
            default
        )!!.primary shouldBe "Merge branch 'new' into old"
    }

    @Test
    fun `remote-only bookmarks use their local name`() {
        mergeDescriptionSuggestion(
            listOf(entry("a", "main@origin"), entry("b", "dev@origin")),
            default
        )!!.primary shouldBe "Merge branch 'dev' into main"
    }

    @Test
    fun `local and its remote on one target count as one name`() {
        mergeDescriptionSuggestion(
            listOf(entry("a", "main", "main@origin"), entry("b", "dev")),
            default
        )!!.primary shouldBe "Merge branch 'dev' into main"
    }

    @Test
    fun `null when a target has several distinct bookmarks`() {
        mergeDescriptionSuggestion(listOf(entry("a", "main", "release"), entry("b", "dev")), default) shouldBe null
    }

    @Test
    fun `null when both targets share the bookmark name`() {
        mergeDescriptionSuggestion(listOf(entry("a", "main"), entry("b", "main@origin")), default) shouldBe null
    }

    @Test
    fun `custom template is used for both directions`() {
        val s = mergeDescriptionSuggestion(
            listOf(entry("a", "one"), entry("b", "two")),
            "Merge {source} -> {destination}"
        )!!
        s.primary shouldBe "Merge two -> one"
        s.alternatives.first() shouldBe "Merge one -> two"
    }

    @Test
    fun `invalid template falls back to default`() {
        mergeDescriptionSuggestion(listOf(entry("a", "one"), entry("b", "two")), "nope")!!
            .primary shouldBe "Merge branch 'two' into one"
    }

    @Test
    fun `template validity requires both placeholders`() {
        isValidMergeTemplate("{source} {destination}") shouldBe true
        isValidMergeTemplate("{source}") shouldBe false
        isValidMergeTemplate("{destination}") shouldBe false
        isValidMergeTemplate("") shouldBe false
    }
}
