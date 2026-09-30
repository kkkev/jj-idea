package `in`.kkkev.jjidea.ui.dnd

import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.ui.components.UnbreakableContent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * The decoded text of every chip in [html]. A chip is one `<img src='unbreakable:…'/>` whose
 * content is URL-encoded into the src (see `UnbreakableContent`), so assertions about a chip's text
 * have to look through that encoding.
 */
internal fun chipContents(html: String): String =
    Regex("unbreakable:([^']*)'").findAll(html)
        .joinToString("") { UnbreakableContent.decode(it.groupValues[1]).plainText }

/**
 * [DropMessage] renders one structure two ways (jj-idea-ymuu): [DropMessage.plain] must stay exactly
 * the strings these messages were before they had structure (ids as their short form), while
 * [DropMessage.html] styles ids the way the log does.
 */
@JupiterTag("platform")
@TestApplication
class DropMessageTest {
    private val id = ChangeId("kkmpztqrvwxy", "kk", null)
    private val other = ChangeId("mzrsnvtuplok", "mz", null)

    @Test
    fun `plain uses the short form of an id`() {
        DropMessage.of("Rebase ", id, " onto ", other).plain shouldBe "Rebase kk onto mz"
    }

    @Test
    fun `plain keeps a divergence offset`() {
        DropMessage.of(ChangeId("kkmpztqrvwxy", "kk", 2), " is immutable").plain shouldBe "kk/2 is immutable"
    }

    @Test
    fun `html styles an id as the log does - bold prefix and a separate remainder`() {
        val html = DropMessage.of("Edit ", id).html

        html shouldContain "Edit "
        html shouldContain "<b>kk</b>"
        html shouldContain "mpztqr" // the remainder, rendered outside the bold prefix
        html shouldNotContain "<b>kkmpztqr"
    }

    @Test
    fun `plain uses a bookmark's and a tag's name`() {
        DropMessage.of("Move bookmark ", Bookmark("main"), " to ", id).plain shouldBe "Move bookmark main to kk"
        DropMessage.of("Move tag ", Tag("v1"), " to ", id).plain shouldBe "Move tag v1 to kk"
    }

    @Test
    fun `html renders a bookmark and a tag as chips with the ref name`() {
        DropMessage.of(Bookmark("main")).html.let {
            it shouldContain "main"
            it shouldContain "<img"
        }
        DropMessage.of(Tag("v1")).html.let {
            it shouldContain "v1"
            it shouldContain "<img"
        }
    }

    @Test
    fun `text after a chip starts with a non-breaking space then a legal break, not a collapsible plain space`() {
        val html = DropMessage.of("Move ", Bookmark("main"), " to ", id).html

        // A plain space straight after a chip's <img> collapses, gluing "to" to the chip's last letter.
        html shouldContain "&nbsp;\u200Bto "
        html shouldNotContain "</span> to "
    }

    @Test
    fun `text not adjacent to a chip keeps its plain spaces so lines can wrap there`() {
        DropMessage.of("Rebase ", id, " onto ", other).html shouldNotContain "&nbsp;"
    }

    @Test
    fun `a wide chip goes on its own line - a break before and after, and no indent on the next line`() {
        val html = DropMessage.of("Move bookmark ", Bookmark("main"), " to ", id).html(RefFit({ it }, { true }))

        html shouldContain "<br>"
        html.split("<br>").size shouldBe 3 // "Move bookmark " / chip / "to <id>"
        html.substringAfterLast("<br>") shouldNotContain "&nbsp;"
        html.substringAfterLast("<br>") shouldContain "to "
    }

    @Test
    fun `a wide chip at the very start or end of a message adds no dangling break`() {
        DropMessage.of(Bookmark("main"), " is conflicted").html(RefFit({ it }, { true })).let {
            it.startsWith("<br>") shouldBe false
        }
        DropMessage.of("Push ", Bookmark("main")).html(RefFit({ it }, { true })).let {
            it.endsWith("<br>") shouldBe false
        }
    }

    @Test
    fun `a narrow chip stays inline`() {
        DropMessage.of("Move bookmark ", Bookmark("main"), " to ", id).html(RefFit({ it }, { false })) shouldNotContain
            "<br>"
    }

    @Test
    fun `the fit's shortened name is what the chip shows`() {
        // A chip's content is URL-encoded inside its `unbreakable:` <img src>, so decode to look inside it.
        val html = DropMessage.of("Edit ", Tag("a-long-tag-name")).html(RefFit({ "a-lo…ame" }, { false }))

        chipContents(html).let {
            it shouldContain "a-lo…ame"
            it shouldNotContain "a-long-tag-name"
        }
    }

    @Test
    fun `ellipsize keeps both ends and never exceeds the cap`() {
        val name = "really-long-bookmark-name-that-spills-over-the-end"

        DropMessage.ellipsize(name, 20).let {
            it.length shouldBe 20
            it shouldContain "…"
            it.startsWith("really-lon") shouldBe true
            it.endsWith("over-the-end".takeLast(9)) shouldBe true
        }
        DropMessage.ellipsize("short", 20) shouldBe "short"
    }

    @Test
    fun `ellipsizeToWidth trims only as far as needed`() {
        val metrics = javax.swing.JLabel().getFontMetrics(java.awt.Font("Dialog", java.awt.Font.PLAIN, 12))
        val name = "really-long-bookmark-name-that-spills-over-the-end"
        val fullWidth = metrics.stringWidth(name)

        DropMessage.ellipsizeToWidth(name, metrics, fullWidth) shouldBe name
        DropMessage.ellipsizeToWidth(name, metrics, fullWidth - 1).let {
            it shouldContain "…"
            (metrics.stringWidth(it) <= fullWidth - 1) shouldBe true
            // ...but not more than one character further than necessary
            (metrics.stringWidth(DropMessage.ellipsize(name, it.length + 1)) > fullWidth - 1) shouldBe true
        }
    }

    @Test
    fun `html escapes literal text`() {
        DropMessage.of("a < b & c").html shouldBe "a &lt; b &amp; c"
    }

    @Test
    fun `of splices a nested message in place`() {
        val nested = DropMessage.of("the branch containing ", id)

        DropMessage.of("Rebase ", nested, " onto ", other).plain shouldBe "Rebase the branch containing kk onto mz"
    }

    @Test
    fun `EMPTY and blank text are blank`() {
        DropMessage.EMPTY.isBlank shouldBe true
        DropMessage.of("  ").isBlank shouldBe true
        DropMessage.of(id).isBlank shouldBe false
    }

    @Test
    fun `messages with the same parts are equal`() {
        DropMessage.of("Edit ", id) shouldBe DropMessage.of("Edit ", ChangeId("kkmpztqrvwxy", "kk", null))
        (DropMessage.of("Edit ", id) == DropMessage.of("Edit ", other)) shouldBe false
    }

    @Test
    fun `an unsupported part is rejected`() {
        shouldThrow<IllegalArgumentException> { DropMessage.of("x", 42) }
    }
}
