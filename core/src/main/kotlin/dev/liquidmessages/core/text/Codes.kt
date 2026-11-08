// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.text

/**
 * Códigos de verificación en un mensaje ("Tu código es 482913"), para ofrecer copiarlos. Todo en
 * el celu: el texto no sale de acá.
 *
 * Se exige una palabra clave (código, clave, verificación, PIN, code…) para no tomar montos,
 * números de teléfono, fechas ni horas. Un código es de 4 a 8 dígitos, o dos grupos de 3 separados
 * por espacio o guion, con un prefijo opcional de letras y guion ("G-482913"). Se copia sin
 * separadores ni prefijo.
 */
object Codes {
    private val KEYWORDS = listOf(
        "codigo", "code", "clave", "contrasena", "password", "passcode", "pin", "otp", "token",
        "verificacion", "verification", "verificar", "verify", "autenticacion", "authentication",
        "confirmacion", "confirmation", "acceso", "login", "ingreso", "seguridad", "security",
    )

    // Delante no puede haber un dígito, letra, símbolo de moneda ni signos que lo hagan parte de
    // otra cosa (1.234, 12/05, 10:30, +54…). Detrás, ni dígito, ni letra, ni %, /, : o un decimal.
    private val CANDIDATE = Regex(
        """(?<![\p{L}\p{N}$€£¥.,/:+#-])(?:\p{Lu}{1,3}-)?(\d{3}[ -]\d{3}|\d{4,8})(?![\p{L}\p{N}%/:]|[.,]\d)""",
    )

    private val KEYWORD = Regex("""\b(${KEYWORDS.joinToString("|")})\b""")

    /** El código más probable, sin separadores; null si no hay ninguno claro. */
    fun find(message: String): String? {
        // Texto normalizado con la posición de cada letra en el original: normalizar puede quitar
        // caracteres (tildes ya separadas, selectores de variante de los emojis) o sumarlos.
        val origin = ArrayList<Int>(message.length)
        val folded = buildString {
            message.forEachIndexed { i, c ->
                val f = Fold.fold(c.toString())
                append(f)
                repeat(f.length) { origin += i }
            }
        }
        val keywords = KEYWORD.findAll(folded).map { origin[it.range.first]..origin[it.range.last] }.toList()
        if (keywords.isEmpty()) return null
        val candidates = CANDIDATE.findAll(message).filterNot { partOfLongerNumber(message, it.range) }.toList()
        if (candidates.isEmpty()) return null
        val best = candidates.minByOrNull { m ->
            val start = m.range.first
            // Lo más cerca de una palabra clave; si está después de ella, mejor.
            keywords.minOf { k ->
                val after = start > k.last
                val gap = if (after) start - k.last else k.first - m.range.last
                gap * (if (after) 1 else 2)
            } + if (looksLikeYear(m.groupValues[1])) 1000 else 0
        } ?: return null
        return best.groupValues[1].filter(Char::isDigit)
    }

    /**
     * Pegado a otro grupo de dígitos por un espacio o guion ("0800 133 1234", "+54 9 11 1345-6789"):
     * es un teléfono o una cuenta, no un código.
     */
    private fun partOfLongerNumber(text: String, range: IntRange): Boolean {
        fun digitAt(i: Int) = i in text.indices && text[i].isDigit()
        fun sep(i: Int) = i in text.indices && (text[i] == ' ' || text[i] == '-')
        val before = range.first - 1
        val after = range.last + 1
        return (sep(before) && digitAt(before - 1)) || (sep(after) && digitAt(after + 1))
    }

    /** 1900–2099 solo como desempate: un código puede ser 2024, pero es menos probable. */
    private fun looksLikeYear(s: String): Boolean = s.length == 4 && (s.startsWith("19") || s.startsWith("20"))
}
