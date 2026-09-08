package `in`.kkkev.jjidea.util

fun <T> String.splitByComma(transform: (String) -> T) =
    if (this.isEmpty()) emptyList() else this.split(",").map(transform)

private val SNAKE_TO_CAMEL_PATTERN = "_([a-z])".toRegex()
fun String.snakeToCamelCase() = lowercase().replace(SNAKE_TO_CAMEL_PATTERN) { it.groupValues[1].uppercase() }
