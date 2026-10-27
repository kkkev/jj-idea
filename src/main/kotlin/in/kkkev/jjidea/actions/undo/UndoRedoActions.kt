package `in`.kkkev.jjidea.actions.undo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.NlsActions
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntries
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.uniqueRepo
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.UndoRedoPlans
import `in`.kkkev.jjidea.jj.cli.UndoRedoPlan
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.oplog.title

internal enum class UndoRedoKind(private val key: String) {
    UNDO("undo"),
    REDO("redo");

    fun plan(plans: UndoRedoPlans): UndoRedoPlan = if (this == UNDO) plans.undo else plans.redo

    fun message(suffix: String, vararg params: Any): String = JujutsuBundle.message("action.$key.op.$suffix", *params)
}

/** Which repository an Undo/Redo acts on: the context's, else the project's only one, else none. */
internal sealed interface RepoChoice {
    data class Chosen(val repo: JujutsuRepository) : RepoChoice

    /** No jj repository in the project. */
    data object None : RepoChoice

    /** Several repositories and nothing in the context says which - same convention as UndoLastOperationAction. */
    data object Ambiguous : RepoChoice
}

internal fun chooseRepo(contextRepo: JujutsuRepository?, all: Collection<JujutsuRepository>): RepoChoice = when {
    contextRepo != null -> RepoChoice.Chosen(contextRepo)
    all.isEmpty() -> RepoChoice.None
    all.size == 1 -> RepoChoice.Chosen(all.single())
    else -> RepoChoice.Ambiguous
}

internal data class UndoRedoPresentation(val text: String, val enabled: Boolean, val description: String?)

private const val LABEL_MAX = 60

/**
 * The operation as the user would read it in a menu: its first description line, with the long
 * commit-id hashes jj embeds ("describe commit 19600818fb62...") cut to the short form the log
 * shows, and the whole thing bounded.
 */
internal fun operationLabel(entry: OperationEntry): String {
    val shortened = entry.title().replace(Regex("[0-9a-f]{12,}")) { it.value.take(8) }
    return if (shortened.length <= LABEL_MAX) shortened else shortened.take(LABEL_MAX - 1) + "…"
}

/**
 * Pure presentation, extracted for testing without an [AnActionEvent]. [plan] is `null` while the
 * repository's operation log has not been loaded yet. Disabled - never hidden, per contributing.md's
 * action-availability-hints tenet - with the reason as the description.
 */
internal fun resolveUndoRedoPresentation(
    kind: UndoRedoKind,
    choice: RepoChoice,
    plan: UndoRedoPlan?
): UndoRedoPresentation {
    fun disabled(reason: String) = UndoRedoPresentation(kind.message("none"), false, reason)
    return when {
        choice !is RepoChoice.Chosen -> disabled(kind.message("disabled.norepo"))
        plan == null -> disabled(kind.message("disabled.loading"))
        plan is UndoRedoPlan.Named && plan.labelOp.isSnapshot ->
            UndoRedoPresentation(kind.message("snapshot"), true, kind.message("description.snapshot"))
        plan is UndoRedoPlan.Named ->
            UndoRedoPresentation(kind.message("named", operationLabel(plan.labelOp)), true, kind.message("description"))
        plan is UndoRedoPlan.Unnamed ->
            UndoRedoPresentation(kind.message("unnamed"), true, kind.message("description.unnamed"))
        plan is UndoRedoPlan.Unavailable && plan.reason == UndoRedoPlan.Reason.WORKING_COPY_CHANGED ->
            disabled(kind.message("disabled.dirty"))
        else -> disabled(kind.message("disabled.nothing"))
    }
}

/**
 * Shared behaviour of [UndoAction] and [RedoAction] (jj-idea-x30x): `jj undo` / `jj redo`, with the
 * label naming the operation. Unlike [UndoLastOperationAction] these act on whatever is at the head
 * of the operation log - including operations from a terminal - which is exactly why the label must
 * name it and why a confirmation precedes the first use. See docs/design/undo-support-roadmap.md,
 * Stage 2, for the snapshot trap that makes them unavailable while the working copy has changes.
 *
 * No default keyboard shortcut, by design: see the roadmap's "On Cmd+Z" section.
 *
 * [repoOverride] lets the Operations tab, which knows which repository it is showing, answer for
 * the multi-repository case where the event carries no log selection.
 */
abstract class UndoRedoAction internal constructor(
    private val kind: UndoRedoKind,
    private val repoOverride: (() -> JujutsuRepository?)?,
    text: @NlsActions.ActionText String,
    description: @NlsActions.ActionDescription String
) : DumbAwareAction(
        text,
        description,
        if (kind ==
            UndoRedoKind.UNDO
        ) {
            AllIcons.Actions.Undo
        } else {
            AllIcons.Actions.Redo
        }
    ) {
    // EDT, not BGT: the Operations tab's repoOverride reads the Swing table selection. Everything else
    // here is non-blocking (cachedValue never shells out).
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private fun choose(e: AnActionEvent): RepoChoice {
        val project = e.project ?: return RepoChoice.None
        val context = repoOverride?.invoke() ?: e.logEntry?.repo ?: e.logEntries.uniqueRepo
        return chooseRepo(context, project.stateModel.initialisedRepositories.value.values)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        if (project == null) return
        val choice = choose(e)
        // cachedValue, not value: never blocks, even on a cold cache.
        val plan = (choice as? RepoChoice.Chosen)?.let { project.stateModel.undoRedoPlans.cachedValue[it.repo] }
            ?.let(kind::plan)
        val presentation = resolveUndoRedoPresentation(kind, choice, plan)
        e.presentation.text = presentation.text
        e.presentation.description = presentation.description
        e.presentation.isEnabled = presentation.enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = (choose(e) as? RepoChoice.Chosen)?.repo ?: return
        runUndoRedo(project, repo, kind)
    }
}

class UndoAction(repoOverride: (() -> JujutsuRepository?)? = null) : UndoRedoAction(
    UndoRedoKind.UNDO,
    repoOverride,
    JujutsuBundle.message("action.undo.op.none"),
    JujutsuBundle.message("action.undo.op.description")
)

class RedoAction(repoOverride: (() -> JujutsuRepository?)? = null) : UndoRedoAction(
    UndoRedoKind.REDO,
    repoOverride,
    JujutsuBundle.message("action.redo.op.none"),
    JujutsuBundle.message("action.redo.op.description")
)
