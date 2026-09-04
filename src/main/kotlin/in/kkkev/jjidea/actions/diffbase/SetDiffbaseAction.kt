package `in`.kkkev.jjidea.actions.diffbase

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.settings.DiffbaseStrategy
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.components.Filter
import `in`.kkkev.jjidea.ui.components.RevisionSelectorPopup
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.diffbase.DiffbaseService
import `in`.kkkev.jjidea.vcs.diffbase.ResolveResult
import `in`.kkkev.jjidea.vcs.diffbase.resolveExactlyOne
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * One row of the "Set Diff Base" popup: a strategy, its label, whether it's the repo's current
 * choice, and — for [DiffbaseStrategy.CUSTOM_REVSET] only — the revset text to show alongside it.
 */
data class DiffbaseMenuItem(
    val strategy: DiffbaseStrategy,
    val label: String,
    val selected: Boolean,
    val customRevset: String? = null
)

/**
 * The data-driven rows of the "Set Diff Base" popup for [repo]'s current settings, in display
 * order: the three fixed strategies, plus a trailing "Custom: <revset>" summary row when
 * [DiffbaseStrategy.CUSTOM_REVSET] is the active strategy and its revset is non-blank. Pure and
 * unit-testable — same "extract the availability logic" convention as `editableEntry` in
 * `actions/change/EditChangeAction.kt`. [SetDiffbaseAction] wraps this with the non-data-driven
 * "Custom revset..." and "Pin to Revision..." entries — see its class doc for why those are two
 * separate entries rather than one.
 */
fun diffbaseMenuItems(strategy: DiffbaseStrategy, customRevset: String): List<DiffbaseMenuItem> {
    val fixed = listOf(
        DiffbaseMenuItem(
            DiffbaseStrategy.WORKING_COPY_PARENT,
            JujutsuBundle.message("settings.diffbase.workingcopy"),
            strategy == DiffbaseStrategy.WORKING_COPY_PARENT
        ),
        DiffbaseMenuItem(
            DiffbaseStrategy.IMMUTABLE_ANCESTOR,
            JujutsuBundle.message("settings.diffbase.immutable"),
            strategy == DiffbaseStrategy.IMMUTABLE_ANCESTOR
        ),
        DiffbaseMenuItem(
            DiffbaseStrategy.PREVIOUS_COMMIT,
            JujutsuBundle.message("settings.diffbase.previous"),
            strategy == DiffbaseStrategy.PREVIOUS_COMMIT
        )
    )
    val trimmed = customRevset.trim()
    val custom = if (strategy == DiffbaseStrategy.CUSTOM_REVSET && trimmed.isNotEmpty()) {
        listOf(
            DiffbaseMenuItem(
                DiffbaseStrategy.CUSTOM_REVSET,
                JujutsuBundle.message("action.diffbase.custom", trimmed),
                selected = true,
                customRevset = trimmed
            )
        )
    } else {
        emptyList()
    }
    return fixed + custom
}

/**
 * Quick action to change a repository's diff base — the base revision used for editor gutter
 * change markers and Annotate, see [DiffbaseService]. Writes the same per-repo setting as
 * Settings → Version Control → Jujutsu's per-repo override row
 * ([JujutsuSettings.setDiffbase]), so there is one source of truth for "task-driven" switches
 * (jj-idea-g1io, GitHub #43) and the permanent per-project default.
 *
 * The popup deliberately offers **three** kinds of entry, not one "pick a revision" catch-all:
 * - the three fixed [DiffbaseStrategy] rows, applied immediately on click;
 * - **"Custom revset..."**, a typed revset *expression* (e.g. `trunk()`) saved and re-resolved
 *   verbatim on every future diff-base change — the literal text is what persists;
 * - **"Pin to Revision..."**, which *freezes* to whatever concrete commit the picked
 *   bookmark/tag/change resolves to right now.
 *
 * These look similar but aren't interchangeable: reusing a single "pick a revision" picker for
 * both (the original jj-idea-g1io shape) silently converted a typed expression into a frozen
 * commit id the moment the picker's own background resolution caught up with it — see
 * [RevisionSelectorPopup]/`RevisionChoicePanel.scheduleResolve` — which made it effectively
 * impossible to save a literal, moving revset. Keeping the two paths separate keeps both
 * behaviors honest and discoverable from the menu labels alone.
 *
 * Reachable from the `Jujutsu` submenu in `Vcs.MainMenu` and the editor's Jujutsu context submenu
 * (plugin.xml).
 */
