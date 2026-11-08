// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.sms

/**
 * Cuántas partes ocupa un SMS y cuánto falta para la siguiente, para el contador del campo de
 * escribir. Sigue GSM 03.38 sin tablas de idioma: el operador puede activar la tabla española
 * (que mete á, í, ó, ú en 7 bits) y entonces el sistema parte distinto; quien decide al enviar es
 * SmsManager.divideMessage, esto es solo lo que se muestra.
 *
 * - 7 bits: 160 caracteres en una parte, 153 por parte si son varias. Los de la tabla extendida
 *   (`^ { } \ [ ] ~ | €` y el salto de página) cuentan doble.
 * - Si hay un solo carácter fuera de GSM (á, un emoji…), todo va en UCS-2: 70 en una parte y 67
 *   por parte si son varias, contando unidades UTF-16 (un emoji ocupa dos).
 */
object SmsLength {
    data class Info(
        val unicode: Boolean,
        /** Lo que ocupa el texto: septetos en GSM, unidades UTF-16 en UCS-2. */
        val units: Int,
        val parts: Int,
        /** Lo que todavía entra en la última parte. */
        val remaining: Int,
        /** Caracteres que obligan a UCS-2, sin repetir, en el orden en que aparecen. */
        val nonGsm: List<String>,
    )

    private const val BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001BÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val EXTENDED = "\u000C^{}\\[~]|€"

    private val basic: Set<Char> = BASIC.filter { it != '\u001B' }.toSet()
    private val extended: Set<Char> = EXTENDED.toSet()

    fun isGsm(c: Char): Boolean = c in basic || c in extended

    fun of(text: String): Info {
        val nonGsm = text.codePoints().toArray()
            .map { String(Character.toChars(it)) }
            .filter { s -> s.length != 1 || !isGsm(s[0]) }
            .distinct()
        val unicode = nonGsm.isNotEmpty()
        val units = if (unicode) text.length else text.sumOf { if (it in extended) 2 else 1 as Int }
        val single = if (unicode) 70 else 160
        val multi = if (unicode) 67 else 153
        if (units <= single) return Info(unicode, units, if (units == 0) 0 else 1, single - units, nonGsm)
        val (parts, used) = partsOf(text, unicode, multi)
        return Info(unicode, units, parts, multi - used, nonGsm)
    }

    /**
     * Partes reales: un carácter extendido (2 septetos) o un emoji (2 unidades) no se parten entre
     * dos partes, así que a veces sobra un lugar al final de una.
     */
    private fun partsOf(text: String, unicode: Boolean, perPart: Int): Pair<Int, Int> {
        var parts = 1
        var used = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val size = when {
                unicode && Character.isHighSurrogate(c) && i + 1 < text.length -> 2
                !unicode && c in extended -> 2
                else -> 1
            }
            if (used + size > perPart) {
                parts++
                used = 0
            }
            used += size
            i += if (unicode && size == 2) 2 else 1
        }
        return parts to used
    }

    /**
     * Cambia las letras con tilde que no están en GSM por la misma sin tilde (á → a, í → i), para
     * que el mensaje vuelva a 160 por parte. No toca é, ñ, ü ni lo que sí está en GSM.
     */
    fun simplify(text: String): String = buildString(text.length) {
        for (c in text) SIMPLE[c]?.let { append(it) } ?: append(c)
    }

    private val SIMPLE: Map<Char, String> = mapOf(
        'á' to "a", 'í' to "i", 'ó' to "o", 'ú' to "u", 'Á' to "A", 'Í' to "I", 'Ó' to "O", 'Ú' to "U",
        'â' to "a", 'ê' to "e", 'î' to "i", 'ô' to "o", 'û' to "u", 'ã' to "a", 'õ' to "o", 'ç' to "Ç",
        'ï' to "i", 'ë' to "e", 'È' to "E", 'Ì' to "I", 'Ò' to "O", 'Ù' to "U", 'À' to "A",
        '“' to "\"", '”' to "\"", '‘' to "'", '’' to "'", '–' to "-", '—' to "-", '…' to "...",
    )
}
