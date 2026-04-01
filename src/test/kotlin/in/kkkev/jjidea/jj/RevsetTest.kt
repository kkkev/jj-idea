package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RevsetTest {
    @Test
    fun `Bookmark toString returns name`() {
        val bookmark = Bookmark("main")
        bookmark.toString() shouldBe "main"
    }

    @Test
    fun `Bookmark name property is accessible`() {
        val bookmark = Bookmark("feature-branch")
        bookmark.name shouldBe "feature-branch"
    }

    @Test
    fun `Tag toString returns name`() {
        val tag = Tag("v1.0.0")
        tag.toString() shouldBe "v1.0.0"
    }

    @Test
    fun `Tag name property is accessible`() {
        val tag = Tag("release-2.0")
        tag.name shouldBe "release-2.0"
    }

    @Test
    fun `Expression toString returns value`() {
        val expression = Expression("all()")
        expression.toString() shouldBe "all()"
    }

    @Test
    fun `Expression value property is accessible`() {
        val expression = Expression("ancestors(@)")
        expression.value shouldBe "ancestors(@)"
    }

    @Test
    fun `Expression ALL constant has correct value`() {
        Expression.ALL.value shouldBe "all()"
        Expression.ALL.toString() shouldBe "all()"
    }

    @Test
    fun `RevisionExpression toString returns value`() {
        val revision = RevisionExpression("@")
        revision.toString() shouldBe "@"
    }

    @Test
    fun `RevisionExpression value property is accessible`() {
        val revision = RevisionExpression("main@origin")
        revision.value shouldBe "main@origin"
    }

    @Test
    fun `WorkingCopy toString returns @`() {
        WorkingCopy.toString() shouldBe "@"
    }

    @Test
    fun `Revision parent returns expression with minus suffix`() {
        val revision = RevisionExpression("@")
        val parent = revision.parent
        parent.toString() shouldBe "@-"
    }

    @Test
    fun `Bookmark parent returns expression with minus suffix`() {
        val bookmark = Bookmark("main")
        val parent = bookmark.parent
        parent.toString() shouldBe "main-"
    }

    @Test
    fun `WorkingCopy parent returns @-`() {
        val parent = WorkingCopy.parent
        parent.toString() shouldBe "@-"
    }

    @Test
    fun `Revision short returns toString result`() {
        val revision = RevisionExpression("longrevision")
        revision.short shouldBe "longrevision"
    }

    @Test
    fun `Bookmark implements Ref which implements Revision`() {
        val bookmark: Revision = Bookmark("test")
        bookmark.toString() shouldBe "test"
    }

    @Test
    fun `Tag implements Ref which implements Revision`() {
        val tag: Revision = Tag("v1.0")
        tag.toString() shouldBe "v1.0"
    }

    @Test
    fun `WorkingCopy implements Ref which implements Revision`() {
        val ref: Revision = WorkingCopy
        ref.toString() shouldBe "@"
    }

    @Test
    fun `multiple parent calls chain correctly`() {
        val revision = RevisionExpression("@")
        val grandparent = revision.parent.parent
        grandparent.toString() shouldBe "@--"
    }

    @Test
    fun `Bookmark isRemote detects remote bookmarks`() {
        Bookmark("main@origin").isRemote shouldBe true
        Bookmark("feature@github").isRemote shouldBe true
    }

    @Test
    fun `Bookmark isRemote returns false for local bookmarks`() {
        Bookmark("main").isRemote shouldBe false
        Bookmark("feature-branch").isRemote shouldBe false
        Bookmark("release/v2.0").isRemote shouldBe false
    }

    @Test
    fun `Bookmark localName extracts name before @`() {
        Bookmark("main@origin").localName shouldBe "main"
        Bookmark("feature@github").localName shouldBe "feature"
    }

    @Test
    fun `Bookmark localName returns full name for local bookmarks`() {
        Bookmark("main").localName shouldBe "main"
    }

    @Test
    fun `Bookmark remote extracts remote after @`() {
        Bookmark("main@origin").remote shouldBe "origin"
        Bookmark("feature@github").remote shouldBe "github"
    }

    @Test
    fun `Bookmark remote returns empty string for local bookmarks`() {
        Bookmark("main").remote shouldBe ""
    }

    @Test
    fun `Bookmark tracked defaults to true`() {
        Bookmark("main").tracked shouldBe true
    }

    @Test
    fun `Bookmark tracked can be set to false`() {
        Bookmark("main@origin", tracked = false).tracked shouldBe false
    }

    @Test
    fun `grouped collapses local and remote bookmarks by localName`() {
        val bookmarks = listOf(
            Bookmark("main"),
            Bookmark("main@origin"),
            Bookmark("main@github"),
            Bookmark("feature@origin", tracked = false)
        )
        val groups = bookmarks.grouped()
        groups.map { it.localName } shouldBe listOf("main", "feature")

        val mainGroup = groups[0]
        mainGroup.local shouldBe Bookmark("main")
        mainGroup.remotes.map { it.name } shouldBe listOf("main@origin", "main@github")
        mainGroup.tracked shouldBe true

        val featureGroup = groups[1]
        featureGroup.local shouldBe null
        featureGroup.remotes.map { it.name } shouldBe listOf("feature@origin")
        featureGroup.tracked shouldBe false
    }

    @Test
    fun `grouped treats group as tracked if any remote is tracked`() {
        val bookmarks = listOf(
            Bookmark("main@origin", tracked = true),
            Bookmark("main@github", tracked = false)
        )
        val groups = bookmarks.grouped()
        groups.single().tracked shouldBe true
    }
}
