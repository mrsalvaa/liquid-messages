// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages

/**
 * Los únicos números que crea la herramienta, de rangos reservados para ficción: +1 NXX 555-01XX en
 * Norteamérica y +44 7700 900XXX en el Reino Unido. Las apps de sincronización leen los contactos
 * del sistema, así que un número de prueba con formato real podría ser la línea de alguien.
 */
object FakeNumbers {
    private val areas = listOf("202", "212", "312", "415", "617", "702")
    private val reserved = listOf(Regex("""1\d{3}55501\d{2}"""), Regex("""447700900\d{3}"""))

    /** Distintos para i en 0 until 1000, con y sin espacios para probar que coincidan igual. */
    fun number(i: Int): String {
        val k = i / 2
        val line = (k % 100).toString().padStart(2, '0')
        val uk = (k % 1000).toString().padStart(3, '0')
        val area = areas[(k / 100) % areas.size]
        return when (i % 4) {
            0 -> "+1 $area 555-01$line"
            1 -> "+44 7700 900$uk"
            2 -> "+1${area}55501$line"
            else -> "+447700900$uk"
        }
    }

    /** Lo último antes de escribir en el sistema: tiene que empezar con + y caer en un rango reservado. */
    fun isReserved(raw: String): Boolean {
        val text = raw.trim()
        val digits = text.filter(Char::isDigit)
        return text.startsWith("+") && reserved.any { it.matches(digits) }
    }
}
