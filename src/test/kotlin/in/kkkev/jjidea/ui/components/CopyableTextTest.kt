package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.StringReader
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

/**
 * jj-idea-5zio: copying a selection must include each atomic unit's (chip's) real text - the document's
 * own `getText` sees only a placeholder character for each one. Uses a vanilla [HTMLEditorKit] document:
 * [copyableText] reads element attributes only, never views.
 */
class CopyableTextTest {
    private fun documentOf(html: String) = (HTMLEditorKit().createDefaultDocument() as HTMLDocument).apply {
        HTMLEditorKit().read(StringReader(html), this, 0)
    }

    private fun HTMLDocument.copyAll() = copyableText(0, length).trim()

    @Test
    fun `an author chip copies as its name and email`() {
        val doc = documentOf(
            htmlString {
                append("by ")
                appendWithEmail(VcsUserImpl("Alice", "alice@example.com"))
            }
        )

        doc.copyAll() shouldBe "by Alice <alice@example.com>"
    }

    @Test
    fun `a bookmark chip copies as its label and divergence, without its icon`() {
        val doc = documentOf(htmlString { append(Bookmark("main", aheadCount = 2)) })

        doc.copyAll() shouldBe "main↑2"
    }

    @Test
    fun `a standalone icon copies as nothing`() {
        val doc = documentOf(
            htmlString {
                append("a")
                append(icon(JujutsuIcons::Repo))
                append("b")
            }
        )

        doc.copyAll() shouldBe "ab"
    }

    @Test
    fun `a selection covering only part of the surrounding text copies just that part plus whole chips`() {
        val doc = documentOf(
            htmlString {
                append("before ")
                appendUnbreakable("12/07/2026")
                append(" after")
            }
        )
        val text = doc.getText(0, doc.length)
        val start = text.indexOf("fore")
        val end = text.indexOf("aft") + 3

        doc.copyableText(start, end) shouldBe "fore 12/07/2026 aft"
    }
}
