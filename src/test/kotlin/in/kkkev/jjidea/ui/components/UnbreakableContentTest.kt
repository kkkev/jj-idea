package `in`.kkkev.jjidea.ui.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Round-trip tests for [UnbreakableContent] - the single owner of the unbreakable-content wire format both
 * `HtmlTextCanvas` (encode) and `AtomicHtmlExtension` (decode) rely on. */
class UnbreakableContentTest {
    @Test
    fun `a plain html fragment round-trips`() {
        val html = "<img src='icon:foo'/>main"

        UnbreakableContent.decode(UnbreakableContent.encode(html)) shouldBe html
    }

    @Test
    fun `a fragment containing quotes, ampersands and unicode round-trips`() {
        val html = "<a href='https://tracker/JIRA-123'>JIRA-123</a> & \"quoted\" ↑2↓1 · café"

        UnbreakableContent.decode(UnbreakableContent.encode(html)) shouldBe html
    }

    @Test
    fun `an empty fragment round-trips`() {
        UnbreakableContent.decode(UnbreakableContent.encode("")) shouldBe ""
    }
}
