package `in`.kkkev.jjidea.ui.components

import java.awt.Color
import java.net.URI
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.text.StringBuilder

class PlainStringTextCanvas : TextCanvas {
    val result = StringBuilder()
    override fun styled(style: Int, builder: TextCanvas.() -> Unit) = this.builder()
    override fun colored(color: Color, builder: TextCanvas.() -> Unit) = this.builder()
    override fun linked(target: URI, builder: TextCanvas.() -> Unit) {
        this.builder()
        append(" <$target>")
    }

    override fun append(text: String) {
        result.append(text)
    }
}

@OptIn(ExperimentalContracts::class)
inline fun buildText(builderAction: TextCanvas.() -> Unit): String {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    return PlainStringTextCanvas().apply(builderAction).result.toString()
}
