package `in`.kkkev.jjidea.ui

import com.intellij.ui.SimpleTextAttributes
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.text.StringBuilder

class PlainStringBuilderTextCanvas : TextCanvas {
    val result = StringBuilder()

    override fun append(text: String, style: SimpleTextAttributes) {
        result.append(text)
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildText(builderAction: TextCanvas.() -> Unit): String {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    return PlainStringBuilderTextCanvas().apply(builderAction).result.toString()
}
