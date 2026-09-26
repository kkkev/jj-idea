package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.conflict.AcceptChoice
import `in`.kkkev.jjidea.jj.conflict.ConflictBlock
import `in`.kkkev.jjidea.jj.conflict.JjConflictBlockParser
import `in`.kkkev.jjidea.jj.conflict.choicesFor
import `in`.kkkev.jjidea.jj.conflict.replacementFor
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications

/**
 * One instance-bound entry in [ConflictBlockGutterIconRenderer]'s popup menu (jj-idea-82fo,
 * stage 4/4) - built fresh per block by the renderer, never registered in `plugin.xml`, exactly
 * like the S1 banner's own "Accept …" links are plain `createActionLabel` callbacks rather than
 * registered actions.
 *
 * [block] is a snapshot from the gutter icon's last reconciliation, not a live handle - between
 * that reconciliation and the user actually clicking through this menu item, the document may
 * have changed further (the debounce that drives reconciliation is deliberately not instant).
 * [actionPerformed] therefore **never trusts [block]'s own content**: it re-parses whatever
 * block (if any) now starts at [ConflictBlock.startOffset] from the live document and applies
 * [choice] to *that*, bailing out with [JujutsuNotifications] rather than guessing if nothing
 * recognizable is there any more (deleted, resolved elsewhere, or reshaped since).
 */
class AcceptConflictBlockAction(
    private val project: Project,
    private val document: Document,
    private val block: ConflictBlock,
    private val choice: AcceptChoice,
    text: String
) : AnAction(text) {
    override fun actionPerformed(e: AnActionEvent) {
        if (!FileDocumentManager.getInstance().requestWriting(document, project)) return

        val live = JjConflictBlockParser.parseBlockAt(document.immutableCharSequence, block.startOffset)
        if (live == null || choice !in choicesFor(live)) {
            JujutsuNotifications.notify(
                project,
                JujutsuBundle.message("gutter.conflict.stale.title"),
                JujutsuBundle.message("gutter.conflict.stale.message"),
                NotificationType.WARNING
            )
            return
        }

        val replacement = replacementFor(live, choice)
        WriteCommandAction.runWriteCommandAction(project, templateText, null, {
            document.replaceString(live.startOffset, live.endOffset, replacement)
        })
    }
}