class SetDiffbaseAction : DumbAwareAction(
    JujutsuBundle.message("action.diffbase.set"),
    JujutsuBundle.message("action.diffbase.set.description"),
    AllIcons.General.Pin
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            project.isJujutsu &&
            candidateRepos(e, project).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repos = candidateRepos(e, project)
        if (repos.isEmpty()) return

        val group = BackgroundActionGroup(
            *if (repos.size == 1) {
                repoMenuItems(repos.first()).toTypedArray()
            } else {
                repos.map { repo ->
                    DefaultActionGroup(repo.displayName, true).apply {
                        repoMenuItems(repo).forEach(::add)
                    }
                }.toTypedArray()
            }
        )

        JBPopupFactory.getInstance().createActionGroupPopup(
            JujutsuBundle.message("action.diffbase.popup.title"),
            group,
            e.dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        ).showInBestPositionFor(e.dataContext)
    }

    private fun candidateRepos(e: AnActionEvent, project: Project): List<JujutsuRepository> {
        val fromSelection = e.repoForFile ?: e.singleRepoForFiles
        return fromSelection?.let { listOf(it) } ?: project.initialisedJujutsuRepositories.toList()
    }

    // ── menu construction ───────────────────────────────────────────────────────

    private fun repoMenuItems(repo: JujutsuRepository): List<AnAction> {
        val settings = JujutsuSettings.getInstance(repo.project)
        val strategy = settings.diffbaseStrategy(repo)
        val customRevset = settings.customDiffbaseRevset(repo)
        val items = diffbaseMenuItems(strategy, customRevset)

        val fixedActions = items.map { item -> strategyToggle(repo, item) }

        val customRevsetAction =
            object : DumbAwareAction(JujutsuBundle.message("action.diffbase.customRevset")) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = promptForCustomRevset(repo)
            }

        val pinAction = object : DumbAwareAction(JujutsuBundle.message("action.diffbase.pin")) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) = pinToRevision(repo)
        }

        val settingsAction = object : DumbAwareAction(JujutsuBundle.message("action.diffbase.settings")) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(repo.project, "Jujutsu")
            }
        }

        return fixedActions + customRevsetAction + pinAction + Separator.getInstance() + settingsAction
    }

    private fun strategyToggle(repo: JujutsuRepository, item: DiffbaseMenuItem): AnAction =
        object : ToggleAction(item.label) {
            init {
                // Close on click instead of ToggleAction's default checkbox "stay open"
                // behavior — this is a one-shot radio-style pick, not a persistent multi-select
                // list, and it sidesteps a checkmark-staleness issue: isSelected() reflects the
                // state as of when this popup was built, so keeping it open after a click would
                // show a stale tick until the next reopen. Same fix, same rationale, as
                // FilterToReferenceAction in ui/log/JujutsuLogContextMenuActions.kt.
                templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
            }

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun isSelected(e: AnActionEvent) = item.selected
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (item.selected) return
                applyStrategy(repo, item.strategy, item.customRevset ?: "")
            }
        }

    // ── applying a choice ───────────────────────────────────────────────────────

    /** "Custom revset...": a typed, persistent revset expression — see the class doc. */
    private fun promptForCustomRevset(repo: JujutsuRepository) {
        val settings = JujutsuSettings.getInstance(repo.project)
        val initial = if (settings.diffbaseStrategy(repo) == DiffbaseStrategy.CUSTOM_REVSET) {
            settings.customDiffbaseRevset(repo)
        } else {
            ""
        }
        val revset = Messages.showInputDialog(
            repo.project,
            JujutsuBundle.message("action.diffbase.customRevset.message"),
            JujutsuBundle.message("action.diffbase.customRevset.title"),
            null,
            initial,
            null
        )?.trim()
        if (!revset.isNullOrEmpty()) {
            validateAndApplyCustomRevset(repo, revset)
        }
    }

    /** "Pin to Revision...": freezes to whichever concrete commit is picked — see the class doc. */
    private fun pinToRevision(repo: JujutsuRepository) {
        RevisionSelectorPopup.show(
            "action.diffbase.pin.popup.title",
            repo,
            Filter(includeRemote = true, includeLogEntries = true)
        ) { chosen -> validateAndApplyCustomRevset(repo, chosen.toString()) }
    }

    /**
     * Shared by [promptForCustomRevset], [pinToRevision]'s callback, and re-selecting an
     * already-listed "Custom: ..." row (via [applyStrategy]) — validates [revset] resolves to
     * exactly one revision (same rule [DiffbaseService.resolve] applies at read time; see
     * [resolveExactlyOne]) off the EDT, then applies it or reports why it couldn't.
     */
    private fun validateAndApplyCustomRevset(repo: JujutsuRepository, revset: String) {
        runInBackground {
            when (val result = resolveExactlyOne(repo, revset)) {
                is ResolveResult.Single -> runLater { applyAndNotify(repo, DiffbaseStrategy.CUSTOM_REVSET, revset) }
                is ResolveResult.None -> runLater {
                    Messages.showErrorDialog(
                        repo.project,
                        JujutsuBundle.message("action.diffbase.error.resolve", revset),
                        JujutsuBundle.message("action.diffbase.error.title")
                    )
                }
                is ResolveResult.Ambiguous -> runLater {
                    Messages.showErrorDialog(
                        repo.project,
                        JujutsuBundle.message("action.diffbase.error.ambiguous", revset, result.count),
                        JujutsuBundle.message("action.diffbase.error.title")
                    )
                }
            }
        }
    }

    private fun applyStrategy(repo: JujutsuRepository, strategy: DiffbaseStrategy, customRevset: String) {
        if (strategy == DiffbaseStrategy.CUSTOM_REVSET) {
            // Re-validate: the row's own revset may be stale by the time the toggle fires (e.g.
            // re-selecting an already-listed "Custom: ..." row after the repo changed).
            validateAndApplyCustomRevset(repo, customRevset)
        } else {
            applyAndNotify(repo, strategy, customRevset)
        }
    }

    private fun applyAndNotify(repo: JujutsuRepository, strategy: DiffbaseStrategy, customRevset: String) {
        JujutsuSettings.getInstance(repo.project).setDiffbase(repo, strategy, customRevset)
        DiffbaseService.getInstance(repo.project).notifyDiffbaseChanged()
    }
}
