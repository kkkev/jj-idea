package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.Presentation

/**
 * Sets the presentation text literally. [Presentation.setText] parses `_`/`&` as mnemonic markers
 * and swallows them, so use this for any text that embeds a user-supplied name (a bookmark such
 * as `my_feature` would otherwise render as `myfeature` - GitHub #156).
 */
fun Presentation.setPlainText(text: String?) = setText(text, false)
