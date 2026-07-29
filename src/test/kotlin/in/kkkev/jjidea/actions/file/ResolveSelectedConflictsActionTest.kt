package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.vcs.LocalFilePath
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.FileChange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Regression coverage for a visibility bug found while manually testing GitHub #63: with a
 * non-empty changes-tree selection that contains no conflicted files, [scopeToConflicted] used
 * to fall through to "every conflicted file in the repo" (meant only for the inherited-conflict
 * case, where the selection is genuinely empty). That made "Resolve Conflicts…" wrongly visible
 * for a selected non-conflicted file whenever some unrelated file elsewhere in the repo was
 * conflicted.
 */
class ResolveSelectedConflictsActionTest {
    private fun fileAtVersion(name: String): FileAtVersion {
        val filePath = LocalFilePath("/project/$name", false)
        return FileAtVersion(filePath, ChangeId("abc123abc123", "abc1"))
    }

    private fun change(name: String, isConflicted: Boolean): FileChange =
        FileChange.Modified(fileAtVersion(name), fileAtVersion(name), isConflicted)

    @Test
    fun `empty selection - returns null so the caller falls back to a broader lookup`() {
        scopeToConflicted(emptyList()) shouldBe null
    }

    @Test
    fun `single non-conflicted file selected - returns empty, not null`() {
        val result = scopeToConflicted(listOf(change("clean.txt", isConflicted = false)))

        result shouldNotBe null
        result shouldBe emptyList()
    }

    @Test
    fun `single conflicted file selected - returns just that file`() {
        val conflicted = change("file.txt", isConflicted = true)

        scopeToConflicted(listOf(conflicted)) shouldBe listOf(conflicted)
    }

    @Test
    fun `one conflicted plus one non-conflicted selected - returns only the conflicted one`() {
        val conflicted = change("file.txt", isConflicted = true)
        val clean = change("clean.txt", isConflicted = false)

        scopeToConflicted(listOf(clean, conflicted)) shouldBe listOf(conflicted)
    }

    @Test
    fun `two non-conflicted files selected - returns empty, not null`() {
        val result = scopeToConflicted(
            listOf(change("a.txt", isConflicted = false), change("b.txt", isConflicted = false))
        )

        result shouldNotBe null
        result shouldBe emptyList()
    }

    @Test
    fun `two conflicted files selected - returns both`() {
        val a = change("a.txt", isConflicted = true)
        val b = change("b.txt", isConflicted = true)

        scopeToConflicted(listOf(a, b)) shouldBe listOf(a, b)
    }
}
