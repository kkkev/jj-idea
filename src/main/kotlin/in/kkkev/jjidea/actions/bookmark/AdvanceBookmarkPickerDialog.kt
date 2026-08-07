package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * Shown by [advanceClosestBookmarkAction] when more than one bookmark is equally close to `@`
 * (e.g. either side of a merge) — lets the user pick which of them to advance, per #61's own
 * suggested behaviour for that case. All start pre-checked, matching a no-args
 * `jj bookmark advance` which would move every one of them.
 */
class AdvanceBookmarkPickerDialog private constructor(
    repo: JujutsuRepository,
    private val names: List<BookmarkName>,
    private val onConfirm: (List<BookmarkName>) -> Unit
) : DialogWrapper(repo.project) {
    private val checkboxes: Map<BookmarkName, JBCheckBox> = names.associateWith { JBCheckBox(it.name, true) }

    init {
        title = JujutsuBundle.message("dialog.bookmark.advance.title")
        init()
    }

    override fun createCenterPanel() = panel {
        row { label(JujutsuBundle.message("dialog.bookmark.advance.message")) }
        names.forEach { name -> row { cell(checkboxes.getValue(name)) } }
    }

    override fun doOKAction() {
        super.doOKAction()
        val chosen = names.filter { checkboxes.getValue(it).isSelected }
        if (chosen.isNotEmpty()) onConfirm(chosen)
    }

    companion object {
        fun show(repo: JujutsuRepository, names: List<BookmarkName>, onConfirm: (List<BookmarkName>) -> Unit) {
            AdvanceBookmarkPickerDialog(repo, names, onConfirm).show()
        }
    }
}
