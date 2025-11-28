package `in`.kkkev.jjidea.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Simple tests for tree model node classes that don't require mocking
 */
class JujutsuChangesTreeModelSimpleTest {

    @Test
    fun `directory node with multiple files uses plural`() {
        val node = JujutsuChangesTreeModel.DirectoryNode("src/main", 5)

        val text = node.toString()

        text shouldContain "5"
        text shouldContain "files"
    }

    @Test
    fun `directory node with one file uses singular`() {
        val node = JujutsuChangesTreeModel.DirectoryNode("src/test", 1)

        val text = node.toString()

        text shouldContain "1"
        text shouldContain "file"
    }

    @Test
    fun `directory node shows directory name`() {
        val node = JujutsuChangesTreeModel.DirectoryNode("src/main/kotlin", 3)

        val text = node.toString()

        text shouldContain "kotlin"
    }

    @Test
    fun `empty directory path shows dot`() {
        val node = JujutsuChangesTreeModel.DirectoryNode("", 1)

        val text = node.toString()

        text shouldContain "."
    }
}
