package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.util.text.StringUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict

/**
 * Pane titles (current, [middleTitle], last) for a three-way conflict view.
 *
 * jj's marker labels include the change description, which may contain `<...>` text (GitHub
 * #138). The platform shows diff/merge titles in a copyable [com.intellij.ui.components.JBLabel],
 * which renders as HTML, so the labels are escaped here - at the diff boundary only, since the
 * banner and actions show the same labels as plain text. Fallbacks to a plain side number are
 * used for markers with no commit identity (snapshot style).
 */
internal fun conflictPaneTitles(conflict: ExtractedConflict, middleTitle: String): List<String> = listOf(
    conflict.currentTitle?.let(StringUtil::escapeXmlEntities) ?: JujutsuBundle.message("merge.column.side1"),
    middleTitle,
    conflict.lastTitle?.let(StringUtil::escapeXmlEntities) ?: JujutsuBundle.message("merge.column.side2")
)
