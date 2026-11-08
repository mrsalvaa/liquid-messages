// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.sms

import dev.liquidmessages.core.phone.PhoneNumbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationsTest {
    private val numbers = PhoneNumbers("AR")

    private fun msg(id: Long, thread: Long, address: String, date: Long, box: Box = Box.INBOX, read: Boolean = true) =
        Message(id, thread, address, "hola $id", date, box, read)

    @Test
    fun tiposDeRemitente() {
        assertEquals(SenderKind.PHONE, SenderKind.of("+1 202 555 0143"))
        assertEquals(SenderKind.PHONE, SenderKind.of("011 1345-6789"))
        assertEquals(SenderKind.SHORT_CODE, SenderKind.of("21212"))
        assertEquals(SenderKind.ALPHANUMERIC, SenderKind.of("BANCO"))
        assertEquals(SenderKind.ALPHANUMERIC, SenderKind.of("Info24"))
        assertFalse(SenderKind.ALPHANUMERIC.canReply)
        assertTrue(SenderKind.SHORT_CODE.canReply)
    }

    @Test
    fun laMismaPersonaConFormatosDistintosTieneLaMismaClave() {
        assertEquals(
            Conversations.key(listOf("+54 9 11 1345-6789"), numbers),
            Conversations.key(listOf("011 15 1345-6789"), numbers),
        )
        assertEquals(Conversations.key(listOf("BANCO"), numbers), Conversations.key(listOf("banco"), numbers))
    }

    @Test
    fun claveDeGrupoNoDependeDelOrden() {
        val a = Conversations.key(listOf("+12025550143", "+12025550188"), numbers)
        val b = Conversations.key(listOf("+1 202 555 0188", "+1 202 555 0143"), numbers)
        assertEquals(a, b)
    }

    @Test
    fun agrupaOrdenaYCuentaNoLeidos() {
        val list = listOf(
            msg(1, 10, "+12025550143", 100),
            msg(2, 10, "+12025550143", 300, read = false),
            msg(3, 10, "+12025550143", 200, Box.SENT),
            msg(4, 20, "BANCO", 250, read = false),
            msg(5, 20, "BANCO", 260, read = false),
            msg(6, 30, "+12025550188", 400, Box.DRAFT),
            msg(7, 30, "+12025550188", 50),
        )
        val c = Conversations.build(list, numbers)
        assertEquals(listOf(10L, 20L, 30L), c.map { it.threadId })
        assertEquals(2L, c[0].last.id)
        assertEquals(1, c[0].unread)
        assertEquals(2, c[1].unread)
        // El borrador no cuenta como último mensaje.
        assertEquals(7L, c[2].last.id)
        assertFalse(c[1].canReply)
        assertTrue(c[0].canReply)
    }

    @Test
    fun fijadasPrimeroYArchivadasAparte() {
        val list = listOf(msg(1, 10, "+12025550143", 100), msg(2, 20, "+12025550188", 200), msg(3, 30, "21212", 300))
        val pinKey = Conversations.key(listOf("+12025550143"), numbers)
        val archKey = Conversations.key(listOf("21212"), numbers)
        val prefs = { k: String ->
            when (k) {
                pinKey -> ConversationPrefs(pinned = true)
                archKey -> ConversationPrefs(archived = true)
                else -> ConversationPrefs()
            }
        }
        assertEquals(listOf(10L, 20L), Conversations.build(list, numbers, prefs).map { it.threadId })
        assertEquals(listOf(30L), Conversations.build(list, numbers, prefs, archived = true).map { it.threadId })
    }

    @Test
    fun hiloEnOrdenDeLectura() {
        val list = listOf(msg(3, 10, "x", 300), msg(1, 10, "x", 100), msg(2, 10, "x", 100), msg(9, 11, "y", 50))
        assertEquals(listOf(1L, 2L, 3L), Conversations.thread(list, 10).map { it.id })
    }

    @Test
    fun tipoDesconocidoEsRecibido() {
        assertEquals(Box.INBOX, Box.of(99))
        assertTrue(Box.FAILED.outgoing)
    }
}
