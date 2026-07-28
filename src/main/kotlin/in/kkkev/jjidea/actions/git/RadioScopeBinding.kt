package `in`.kkkev.jjidea.actions.git

import com.intellij.ui.dsl.builder.Cell
import javax.swing.AbstractButton
import kotlin.reflect.KMutableProperty0

/**
 * Two-way binds this radio button's checked state to [prop] matching [value]: selects the
 * button now if [prop]'s current value equals [value], and updates [prop] to [value] whenever
 * the user clicks it.
 *
 * Written locally rather than using the platform's `ButtonsGroup.bind(::prop)` because that
 * helper is an inline function; on 2025.3+ (253+) platforms it's compiled targeting a newer JVM
 * bytecode version than this plugin's cross-version build target allows to inline into
 * (jj-idea-gu9q). This function is compiled by our own build at our own target, so it doesn't
 * have that constraint.
 *
 * **Important**: use the value-less `radioButton(text)` overload with this helper, not
 * `radioButton(text, value)`. `ButtonsGroupImpl.postInitUnbound` (used whenever the enclosing
 * `buttonsGroup { }` has no `.bind(...)` call, which is always true here) throws
 * `UiDslException` for any radio button that carries a non-null value — that value argument only
 * has meaning when paired with the platform's own `.bind`. `bindScope` supplies the initial
 * selection and write-back itself, and the enclosing `ButtonGroup` still enforces mutual
 * exclusion, so no value argument is needed (jj-idea-tmmy).
 */
fun <T, C : AbstractButton> Cell<C>.bindScope(prop: KMutableProperty0<T>, value: T): Cell<C> {
    component.isSelected = prop.get() == value
    component.addActionListener { prop.set(value) }
    return this
}
