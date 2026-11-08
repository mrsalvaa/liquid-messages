// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinksTest {
    private fun texts(s: String) = Links.find(s).map { it.kind to it.text }

    @Test
    fun webConEsquemaOWww() {
        assertEquals(listOf(Links.Kind.WEB to "https://ejemplo.com/a?b=1"), texts("Mirá https://ejemplo.com/a?b=1."))
        val www = Links.find("Entrá a www.ejemplo.org, dale")
        assertEquals("www.ejemplo.org", www.single().text)
        assertEquals("https://www.ejemplo.org", www.single().target)
    }

    @Test
    fun dominioSueltoNoSeMarca() {
        assertTrue(Links.find("ejemplo.com y hola.como").isEmpty())
    }

    @Test
    fun puntuacionYParentesis() {
        assertEquals("https://ejemplo.com/x", Links.find("(ver https://ejemplo.com/x)").single().text)
        assertEquals("https://es.wikipedia.org/wiki/Mate_(bebida)", Links.find("https://es.wikipedia.org/wiki/Mate_(bebida)!").single().text)
    }

    @Test
    fun hostParaConfirmar() {
        assertEquals("banco.ejemplo.net", Links.find("http://banco.ejemplo.net:8080/login").single().host)
        // Un usuario delante del dominio no engaña: el host es lo que va después de @.
        assertEquals("estafa.example", Links.find("https://banco.com@estafa.example/x").single().host)
    }

    @Test
    fun emails() {
        val l = Links.find("Escribime a ana.perez+sms@ejemplo.com.ar.").single()
        assertEquals(Links.Kind.EMAIL, l.kind)
        assertEquals("ana.perez+sms@ejemplo.com.ar", l.text)
        assertEquals("mailto:ana.perez+sms@ejemplo.com.ar", l.target)
    }

    @Test
    fun telefonos() {
        assertEquals(listOf(Links.Kind.PHONE to "0800 133 1234"), texts("Llamá al 0800 133 1234 hoy"))
        assertEquals("tel:+12025550143", Links.find("o al +1 202 555 0143").single().target)
        assertEquals(listOf(Links.Kind.PHONE to "011 1345-6789"), texts("Mi fijo: 011 1345-6789"))
    }

    @Test
    fun fechasHorasYMontosNoSonTelefonos() {
        assertTrue(Links.find("El 12/05/2026 a las 18:30, son $1.234,50 y el código 482913").isEmpty())
    }

    @Test
    fun unTelefonoDentroDeUnEnlaceNoSeRepite() {
        val l = Links.find("https://ejemplo.com/pedido/12345678")
        assertEquals(1, l.size)
        assertEquals(Links.Kind.WEB, l[0].kind)
    }
}
