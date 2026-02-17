package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.ColorUtil
import java.awt.Color
import javax.swing.Icon
import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.javaField

data class IconSpec(val icon: KProperty0<Icon>, val fillColor: Color? = null) {
    val name: String = "${
        icon.javaField!!.declaringClass.name
            .removePrefix("com.intellij.icons.")
            .removePrefix("in.kkkev.jjidea.ui.common.")
            .replace('$', '.')
    }.${icon.name}"

    val qualified get() = "$name${fillColor?.let { "#${ColorUtil.toHex(it)}" } ?: ""}"
}

fun icon(icon: KProperty0<Icon>, fillColor: Color? = null) = IconSpec(icon, fillColor)
