package `in`.kkkev.jjidea.jj.revset

import `in`.kkkev.jjidea.jj.cli.RevsetAlias
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RevsetCompletionTest {
    private fun candidates(
        prefix: String,
        bookmarks: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        aliases: List<RevsetAlias> = emptyList()
    ) = revsetCandidates(prefix, REVSET_FUNCTIONS, bookmarks, tags, aliases)

    @Test
    fun `prefix is the symbol before the caret`() {
        completionPrefix("heads(anc", 9) shouldBe "anc"
        completionPrefix("heads(anc", 6) shouldBe ""
        completionPrefix("a & my-br", 9) shouldBe "my-br"
        completionPrefix("a..b", 4) shouldBe "b"
        completionPrefix("", 0) shouldBe ""
    }

    @Test
    fun `prefix stops at range operators`() {
        completionPrefix("x::an", 5) shouldBe "an"
    }

    @Test
    fun `functions are offered with signature and args flag`() {
        val ancestors = candidates("anc").single { it.text == "ancestors" }
        ancestors.kind shouldBe RevsetCandidateKind.FUNCTION
        ancestors.tail shouldBe "(x, [depth])"
        ancestors.takesArgs shouldBe true
        candidates("mi").single { it.text == "mine" }.takesArgs shouldBe false
    }

    @Test
    fun `bookmarks and tags are labelled by kind`() {
        val result = candidates("rel", bookmarks = listOf("release"), tags = listOf("release-1"))
        result shouldContain RevsetCandidate("release", RevsetCandidateKind.BOOKMARK)
        result shouldContain RevsetCandidate("release-1", RevsetCandidateKind.TAG)
    }

    @Test
    fun `same name as bookmark and tag is kept once per kind`() {
        val result = candidates("v1", bookmarks = listOf("v1", "v1"), tags = listOf("v1"))
        result.count { it.text == "v1" } shouldBe 2
    }

    @Test
    fun `function aliases are offered as functions, symbol aliases as aliases`() {
        val result = candidates(
            "",
            aliases = listOf(RevsetAlias("mywork"), RevsetAlias("recent", true, listOf("n", "who")))
        )
        result shouldContain RevsetCandidate("mywork", RevsetCandidateKind.ALIAS)
        result shouldContain RevsetCandidate("recent", RevsetCandidateKind.FUNCTION, "(n, who)", true)
    }

    @Test
    fun `non-matching candidates are dropped`() {
        candidates("zzz").map { it.text } shouldNotContain "ancestors"
    }

    @Test
    fun `function names are unique`() {
        REVSET_FUNCTIONS.map { it.name }.distinct().size shouldBe REVSET_FUNCTIONS.size
    }
}
