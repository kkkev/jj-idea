package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleTextAttributes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font

/** Round-trip tests for [UnbreakableContent] - the single owner of the unbreakable-content wire format both
 * `HtmlTextCanvas` (encode) and `AtomicHtmlExtension` (decode) rely on. */
class UnbreakableContentTest {
    private fun roundTrip(content: ChipContent) = UnbreakableContent.decode(UnbreakableContent.encode(content))

    @Test
    fun `icon and text runs round-trip with their style, color and link`() {
        val content = ChipContent(
            SimpleTextAttributes.STYLE_SMALLER,
            listOf(
                ChipRun.Icon("JujutsuIcons.BookmarkTracked#112233", SimpleTextAttributes.STYLE_SMALLER, null),
                ChipRun.Text("JIRA-123", Font.BOLD, Color(0x445566), "https://tracker/JIRA-123"),
                ChipRun.Text("-fix", SimpleTextAttributes.STYLE_STRIKEOUT, null, null)
            )
        )

        roundTrip(content) shouldBe content
    }

    @Test
    fun `text containing separators, escapes, quotes and unicode round-trips`() {
        val text = "a\u001Fb\nc\\d\\n & \"quoted\" <tag> ↑2↓1 · café"
        val content = ChipContent(0, listOf(ChipRun.Text(text, 0, null, null)))

        roundTrip(content) shouldBe content
    }

    @Test
    fun `an empty unit round-trips`() {
        roundTrip(ChipContent(0, emptyList())) shouldBe ChipContent(0, emptyList())
    }

    @Test
    fun `a malformed payload decodes to an empty unit rather than throwing`() {
        UnbreakableContent.decode("not%20a%20unit") shouldBe ChipContent(0, emptyList())
    }

    @Test
    fun `plain text joins the text runs and skips icons`() {
        val content = ChipContent(
            0,
            listOf(
                ChipRun.Icon("JujutsuIcons.Tag", 0, null),
                ChipRun.Text("v1.0", 0, null, null),
                ChipRun.Text("↑2", 0, null, null)
            )
        )

        content.plainText shouldBe "v1.0↑2"
    }
}
