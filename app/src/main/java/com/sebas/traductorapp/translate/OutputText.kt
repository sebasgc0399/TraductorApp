package com.sebas.traductorapp.translate

/**
 * Normaliza el texto traducido antes de mostrarlo o guardarlo. A veces el modelo escapa de más los saltos de línea
 * dentro del JSON y llega un "\n" literal (barra + n); aquí se convierte en un salto real.
 */
object OutputText {
    private val LITERAL_BREAK = Regex("""\\r\\n|\\n|\\r""")
    private val LITERAL_TAB = Regex("""\\t""")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val BLANK_LINES = Regex("\n{2,}")

    fun clean(text: String): String = text
        .replace(LITERAL_BREAK, "\n")
        .replace(LITERAL_TAB, " ")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .joinToString("\n") { it.replace(SPACES, " ").trim() }
        .replace(BLANK_LINES, "\n")
        .trim()
}
