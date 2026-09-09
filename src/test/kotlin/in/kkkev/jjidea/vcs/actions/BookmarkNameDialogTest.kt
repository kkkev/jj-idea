package `in`.kkkev.jjidea.vcs.actions

import `in`.kkkev.jjidea.jj.BookmarkName
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for BookmarkNameDialog validation and error mapping logic.
 *
 * These tests verify the business logic without requiring the IntelliJ Application,
 * by testing the data structures and mapping logic that the dialog depends on.
 */
class BookmarkNameDialogTest {
    @Nested
    inner class `Error code mapping` {
        /**
         * Mirrors [in.kkkev.jjidea.actions.bookmark.BookmarkNameDialog.doOKAction]'s current
         * classification (jj-idea-27b4): exit 2 is clap's own parse/usage error; exit 1 is
         * "already exists" only when jj's stderr actually says so (verified wording: "Bookmark
         * already exists: &lt;name&gt;"), never from the exit code alone - a stale workspace, an
         * invalid revision, or any other exit-1 failure must not be misreported as a name
         * conflict.
         */
        private fun classify(exitCode: Int, stderr: String) = when {
            exitCode == 2 -> "dialog.bookmark.create.error.incorrect.format"
            stderr.contains("already exists", ignoreCase = true) -> "dialog.bookmark.create.error.already.exists"
            else -> "dialog.bookmark.create.error.unknown"
        }

        @Test
        fun `exit code 1 with 'already exists' in stderr maps to already exists error`() {
            val failedNames = mutableMapOf<BookmarkName, String>()
            val bookmark = BookmarkName("main")

            failedNames[bookmark] = classify(1, "Error: Bookmark already exists: main")

            failedNames[bookmark] shouldBe "dialog.bookmark.create.error.already.exists"
        }

        @Test
        fun `exit code 1 with unrelated stderr does not map to already exists error`() {
            val failedNames = mutableMapOf<BookmarkName, String>()
            val bookmark = BookmarkName("main")

            // Verified empirically: `jj bookmark create x -r nonexistent_rev` also exits 1.
            failedNames[bookmark] = classify(1, "Error: Revision `nonexistent_rev` doesn't exist")

            failedNames[bookmark] shouldBe "dialog.bookmark.create.error.unknown"
        }

        @Test
        fun `exit code 2 maps to incorrect format error regardless of stderr`() {
            val failedNames = mutableMapOf<BookmarkName, String>()
            val bookmark = BookmarkName("invalid name")

            failedNames[bookmark] = classify(2, "error: invalid value 'invalid name'")

            failedNames[bookmark] shouldBe "dialog.bookmark.create.error.incorrect.format"
        }

        @Test
        fun `unexpected exit code with unrelated stderr maps to unknown error`() {
            val failedNames = mutableMapOf<BookmarkName, String>()
            val bookmark = BookmarkName("test")

            failedNames[bookmark] = classify(99, "Error: something else")

            failedNames[bookmark] shouldBe "dialog.bookmark.create.error.unknown"
        }

        @Test
        fun `failed names cache accumulates errors`() {
            val failedNames = mutableMapOf<BookmarkName, String>()

            failedNames[BookmarkName("main")] = "dialog.bookmark.create.error.already.exists"
            failedNames[BookmarkName("bad name")] = "dialog.bookmark.create.error.incorrect.format"

            failedNames.size shouldBe 2
        }

        @Test
        fun `retrying same name overwrites previous error`() {
            val failedNames = mutableMapOf<BookmarkName, String>()
            val bookmark = BookmarkName("main")

            failedNames[bookmark] = "dialog.bookmark.create.error.already.exists"
            failedNames[bookmark] = "dialog.bookmark.create.error.unknown"

            failedNames[bookmark] shouldBe "dialog.bookmark.create.error.unknown"
        }
    }

    @Nested
    inner class `Validation logic` {
        @Test
        fun `remote bookmark name is rejected`() {
            val bookmark = BookmarkName("main@origin")

            bookmark.isRemote shouldBe true
        }

        @Test
        fun `local bookmark name is accepted`() {
            val bookmark = BookmarkName("feature-branch")

            bookmark.isRemote shouldBe false
        }

        @Test
        fun `unknown bookmark not in failed cache returns null`() {
            val failedNames = mutableMapOf<BookmarkName, String>()

            failedNames[BookmarkName("unknown")].shouldBeNull()
        }

        @Test
        fun `validation checks remote before failed cache`() {
            // The doValidate logic: isRemote is checked first, then failedNames
            val bookmark = BookmarkName("main@origin")
            val failedNames = mutableMapOf<BookmarkName, String>()
            failedNames[bookmark] = "dialog.bookmark.create.error.already.exists"

            // Remote check takes precedence
            val errorKey = when {
                bookmark.isRemote -> "dialog.bookmark.create.error.not.remote"
                else -> failedNames[bookmark]
            }

            errorKey shouldBe "dialog.bookmark.create.error.not.remote"
        }

        @Test
        fun `validation returns failed cache error for local bookmark`() {
            val bookmark = BookmarkName("main")
            val failedNames = mutableMapOf<BookmarkName, String>()
            failedNames[bookmark] = "dialog.bookmark.create.error.already.exists"

            val errorKey = when {
                bookmark.isRemote -> "dialog.bookmark.create.error.not.remote"
                else -> failedNames[bookmark]
            }

            errorKey shouldBe "dialog.bookmark.create.error.already.exists"
        }

        @Test
        fun `validation returns null for valid local bookmark not in cache`() {
            val bookmark = BookmarkName("new-branch")
            val failedNames = mutableMapOf<BookmarkName, String>()

            val errorKey = when {
                bookmark.isRemote -> "dialog.bookmark.create.error.not.remote"
                else -> failedNames[bookmark]
            }

            errorKey.shouldBeNull()
        }

        @Test
        fun `empty failed names cache means no validation errors`() {
            val failedNames = mutableMapOf<BookmarkName, String>()

            failedNames.shouldBeEmpty()
        }
    }

    @Nested
    inner class `Move backwards detection` {
        @Test
        fun `stderr with backwards or sideways is detected`() {
            val stderr = "Error: Refusing to move bookmark backwards or sideways: main\n" +
                "Hint: Use --allow-backwards to allow it."

            (stderr.contains("backwards or sideways")) shouldBe true
        }

        @Test
        fun `other error stderr is not detected as backwards`() {
            val stderr = "Error: Bookmark main does not exist"

            (stderr.contains("backwards or sideways")) shouldBe false
        }

        @Test
        fun `exit code 1 with backwards stderr triggers retry flow`() {
            val exitCode = 1
            val stderr = "Refusing to move bookmark backwards or sideways: main"

            val isBackwardsError = exitCode == 1 && stderr.contains("backwards or sideways")

            isBackwardsError shouldBe true
        }

        @Test
        fun `exit code 1 without backwards stderr does not trigger retry`() {
            val exitCode = 1
            val stderr = "Error: Some other error"

            val isBackwardsError = exitCode == 1 && stderr.contains("backwards or sideways")

            isBackwardsError shouldBe false
        }

        @Test
        fun `exit code 2 with backwards stderr does not trigger retry`() {
            val exitCode = 2
            val stderr = "Refusing to move bookmark backwards or sideways: main"

            val isBackwardsError = exitCode == 1 && stderr.contains("backwards or sideways")

            isBackwardsError shouldBe false
        }
    }
}
