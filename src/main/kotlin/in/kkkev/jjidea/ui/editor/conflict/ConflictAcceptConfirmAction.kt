package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import `in`.kkkev.jjidea.jj.conflict.AcceptChoice
import `in`.kkkev.jjidea.jj.conflict.ConflictBlock
import java.awt.event.MouseEvent

/**
 * [ConflictBlockGutterIconRenderer]'s left-click action (jj-idea-82fo follow-up) - shows a
 * one-item confirmation popup ("Accept `<label>`") rather than applying [choice] immediately, so
 * a bare click on the icon can't silently edit the document - one deliberate extra step, without
 * going all the way back to the old whole-block 4-choice menu, which is still reachable
 * unchanged via right-click ([ConflictBlockGutterIconRenderer.getPopupMenuActions]).
 *
 * Built manually with [JBPopupFactory] rather than relying on the platform's own
 * click-with-no-`getClickAction`-falls-back-to-`getPopupMenuActions` behaviour
 * ([com.intellij.openapi.editor.impl.EditorGutterComponentImpl.isPopupAction]) - that fallback
 * is wired to the *same* [com.intellij.openapi.editor.markup.GutterIconRenderer.getPopupMenuActions]
 * right-click uses, so it can't show a *different*, single-item menu on left-click while keeping
 * the full menu on right-click.
 *
 * The actual write happens in [AcceptConflictBlockAction] once the popup's one entry is chosen -
 * unchanged, including its own re-parse-at-click-time safety check.
 */
class ConflictAcceptConfirmAction(
    private val project: Project,
    private val document: Document,
    private val block: ConflictBlock,
    private val choice: AcceptChoice,
    text: String
) : AnAction(text) {
    override fun actionPerformed(e: AnActionEvent) {
        val group =
            DefaultActionGroup(AcceptConflictBlockAction(project, document, block, choice, templateText.orEmpty()))
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            group,
            e.dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
        when (val inputEvent = e.inputEvent) {
            is MouseEvent -> popup.show(RelativePoint(inputEvent))
            else -> popup.showInBestPositionFor(e.dataContext)
        }
    }
}
