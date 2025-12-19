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
    fun `Revision full returns toString result`() {
        val revision = RevisionExpression("abc123")
        revision.full shouldBe "abc123"
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

}
