package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.jj.cli.isValidRevsetAliasName
import `in`.kkkev.jjidea.jj.cli.revsetAliasExists
import `in`.kkkev.jjidea.jj.cli.saveRevsetAlias
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * "Save as revset alias…" (jj-idea-ehwm.6, GitHub #116/#150): writes [expression] to jj config as
 * `revset-aliases.<name>`, in the user config or - for every repo in [repos] - the repo config.
 *
 * Asks before overwriting an existing alias, and checks that the saved name resolves against every
 * repo ([validateRevsetFilter]) before calling [onSaved], so the caller never switches its input to a
 * name jj can't resolve. Work is O(#repos) `jj config` processes plus one `--limit 1` log per repo.
 */
class SaveRevsetAliasDialog(
    private val repos: Collection<JujutsuRepository>,
    private val expression: String,
    private val baseFor: (JujutsuRepository) -> Revset,
    private val onSaved: (name: String) -> Unit
) : DialogWrapper(repos.first().project) {
    private val project = repos.first().project
    private val nameField = JBTextField()
    private var scope = CommandExecutor.ConfigScope.USER

    private val name get() = nameField.text.trim()

    init {
        title = JujutsuBundle.message("log.filter.revset.alias.title")
        init()
    }

    override fun createCenterPanel() = panel {
        row(JujutsuBundle.message("log.filter.revset.alias.expression")) {
            // Selectable so it can be copied, not editable: the alias is exactly what the input held.
            cell(JBTextField(expression).apply { isEditable = false }).columns(COLUMNS_MEDIUM)
        }
        row(JujutsuBundle.message("log.filter.revset.alias.name")) {
            cell(nameField).focused().columns(COLUMNS_MEDIUM)
        }
        // The DSL only accepts radio buttons it creates itself, inside a buttonsGroup. Selection is tracked
        // in [scope] as it changes: doOKAction doesn't run the panel's apply().
        buttonsGroup(JujutsuBundle.message("log.filter.revset.alias.scope")) {
            row {
                radioButton(JujutsuBundle.message("log.filter.revset.alias.scope.user"))
                    .applyToComponent {
                        isSelected = true
                        addActionListener { scope = CommandExecutor.ConfigScope.USER }
                    }
                radioButton(JujutsuBundle.message("log.filter.revset.alias.scope.repo"))
                    .applyToComponent { addActionListener { scope = CommandExecutor.ConfigScope.REPO } }
            }
        }
    }

    override fun continuousValidation() = true

    override fun doValidate(): ValidationInfo? = when {
        name.isEmpty() -> null // no error before the user has typed anything; OK is blocked below
        !isValidRevsetAliasName(name) ->
            ValidationInfo(JujutsuBundle.message("log.filter.revset.alias.error.name"), nameField)
        else -> null
    }

    override fun doOKAction() {
        if (name.isEmpty() || !isValidRevsetAliasName(name)) {
            setErrorText(JujutsuBundle.message("log.filter.revset.alias.error.name"), nameField)
            return
        }
        val aliasName = name
        val aliasScope = scope
        // The user config is shared by every repo, so one write is enough; repo scope writes each repo's own.
        val targets = if (aliasScope == CommandExecutor.ConfigScope.USER) listOf(repos.first()) else repos.toList()

        isOKActionEnabled = false
        setErrorText(null)
        runInBackground {
            val existing = targets.filter { revsetAliasExists(it.commandExecutor, aliasScope, aliasName) }
            runLater {
                if (existing.isEmpty() || confirmOverwrite(aliasName)) {
                    save(aliasName, aliasScope, targets)
                } else {
                    isOKActionEnabled = true
                }
            }
        }
    }

    private fun confirmOverwrite(aliasName: String) = Messages.showYesNoDialog(
        project,
        JujutsuBundle.message("log.filter.revset.alias.overwrite.message", aliasName),
        JujutsuBundle.message("log.filter.revset.alias.overwrite.title"),
        Messages.getWarningIcon()
    ) == Messages.YES

    private fun save(aliasName: String, aliasScope: CommandExecutor.ConfigScope, targets: List<JujutsuRepository>) {
        runInBackground {
            val failure = targets
                .map { saveRevsetAlias(it.commandExecutor, aliasScope, aliasName, expression) }
                .firstOrNull { it is CommandResult.Failure }
            val error = when {
                failure != null -> JujutsuBundle.message("log.filter.revset.alias.error.save", failure.stderr.trim())
                else -> validateRevsetFilter(repos, aliasName, baseFor).values.firstOrNull()?.let {
                    JujutsuBundle.message("log.filter.revset.alias.error.resolve", conciseRevsetError(it))
                }
            }
            runLater {
                if (error == null) {
                    // DialogWrapper.doOKAction() is a no-op while OK is disabled, so re-enable it to close.
                    isOKActionEnabled = true
                    super.doOKAction()
                    onSaved(aliasName)
                } else {
                    isOKActionEnabled = true
                    setErrorText(error, nameField)
                }
            }
        }
    }
}
