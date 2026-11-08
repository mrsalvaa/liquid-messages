// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpamTest {
    private val unknown = "+12025550143"

    @Test
    fun estafasTipicas() {
        assertTrue(Spam.looksLikeSpam(unknown, "Ganaste un premio!!! Reclamalo en http://premio.example.net/reclamar"))
        assertTrue(Spam.looksLikeSpam(unknown, "Tu paquete está retenido, pagá el envío en https://correo-ar.example.com/pago"))
        assertTrue(Spam.looksLikeSpam(unknown, "Your account has been suspended. Verify at https://bank.example.org/login"))
        assertTrue(Spam.looksLikeSpam(unknown, "Mirá esto bit.ly/3xYz12"))
        assertTrue(Spam.looksLikeSpam(unknown, "Hola, mirá http://192.168.10.5/x"))
    }

    @Test
    fun mensajesNormales() {
        assertFalse(Spam.looksLikeSpam(unknown, "Te paso la dirección: https://example.com/mapa?id=42"))
        assertFalse(Spam.looksLikeSpam(unknown, "¿Venís a la cena del sábado?"))
        assertFalse(Spam.looksLikeSpam(unknown, "Ganaste la apuesta jaja"))
    }

    @Test
    fun bancosYServiciosNoSeFiltran() {
        // Remitentes con nombre o números cortos: suelen ser servicios reales.
        assertFalse(Spam.looksLikeSpam("BANCO", "Verificá tu compra en https://banco.example.com"))
        assertFalse(Spam.looksLikeSpam("31313", "Ganaste un premio!!! Reclamalo en http://premio.example.net/reclamar"))
    }
}
