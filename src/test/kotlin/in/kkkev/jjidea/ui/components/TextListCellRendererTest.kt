package `in`.kkkev.jjidea.ui.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import javax.swing.JList

class TextListCellRendererTest {
    private val renderer = object : TextListCellRenderer<String>() {
        override fun render(canvas: TextCanvas, value: String) {
            canvas.append(value)
        }
    }

    @Test
    fun `getListCellRendererComponent returns the same instance on every call`() {
        val list = JList(arrayOf("a", "b", "c"))
        val first = renderer.getListCellRendererComponent(list, "a", 0, false, false)
        val second = renderer.getListCellRendererComponent(list, "b", 1, true, false)
        val third = renderer.getListCellRendererComponent(list, "c", 2, false, true)
        first shouldBe second
        second shouldBe third
    }
}
