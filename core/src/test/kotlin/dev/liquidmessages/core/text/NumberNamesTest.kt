// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NumberNamesTest {
    // Números reservados para ficción (+1 NXX 555-01XX), comparados por sus dígitos.
    private val book = mapOf("12025550143" to "Lucas Rodríguez", "2025550143" to "Lucas Rodríguez", "12025550199" to "Ana Pérez")
    private fun nameOf(n: String) = book[n.filter(Char::isDigit)]

    @Test
    fun numeroDeContactoPasaANombre() {
        val r = NumberNames.replace("Llamada perdida de +12025550143 a las 14:30", ::nameOf)
        assertEquals("Llamada perdida de Lucas Rodríguez a las 14:30", r.text)
        val n = r.names.single()
        assertEquals("Lucas Rodríguez", r.text.substring(n.range))
        assertEquals("+12025550143", n.number)
    }

    @Test
    fun desconocidoQuedaIgual() {
        val text = "Llamada perdida de +12025550111"
        val r = NumberNames.replace(text, ::nameOf)
        assertEquals(text, r.text)
        assertTrue(r.names.isEmpty())
    }

    @Test
    fun variosNumerosYFormatos() {
        val r = NumberNames.replace("Te llamaron: +1 (202) 555-0143 y +12025550199. Otro: +12025550111.", ::nameOf)
        assertEquals("Te llamaron: Lucas Rodríguez y Ana Pérez. Otro: +12025550111.", r.text)
        assertEquals(listOf("+1 (202) 555-0143", "+12025550199"), r.names.map { it.number })
        assertEquals(listOf("Lucas Rodríguez", "Ana Pérez"), r.names.map { r.text.substring(it.range) })
    }

    @Test
    fun fechaPegadaAlNumeroNoLoRompe() {
        val r = NumberNames.replace("Llamada perdida del 2025550143 12/11 14:30", ::nameOf)
        assertEquals("Llamada perdida del Lucas Rodríguez 12/11 14:30", r.text)
        assertEquals("2025550143", r.names.single().number)
    }

    @Test
    fun codigosYTextoSinNumeros() {
        assertEquals("Tu código es 123456", NumberNames.replace("Tu código es 123456", ::nameOf).text)
        assertEquals("Hola, ¿cómo va?", NumberNames.replace("Hola, ¿cómo va?", ::nameOf).text)
    }
}
