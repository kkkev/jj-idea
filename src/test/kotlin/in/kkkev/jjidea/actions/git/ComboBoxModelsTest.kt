package `in`.kkkev.jjidea.actions.git

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import javax.swing.DefaultComboBoxModel

/**
 * Regression tests for jj-idea-idm0: `DefaultComboBoxModel.removeAllElements()` +
 * `addAll(list)` leaves the model's selection null (`addAll` doesn't select, unlike
 * `addElement`), which is what fed a null selection into `bindItem(...toNullableProperty())`'s
 * `!!` and threw an NPE from `GitPushDialog.doOKAction`, leaving the Push button inert.
 */
class ComboBoxModelsTest {
    @Test
    fun `selects the first item by default after replacing contents`() {
        val model = DefaultComboBoxModel(arrayOf("a"))
        model.replaceContents(listOf("x", "y", "z"))
        model.selectedItem shouldBe "x"
    }

    @Test
    fun `selects null when the new item list is empty`() {
        val model = DefaultComboBoxModel(arrayOf("a"))
        model.replaceContents(emptyList())
        model.selectedItem shouldBe null
    }

    @Test
    fun `honours an explicit selection`() {
        val model = DefaultComboBoxModel(arrayOf("a"))
        model.replaceContents(listOf("x", "y", "z"), selected = "y")
        model.selectedItem shouldBe "y"
    }

    @Test
    fun `replaces the full element list, not just the selection`() {
        val model = DefaultComboBoxModel(arrayOf("a", "b", "c"))
        model.replaceContents(listOf("x", "y"))
        (0 until model.size).map { model.getElementAt(it) } shouldBe listOf("x", "y")
    }
}
