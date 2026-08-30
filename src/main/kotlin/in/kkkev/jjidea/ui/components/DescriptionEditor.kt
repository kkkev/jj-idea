package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.Description
import javax.swing.JComponent

/**
 * Thin wrapper around the platform's [CommitMessage] component so every description input in the
 * plugin gets IntelliJ's real commit-message editor - spellcheck, subject/body length
 * inspections, the recent-messages history popup (Ctrl+E), and completion contributors (e.g.
 * `Co-authored-by:`) - instead of a plain [javax.swing.JTextArea] (GitHub #46, jj-idea-n3w1).
 *
 * Deliberately thin: [CommitMessage] already does the real work (see
 * `com.intellij.openapi.vcs.ui.CommitMessage.createCommitMessageEditor`). This wrapper exists only
 * to give call sites a [Description]-typed [text] property, a single [dispose] to register with
 * the owning [Disposable], and a helper to feed accepted text into the IDE's shared recent-message
 * history so other plugin dialogs and Git's own commit UI both see it.
 *
 * Modelled on the platform's own standalone-`CommitMessage`-in-a-dialog examples, notably
 * `git4idea.rebase.log.GitNewCommitMessageActionDialog`.
 */
class DescriptionEditor(
    project: Project,
    placeholder: String? = null,
    minimumSize: java.awt.Dimension = JBUI.size(300, 60)
) : Disposable {
    // Arguments: withSeparator = false, showToolbar = true (Ctrl+E history popup), runInspections = true.
    val commitMessage: CommitMessage = CommitMessage(project, false, true, true, placeholder).apply {
        this.minimumSize = minimumSize
    }

    /** The component to add to a layout. Scrolls itself - do not wrap in a [javax.swing.JScrollPane]. */
    val component: JComponent get() = commitMessage

    /** Target for `DialogWrapper.getPreferredFocusedComponent()`. */
    val focusTarget: JComponent get() = commitMessage.editorField

    var text: Description
        get() = Description(commitMessage.comment)
        set(value) = commitMessage.setCommitMessage(value.actual)

    /** Registers a listener for text changes, mirroring the old Swing `DocumentListener` usage. */
    fun addTextChangeListener(onChange: () -> Unit) {
        commitMessage.editorField.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = onChange()
        })
    }

    /**
     * Adds [text] to the IDE's shared recent-commit-messages list (also used by Git's commit UI),
     * which is what actually populates the history popup on the toolbar this component shows.
     * Call only after a successful `jj` operation - never on cancel - so a discarded edit doesn't
     * pollute the history.
     */
    fun saveToHistory(project: Project) {
        VcsConfiguration.getInstance(project).saveCommitMessage(text.actual)
    }

    override fun dispose() = Disposer.dispose(commitMessage)
}
