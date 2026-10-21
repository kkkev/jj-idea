package `in`.kkkev.jjidea.ui.components

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.util.textCompletion.TextCompletionProvider
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.cli.RevsetAlias
import `in`.kkkev.jjidea.jj.revset.REVSET_FUNCTIONS
import `in`.kkkev.jjidea.jj.revset.RevsetCandidate
import `in`.kkkev.jjidea.jj.revset.RevsetCandidateKind
import `in`.kkkev.jjidea.jj.revset.completionPrefix
import `in`.kkkev.jjidea.jj.revset.isRevsetSymbolChar
import `in`.kkkev.jjidea.jj.revset.revsetCandidates
import `in`.kkkev.jjidea.jj.stateModel

/**
 * Completes revset functions, bookmarks, tags and aliases (jj-idea-ehwm.7). Reads only the state
 * model's cached references and the field's alias snapshot, so completing never spawns a jj process.
 * [enabled] gates the whole provider (the search box only completes in revset mode).
 */
class RevsetCompletionProvider(
    private val project: Project,
    private val repos: () -> List<JujutsuRepository>,
    private val aliases: () -> List<RevsetAlias>,
    private val enabled: () -> Boolean
) : TextCompletionProvider {
    override fun getAdvertisement(): String? = null

    override fun getPrefix(text: String, offset: Int): String? = if (enabled()) completionPrefix(text, offset) else null

    override fun applyPrefixMatcher(result: CompletionResultSet, prefix: String): CompletionResultSet =
        result.withPrefixMatcher(prefix)

    override fun acceptChar(c: Char): CharFilter.Result? =
        if (isRevsetSymbolChar(c) || c in "-+.@") CharFilter.Result.ADD_TO_PREFIX else CharFilter.Result.HIDE_LOOKUP

    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        prefix: String,
        result: CompletionResultSet
    ) {
        val references = project.stateModel.references.value
        val perRepo = repos().mapNotNull { references[it] }
        val bookmarks = perRepo.flatMap { r -> r.bookmarks.map { it.bookmark.name.toString() } }
        val tags = perRepo.flatMap { r -> r.tags.map { it.tag.name } }
        revsetCandidates(prefix, REVSET_FUNCTIONS, bookmarks, tags, aliases()).forEach {
            result.addElement(element(it))
        }
    }

    private fun element(candidate: RevsetCandidate): LookupElement {
        val builder = LookupElementBuilder.create(candidate.text)
            .withTailText(candidate.tail.ifEmpty { null }, true)
            .withTypeText(candidate.kind.label, true)
        return if (candidate.kind == RevsetCandidateKind.FUNCTION) {
            builder.withInsertHandler { context, _ -> insertCall(context, candidate.takesArgs) }
        } else {
            builder
        }
    }

    /** Appends `()`, leaving the caret inside when the function takes arguments, and re-opens completion there. */
    private fun insertCall(context: InsertionContext, takesArgs: Boolean) {
        val document = context.document
        val tail = context.tailOffset
        // Typing over an existing call (`anc|(@)`) must not add a second pair of parens.
        val alreadyCalled = tail < document.textLength && document.charsSequence[tail] == '('
        if (!alreadyCalled) document.insertString(tail, "()")
        context.editor.caretModel.moveToOffset(if (takesArgs || alreadyCalled) tail + 1 else tail + 2)
        if (takesArgs) AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
    }
}
