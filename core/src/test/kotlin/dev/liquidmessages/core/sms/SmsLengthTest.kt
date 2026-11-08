// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsLengthTest {
    @Test
    fun vacio() {
        val i = SmsLength.of("")
        assertEquals(0, i.parts)
        assertEquals(160, i.remaining)
    }

    @Test
    fun gsmEnUnaParte() {
        val i = SmsLength.of("Hola, ¿cómo andás?".replace("ó", "o").replace("á", "a"))
        assertFalse(i.unicode)
        assertEquals(1, i.parts)
        assertEquals(160 - i.units, i.remaining)
    }

    @Test
    fun ciento_sesenta_justos_y_uno_mas() {
        assertEquals(1, SmsLength.of("a".repeat(160)).parts)
        val two = SmsLength.of("a".repeat(161))
        assertEquals(2, two.parts)
        assertEquals(153 * 2 - 161, two.remaining)
        assertEquals(3, SmsLength.of("a".repeat(307)).parts)
    }

    @Test
    fun laEnieYLaEConTildeSonGsm() {
        val i = SmsLength.of("Mañana café, señor")
        assertFalse(i.unicode)
    }

    @Test
    fun lasOtrasTildesPasanAUnicode() {
        val i = SmsLength.of("Sí, está")
        assertTrue(i.unicode)
        assertEquals(listOf("í", "á"), i.nonGsm)
        assertEquals(70 - 8, i.remaining)
        assertEquals(2, SmsLength.of("á".repeat(71)).parts)
        assertEquals(67 * 2 - 71, SmsLength.of("á".repeat(71)).remaining)
    }

    @Test
    fun extendidosCuentanDoble() {
        val i = SmsLength.of("100€")
        assertFalse(i.unicode)
        assertEquals(5, i.units)
        assertEquals(1, SmsLength.of("€".repeat(80)).parts)
        assertEquals(2, SmsLength.of("€".repeat(81)).parts)
    }

    @Test
    fun unExtendidoNoSeParteEntreDosPartes() {
        // 152 letras + € (2 septetos) no entran en 153: el € pasa entero a la segunda parte.
        val i = SmsLength.of("a".repeat(152) + "€" + "a".repeat(10))
        assertEquals(2, i.parts)
        assertEquals(153 - 12, i.remaining)
    }

    @Test
    fun emojiOcupaDosYNoSeParte() {
        val i = SmsLength.of("👍")
        assertTrue(i.unicode)
        assertEquals(2, i.units)
        assertEquals(listOf("👍"), i.nonGsm)
        // 66 letras + emoji + 3 = 71 unidades: en dos partes; el emoji no entra en lo que queda
        // de la primera (1 lugar) y va entero a la segunda.
        val split = SmsLength.of("á".repeat(66) + "👍" + "á".repeat(3))
        assertEquals(2, split.parts)
        assertEquals(67 - 5, split.remaining)
    }

    @Test
    fun simplificarVuelveAGsm() {
        val s = SmsLength.simplify("Sí, está “bien”… ¿Qué tal? Mañana, café.")
        assertEquals("Si, esta \"bien\"... ¿Qué tal? Mañana, café.", s)
        assertFalse(SmsLength.of(s).unicode)
    }

    @Test
    fun simplificarSoloCambiaLoQueNoEsGsm() {
        val gsm = "ñandú".replace("ú", "u") + " é ü à ç"
        assertEquals(gsm.replace("ç", "Ç"), SmsLength.simplify(gsm))
    }
}
