// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.text

/**
 * Enlaces, emails y teléfonos dentro de un mensaje, para poder tocarlos. Solo se marcan: la app
 * nunca abre ni visita un enlace por su cuenta (ni para una vista previa), y antes de abrirlo
 * muestra la dirección completa.
 *
 * Enlaces: solo con `http://`, `https://` o `www.`. Un dominio suelto ("ejemplo.com") no se marca:
 * hay demasiados falsos positivos ("hola.como"), y un enlace de estafa suele venir con esquema.
 */
object Links {
    enum class Kind { WEB, EMAIL, PHONE }

    data class Link(val range: IntRange, val text: String, val kind: Kind) {
        /** Lo que se abre: con esquema para la web, `mailto:` y `tel:` para el resto. */
        val target: String
            get() = when (kind) {
                Kind.WEB -> if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
                Kind.EMAIL -> "mailto:$text"
                Kind.PHONE -> "tel:" + text.filter { it.isDigit() || it == '+' }
            }

        /** El dominio, para mostrarlo antes de abrir ("¿Abrir ejemplo.com?"); null si no es web. */
        val host: String?
            get() = if (kind != Kind.WEB) null
            else target.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfter('@').substringBefore(':').lowercase()
    }

    private val WEB = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")
    private val EMAIL = Regex("""(?<![\w.+-])[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    // Un teléfono: + opcional y de 7 a 15 dígitos, con espacios, guiones o paréntesis entre medio.
    private val PHONE = Regex("""(?<![\w+])\+?\(?\d[\d ()-]{5,}\d(?![\w])""")

    fun find(text: String): List<Link> {
        val found = mutableListOf<Link>()
        fun free(r: IntRange) = found.none { it.range.first <= r.last && r.first <= it.range.last }

        for (m in WEB.findAll(text)) {
            val r = trim(text, m.range)
            if (r.last - r.first >= 4) found += Link(r, text.substring(r), Kind.WEB)
        }
        for (m in EMAIL.findAll(text)) {
            val r = trim(text, m.range)
            if (free(r)) found += Link(r, text.substring(r), Kind.EMAIL)
        }
        for (m in PHONE.findAll(text)) {
            val r = m.range
            val digits = text.substring(r).count(Char::isDigit)
            if (digits in 7..15 && free(r)) found += Link(r, text.substring(r), Kind.PHONE)
        }
        return found.sortedBy { it.range.first }
    }

    /**
     * Saca la puntuación del final que casi seguro es de la oración ("Mirá https://ejemplo.com.")
     * y un paréntesis de cierre que no abrió dentro del enlace.
     */
    private fun trim(text: String, range: IntRange): IntRange {
        var end = range.last
        while (end > range.first) {
            val c = text[end]
            val sub = text.substring(range.first, end + 1)
            val unbalanced = (c == ')' && sub.count { it == '(' } < sub.count { it == ')' }) ||
                (c == ']' && sub.count { it == '[' } < sub.count { it == ']' })
            if (c in ".,;:!?¡¿'\"»”" || unbalanced) end-- else break
        }
        return range.first..end
    }
}
