package `in`.kkkev.jjidea.actions.git

import `in`.kkkev.jjidea.actions.git.PushAction.Kind
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * jj-idea-spwt: golden captures of `jj git push --dry-run` from real jj 0.37.0, 0.39.0, 0.44.0 and
 * 0.45.0 (src/test/resources/jj-push-dryrun/). jj switched from "Move sideways bookmark X from A to B"
 * to "bookmark: X [move sideways from A to B]"; both must keep driving the safety confirmations.
 */
class PushDryRunTest {
    private fun sections(version: String): Map<String, String> {
        val text = javaClass.getResourceAsStream("/jj-push-dryrun/$version.txt")!!.reader().readText()
        return text.split(Regex("(?m)^## ")).filter { it.isNotBlank() }
            .associate { it.substringBefore('\n').trim() to it.substringAfter('\n') }
    }

    @Test
    fun `legacy format (0_37, 0_39) sideways, delete and add`() {
        for (v in listOf("0.37.0", "0.39.0")) {
            val s = sections(v)
            parsePushPlan(s.getValue("sideways")) shouldContainExactly listOf(PushAction(Kind.MOVE_SIDEWAYS, "main"))
            parsePushPlan(s.getValue("delete")) shouldContainExactly listOf(PushAction(Kind.DELETE, "main"))
            parsePushPlan(s.getValue("add")) shouldContainExactly listOf(PushAction(Kind.ADD, "main"))
            parseForcePushBookmarks(s.getValue("sideways")) shouldBe listOf("main")
            parseDeletedBookmarks(s.getValue("delete")) shouldBe listOf("main")
        }
    }

    @Test
    fun `bracketed format (0_44, 0_45) all kinds`() {
        for (v in listOf("0.44.0", "0.45.0")) {
            val s = sections(v)
            parsePushPlan(s.getValue("sideways")) shouldContainExactly listOf(PushAction(Kind.MOVE_SIDEWAYS, "main"))
            parsePushPlan(s.getValue("backward")) shouldContainExactly listOf(PushAction(Kind.MOVE_BACKWARD, "main"))
            parsePushPlan(s.getValue("forward")) shouldContainExactly listOf(PushAction(Kind.MOVE_FORWARD, "main"))
            parsePushPlan(s.getValue("delete")) shouldContainExactly listOf(PushAction(Kind.DELETE, "main"))
            parsePushPlan(s.getValue("add")) shouldContainExactly listOf(PushAction(Kind.ADD, "main"))
            parsePushPlan(s.getValue("tag")) shouldContainExactly listOf(PushAction(Kind.ADD, "v1", isTag = true))
            parseForcePushBookmarks(s.getValue("sideways") + s.getValue("backward")) shouldBe listOf("main", "main")
            parseForcePushBookmarks(s.getValue("forward")) shouldBe emptyList()
            parseDeletedBookmarks(s.getValue("delete")) shouldBe listOf("main")
        }
    }

    @Test
    fun `several actions in one plan keep their order`() {
        val text =
            """
            Changes to push to origin:
              bookmark: a [move forward from 111 to 222]
              bookmark: b [delete from 333]
              tag: v2 [move sideways from 444 to 555]
            """.trimIndent()
        parsePushPlan(text) shouldContainExactly listOf(
            PushAction(Kind.MOVE_FORWARD, "a"),
            PushAction(Kind.DELETE, "b"),
            PushAction(Kind.MOVE_SIDEWAYS, "v2", isTag = true)
        )
    }

    @Test
    fun `unrecognised plan yields nothing and unrelated text is ignored`() {
        parsePushPlan("") shouldBe emptyList()
        parsePushPlan("Nothing changed.") shouldBe emptyList()
        parsePushPlan("Changes to push to origin:\n  something entirely new: x\n") shouldBe emptyList()
    }
}
