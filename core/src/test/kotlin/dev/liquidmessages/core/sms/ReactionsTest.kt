// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.core.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReactionsTest {
    private fun msg(id: Long, body: String, out: Boolean = false, address: String = "+12025550143") =
        Message(id, 1, address, body, id * 1000, if (out) Box.SENT else Box.INBOX)

    @Test
    fun frasesDeIphone() {
        assertEquals(Reactions.Parsed("👍", "Nos vemos a las 8", false), Reactions.parse("Le gustó “Nos vemos a las 8”"))
        assertEquals(Reactions.Parsed("😂", "jaja", false), Reactions.parse("Laughed at \"jaja\""))
        assertEquals(Reactions.Parsed("❤️", "Gracias", false), Reactions.parse("Loved “Gracias”"))
        assertEquals(Reactions.Parsed("❓", "¿Mañana?", false), Reactions.parse("Questioned “¿Mañana?”"))
    }

    @Test
    fun conCualquierEmoji() {
        assertEquals(Reactions.Parsed("🙏", "Te paso a buscar", false), Reactions.parse("Reacted 🙏 to “Te paso a buscar”"))
        assertEquals(Reactions.Parsed("🔥", "Ganamos", false), Reactions.parse("Reaccionó con 🔥 a «Ganamos»"))
        assertEquals(Reactions.Parsed("😂", "jaja", true), Reactions.parse("Removed 😂 from “jaja”"))
        assertEquals(Reactions.Parsed(null, "jaja", true), Reactions.parse("Removed a like from “jaja”"))
    }

    @Test
    fun textosComunesNoSonReacciones() {
        assertNull(Reactions.parse("Le gustó la película"))
        assertNull(Reactions.parse("Reacted badly to \"that\""))
        assertNull(Reactions.parse("Hola"))
        assertNull(Reactions.parse("Me dijo \"llego tarde\""))
    }

    @Test
    fun seUbicaSobreElOriginal() {
        val list = listOf(msg(1, "Nos vemos a las 8", out = true), msg(2, "Le gustó “Nos vemos a las 8”"), msg(3, "Perfecto"))
        val r = Reactions.apply(list)
        assertEquals(listOf(1L, 3L), r.visible.map { it.id })
        assertEquals(mapOf(1L to listOf("👍")), r.reactions)
    }

    @Test
    fun sinOriginalSeMuestraComoTexto() {
        val list = listOf(msg(1, "Hola"), msg(2, "Le gustó “Otra cosa”"))
        val r = Reactions.apply(list)
        assertEquals(listOf(1L, 2L), r.visible.map { it.id })
        assertEquals(emptyMap(), r.reactions)
    }

    @Test
    fun citaCortadaYQuitarYReemplazar() {
        val largo = "Mañana paso a buscarte por tu casa a eso de las nueve y media, avisame"
        val list = listOf(
            msg(1, largo, out = true),
            msg(2, "Le gustó “Mañana paso a buscarte por tu casa…”"),
            msg(3, "Loved “Mañana paso a buscarte por tu casa…”"),
            msg(4, "Removed a heart from “Mañana paso a buscarte por tu casa…”"),
        )
        val r = Reactions.apply(list)
        assertEquals(listOf(1L), r.visible.map { it.id })
        assertEquals(emptyMap(), r.reactions)
    }

    @Test
    fun variasPersonas() {
        val list = listOf(
            msg(1, "¿Pizza?", out = true),
            msg(2, "Le encantó “¿Pizza?”", address = "+12025550101"),
            msg(3, "Le gustó “¿Pizza?”", address = "+12025550102"),
        )
        assertEquals(mapOf(1L to listOf("❤️", "👍")), Reactions.apply(list).reactions)
    }
}
