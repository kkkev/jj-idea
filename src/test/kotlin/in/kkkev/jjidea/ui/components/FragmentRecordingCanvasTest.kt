package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Font

class FragmentRecordingCanvasTest {
    @Nested
    inner class `basic recording` {
        @Test
        fun `records plain text fragment`() {
            val canvas = FragmentRecordingCanvas()
            canvas.append("hello")

            canvas.fragments shouldHaveSize 1
            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            frag.text shouldBe "hello"
            frag.style shouldBe SimpleTextAttributes.REGULAR_ATTRIBUTES
            frag.truncatable shouldBe false
        }

        @Test
        fun `records multiple fragments`() {
            val canvas = FragmentRecordingCanvas()
            canvas.append("one")
            canvas.append("two")

            canvas.fragments shouldHaveSize 2
        }

        @Test
        fun `records icon fragment`() {
            val canvas = FragmentRecordingCanvas()
            val iconSpec = icon(AllIcons.Nodes::Bookmark)
            canvas.append(iconSpec)

            canvas.fragments shouldHaveSize 1
            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Icon>()
            frag.icon shouldBe iconSpec
            frag.truncatable shouldBe false
        }
    }

    @Nested
    inner class `styled recording` {
        @Test
        fun `bold styling applied to fragment`() {
            val canvas = FragmentRecordingCanvas()
            canvas.bold { append("bold text") }

            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            (frag.style.fontStyle and Font.BOLD) shouldBe Font.BOLD
        }

        @Test
        fun `italic styling applied to fragment`() {
            val canvas = FragmentRecordingCanvas()
            canvas.italic { append("italic text") }

            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            (frag.style.fontStyle and Font.ITALIC) shouldBe Font.ITALIC
        }

        @Test
        fun `nested bold then italic adds to style`() {
            val canvas = FragmentRecordingCanvas()
            canvas.bold { italic { append("italic and bold") } }

            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            frag.style.fontStyle shouldBe (Font.ITALIC or Font.BOLD)
        }

        @Test
        fun `styled with combined flags`() {
            val canvas = FragmentRecordingCanvas()
            // To get both bold+italic, pass combined style in one call
            canvas.styled(Font.BOLD or Font.ITALIC) { append("bold italic") }

            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            (frag.style.fontStyle and Font.BOLD) shouldBe Font.BOLD
            (frag.style.fontStyle and Font.ITALIC) shouldBe Font.ITALIC
        }

        @Test
        fun `colored styling`() {
            val canvas = FragmentRecordingCanvas()
            canvas.colored(JujutsuColors.WORKING_COPY) { append("colored") }

            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            frag.style.fgColor shouldBe JujutsuColors.WORKING_COPY
        }

        @Test
        fun `style resets after block`() {
            val canvas = FragmentRecordingCanvas()
            canvas.bold { append("bold") }
            canvas.append("regular")

            val boldFrag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            val regularFrag = canvas.fragments[1].shouldBeInstanceOf<Fragment.Text>()
            (boldFrag.style.fontStyle and Font.BOLD) shouldBe Font.BOLD
            regularFrag.style shouldBe SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
    }

    @Nested
    inner class `truncate marking` {
        @Test
        fun `no truncate range when nothing marked`() {
            val canvas = FragmentRecordingCanvas()
            canvas.append("text")

            canvas.truncateRange.shouldBeNull()
        }

        @Test
        fun `single truncatable fragment`() {
            val canvas = FragmentRecordingCanvas()
            canvas.truncate { append("truncatable") }

            canvas.truncateRange.shouldNotBeNull()
            canvas.truncateRange shouldBe 0..0
            canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>().truncatable shouldBe true
        }

        @Test
        fun `mixed truncatable and non-truncatable`() {
            val canvas = FragmentRecordingCanvas()
            canvas.append("prefix ") // 0 - not truncatable
            canvas.truncate { append("middle") } // 1 - truncatable
            canvas.append(" suffix") // 2 - not truncatable

            canvas.fragments shouldHaveSize 3
            canvas.truncateRange shouldBe 1..1
            canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>().truncatable shouldBe false
            canvas.fragments[1].shouldBeInstanceOf<Fragment.Text>().truncatable shouldBe true
            canvas.fragments[2].shouldBeInstanceOf<Fragment.Text>().truncatable shouldBe false
        }

        @Test
        fun `multiple fragments inside truncate block`() {
            val canvas = FragmentRecordingCanvas()
            canvas.truncate {
                append("Description: ")
                bold { append("important part") }
                append(" - suffix")
            }

            canvas.fragments shouldHaveSize 3
            canvas.truncateRange shouldBe 0..2
            canvas.fragments.forEach { it.shouldBeInstanceOf<Fragment.Text>().truncatable shouldBe true }
        }

        @Test
        fun `truncate with nested styling preserves style`() {
            val canvas = FragmentRecordingCanvas()
            canvas.truncate { grey { italic { append("(no description set)") } } }

            canvas.fragments shouldHaveSize 1
            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            frag.truncatable shouldBe true
            (frag.style.fontStyle and Font.ITALIC) shouldBe Font.ITALIC
        }

        @Test
        fun `truncatable icon`() {
            val canvas = FragmentRecordingCanvas()
            canvas.truncate { append(icon(AllIcons.Nodes::Bookmark)) }

            canvas.fragments shouldHaveSize 1
            canvas.fragments[0].shouldBeInstanceOf<Fragment.Icon>().truncatable shouldBe true
            canvas.truncateRange shouldBe 0..0
        }
    }

    @Nested
    inner class `appendSummary integration` {
        @Test
        fun `non-empty description marked truncatable`() {
            val canvas = FragmentRecordingCanvas()
            canvas.appendSummary(Description("Some description"))

            canvas.fragments shouldHaveSize 1
            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            frag.text shouldBe "Some description"
            frag.truncatable shouldBe true
            frag.style shouldBe SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        @Test
        fun `empty description marked truncatable with grey italic`() {
            val canvas = FragmentRecordingCanvas()
            canvas.appendSummary(Description(""))

            canvas.fragments shouldHaveSize 1
            val frag = canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>()
            frag.truncatable shouldBe true
            (frag.style.fontStyle and Font.ITALIC) shouldBe Font.ITALIC
        }

        @Test
        fun `appendSummary between other fragments`() {
            val canvas = FragmentRecordingCanvas()
            canvas.append("id ")
            canvas.appendSummary(Description("desc"))
            canvas.append(" [empty]")

            canvas.fragments shouldHaveSize 3
            canvas.fragments[0].shouldBeInstanceOf<Fragment.Text>().truncatable shouldBe false
            canvas.fragments[1].shouldBeInstanceOf<Fragment.Text>().truncatable shouldBe true
            canvas.fragments[2].shouldBeInstanceOf<Fragment.Text>().truncatable shouldBe false
            canvas.truncateRange shouldBe 1..1
        }
    }
}
