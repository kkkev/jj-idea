package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests that [fileChangeActionGroup] assembles the correct set of actions in the right order.
 *
 * This group is used as the context menu for the changes tree in both the commit details
 * panel and the working copy panel.
 */
@Tag("platform")
@TestApplication
class FileChangeActionGroupPlatformTest {
    @Test
    fun `all expected action IDs are registered in ActionManager`() {
        val actionManager = ActionManager.getInstance()
        val expected = listOf(
            "Jujutsu.ShowChangesDiff",
            "Jujutsu.OpenChangeFile",
            "Jujutsu.CompareWithLocal",
            "Jujutsu.CompareBeforeWithLocal",
            "Jujutsu.CompareWithBranch",
            "Jujutsu.OpenFileInRemote",
            "Jujutsu.RestoreFile",
            "Jujutsu.RestoreToChange",
            "Jujutsu.SquashFiles",
            "Jujutsu.SplitFiles"
        )
        val missing = expected.filter { actionManager.getAction(it) == null }
        missing shouldBe emptyList()
    }

    @Test
    fun `group contains all expected registered actions`() {
        val group = fileChangeActionGroup()
        val actionIds = group.childActionIds()
        actionIds shouldContainAll listOf(
            "Jujutsu.ShowChangesDiff",
            "Jujutsu.OpenChangeFile",
            "Jujutsu.CompareWithLocal",
            "Jujutsu.CompareBeforeWithLocal",
            "Jujutsu.CompareWithBranch",
            "Jujutsu.OpenFileInRemote",
            "Jujutsu.RestoreFile",
            "Jujutsu.RestoreToChange",
            "Jujutsu.SquashFiles",
            "Jujutsu.SplitFiles"
        )
    }

    @Test
    fun `ShowDiff and OpenFile appear before the first separator`() {
        val group = fileChangeActionGroup()
        val children = group.getChildren(null).toList()
        val firstBlock = children.takeWhile { it !is Separator }
        firstBlock.actionIds() shouldContainAll listOf("Jujutsu.ShowChangesDiff", "Jujutsu.OpenChangeFile")
    }

    @Test
    fun `Compare and Remote actions appear in the block after the first separator`() {
        val group = fileChangeActionGroup()
        val children = group.getChildren(null).toList()
        val blocks = splitBySeparators(children)
        (blocks.size >= 4) shouldBe true
        blocks[1].actionIds() shouldContainAll listOf(
            "Jujutsu.CompareWithLocal",
            "Jujutsu.CompareBeforeWithLocal",
            "Jujutsu.CompareWithBranch",
            "Jujutsu.OpenFileInRemote"
        )
    }

    @Test
    fun `Restore actions appear in the block after the second separator`() {
        val group = fileChangeActionGroup()
        val children = group.getChildren(null).toList()
        val blocks = splitBySeparators(children)
        blocks[2].actionIds() shouldContainAll listOf("Jujutsu.RestoreFile", "Jujutsu.RestoreToChange")
    }

    @Test
    fun `SquashFiles and SplitFiles appear in the last block`() {
        val group = fileChangeActionGroup()
        val children = group.getChildren(null).toList()
        val blocks = splitBySeparators(children)
        blocks.last().actionIds() shouldContainAll listOf("Jujutsu.SquashFiles", "Jujutsu.SplitFiles")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the action IDs of direct children (excluding separators). */
    private fun DefaultActionGroup.childActionIds(): List<String> =
        getChildren(null).mapNotNull { ActionManager.getInstance().getId(it) }

    /** Returns the action IDs of a list of actions (excluding separators). */
    private fun List<AnAction>.actionIds(): List<String> =
        mapNotNull { ActionManager.getInstance().getId(it) }

    /** Splits a flat list of actions by [Separator] items, returning the non-separator blocks. */
    private fun splitBySeparators(children: List<AnAction>): List<List<AnAction>> {
        val blocks = mutableListOf<List<AnAction>>()
        var current = mutableListOf<AnAction>()
        for (child in children) {
            if (child is Separator) {
                if (current.isNotEmpty()) {
                    blocks.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(child)
            }
        }
        if (current.isNotEmpty()) blocks.add(current)
        return blocks
    }
}
