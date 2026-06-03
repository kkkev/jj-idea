package `in`.kkkev.jjidea.actions.bookmark

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BookmarkClassifierTest {
    private fun item(
        name: String,
        id: String = name,
        deleted: Boolean = false,
        remote: Boolean = false,
        conflict: Boolean = false
    ): BookmarkItem {
        val bName = if (remote) "$name@origin" else name
        val bookmark = Bookmark(bName, deleted = deleted, conflict = conflict)
        return BookmarkItem(bookmark, if (deleted) null else ChangeId(id, id.take(4), null))
    }

    private fun id(full: String) = ChangeId(full, full.take(4), null)

    @Nested
    inner class `eligible` {
        @Test
        fun `includes local present bookmarks`() {
            val items = listOf(item("main", "aaa"), item("dev", "bbb"))
            val result = BookmarkClassifier.eligible(items, id("zzz"))
            result shouldHaveSize 2
        }

        @Test
        fun `excludes deleted bookmarks`() {
            val items = listOf(item("main", deleted = true), item("dev", "bbb"))
            BookmarkClassifier.eligible(items, id("zzz")) shouldHaveSize 1
        }

        @Test
        fun `excludes remote bookmarks`() {
            val items = listOf(item("main", remote = true), item("dev", "bbb"))
            BookmarkClassifier.eligible(items, id("zzz")) shouldHaveSize 1
        }

        @Test
        fun `excludes bookmark already at target`() {
            val items = listOf(item("main", "targetfull"), item("dev", "bbb"))
            val target = id("targetfull")
            BookmarkClassifier.eligible(items, target) shouldHaveSize 1
        }

        @Test
        fun `includes conflicted but present bookmarks`() {
            val items = listOf(item("main", "aaa", conflict = true))
            BookmarkClassifier.eligible(items, id("zzz")) shouldHaveSize 1
        }
    }

    @Nested
    inner class `ancestorRevset` {
        @Test
        fun `returns null when candidates is empty`() {
            BookmarkClassifier.ancestorRevset(emptyList(), id("target")) shouldBe null
        }

        @Test
        fun `builds correct revset for single candidate`() {
            val items = listOf(item("main", "abc123full"))
            val revset = BookmarkClassifier.ancestorRevset(items, id("targetfull"))
            revset?.value shouldBe "(abc123full) & ::targetfull"
        }

        @Test
        fun `builds correct revset for multiple candidates`() {
            val items = listOf(item("a", "aaa"), item("b", "bbb"))
            val revset = BookmarkClassifier.ancestorRevset(items, id("ttt"))
            revset?.value shouldBe "(aaa | bbb) & ::ttt"
        }
    }

    @Nested
    inner class `classify` {
        @Test
        fun `marks forward items whose id is in forwardIds`() {
            val items = listOf(item("main", "aaa"), item("dev", "bbb"))
            val result = BookmarkClassifier.classify(items, setOf("aaa"))
            result.find { it.item.bookmark.name.name == "main" }!!.direction shouldBe MoveDirection.FORWARD
            result.find { it.item.bookmark.name.name == "dev" }!!.direction shouldBe MoveDirection.BACKWARD_OR_SIDEWAYS
        }

        @Test
        fun `marks conflicted as BACKWARD_OR_SIDEWAYS even if id is in forwardIds`() {
            val items = listOf(item("main", "aaa", conflict = true))
            val result = BookmarkClassifier.classify(items, setOf("aaa"))
            result.single().direction shouldBe MoveDirection.BACKWARD_OR_SIDEWAYS
        }

        @Test
        fun `empty forwardIds makes everything BACKWARD_OR_SIDEWAYS`() {
            val items = listOf(item("a", "aaa"), item("b", "bbb"))
            val result = BookmarkClassifier.classify(items, emptySet())
            result.map { it.direction }.shouldContainExactlyInAnyOrder(
                MoveDirection.BACKWARD_OR_SIDEWAYS,
                MoveDirection.BACKWARD_OR_SIDEWAYS
            )
        }

        @Test
        fun `empty candidates produces empty result`() {
            BookmarkClassifier.classify(emptyList(), setOf("aaa")).shouldBeEmpty()
        }
    }
}
