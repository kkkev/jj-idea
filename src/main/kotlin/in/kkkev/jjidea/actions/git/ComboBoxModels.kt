package `in`.kkkev.jjidea.actions.git

import javax.swing.DefaultComboBoxModel

/**
 * Replaces the model's contents and restores a selection.
 *
 * `DefaultComboBoxModel.removeAllElements()` nulls the selection, and `addAll(Collection)` —
 * unlike `addElement` — does not select anything afterwards. A plain `removeAllElements()` +
 * `addAll(items)` therefore leaves the combo box showing blank with a null selection, while any
 * Kotlin property still bound to the "old" selected value is now out of sync with what's on
 * screen (jj-idea-idm0: this desync fed a null combo selection into `bindItem`'s
 * `toNullableProperty()`, whose `!!` threw and left the Push button inert).
 */
internal fun <T> DefaultComboBoxModel<T>.replaceContents(items: List<T>, selected: T? = items.firstOrNull()) {
    removeAllElements()
    addAll(items)
    selectedItem = selected
}
