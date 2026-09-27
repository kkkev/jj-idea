package `in`.kkkev.jjidea.jj

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.FileContent
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.JujutsuDataKeys.DiffContentInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.every
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * GitHub: image diffs rendered as text. Both of the plugin's diff paths must hand the platform a
 * historical PNG as binary content (which the image diff tool renders), not a text document:
 * - the plugin's own [historicalDiffContent], used by `DiffSideImpl` for log/changes-tree diffs;
 * - the platform's [ChangeDiffRequestProducer], used for `Change`-based diffs, which only loads
 *   bytes when the revision is a [com.intellij.openapi.vcs.changes.ByteBackedContentRevision].
 * Text files must still come out as ordinary documents with the same text.
 */
@Tag("platform")
@TestApplication
class HistoricalDiffContentPlatformTest {
    private val changeId = ChangeId("abc123", "ab", null)
    private val repo = mockRepo()

    private val png: ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()

    private fun revision(path: String, bytes: ByteArray): ContentLogEntryImpl {
        val filePath = LocalFilePath("/repo/$path", false)
        every { repo.commandExecutor.showBytes(filePath, changeId) } returns bytes
        return ContentLogEntryImpl(repo, filePath, changeId)
    }

    private fun info(revision: ContentLogEntryImpl) = DiffContentInfo(repo, revision.file, CommitId("c0ffee"))

    @Test
    fun `historicalDiffContent gives a PNG as binary file content, keeping jj's diff info`() {
        val revision = revision("img/logo.png", png)
        val info = info(revision)

        val content = historicalDiffContent(null, revision, info)

        content.shouldNotBeInstanceOf<DocumentContent>()
        val file = content.shouldBeInstanceOf<FileContent>().file
        file.fileType.isBinary shouldBe true
        file.contentsToByteArray() shouldBe png
        content.getUserData(JujutsuDataKeys.DIFF_CONTENT_INFO) shouldBe info
    }

    @Test
    fun `historicalDiffContent still gives a text file as a document with the same text`() {
        val text = "fun main() {\n    println(\"héllo\")\n}\n"
        val revision = revision("src/Main.kt", text.toByteArray())
        val info = info(revision)

        val content = historicalDiffContent(null, revision, info)

        content.shouldBeInstanceOf<DocumentContent>().document.text shouldBe text
        content.getUserData(JujutsuDataKeys.DIFF_CONTENT_INFO) shouldBe info
    }

    @Test
    fun `historicalDiffContent is null when the content can't be loaded`() {
        val filePath = LocalFilePath("/repo/img/gone.png", false)
        every { repo.commandExecutor.showBytes(filePath, changeId) } throws VcsException("No such path")
        val revision = ContentLogEntryImpl(repo, filePath, changeId)

        historicalDiffContent(null, revision, info(revision)) shouldBe null
    }

    @Test
    fun `platform Change diffs load a historical PNG as binary file content`() {
        val content = ChangeDiffRequestProducer.createContent(
            null,
            revision("img/logo.png", png),
            UserDataHolderBase(),
            EmptyProgressIndicator()
        )

        content.shouldNotBeInstanceOf<DocumentContent>()
        content.shouldBeInstanceOf<FileContent>().file.contentsToByteArray() shouldBe png
    }

    @Test
    fun `platform Change diffs still load a historical text file as a document`() {
        val text = "plain text\n"
        val content = ChangeDiffRequestProducer.createContent(
            null,
            revision("notes.txt", text.toByteArray()),
            UserDataHolderBase(),
            EmptyProgressIndicator()
        )

        content.shouldBeInstanceOf<DocumentContent>().document.text shouldBe text
    }
}
